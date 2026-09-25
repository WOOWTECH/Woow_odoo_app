"""Single installed-path pull per artifact; no guest hash, retries or fallback."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time

import launcher

EXPECTED = {
    'com.apporo.odoo.debug': '903e42a97ba839e0acb576a09cf6cc1a505f82dda8302fefbf0c3d2b2f7f6d96',
    'com.github.uiautomator': '6f85594700ad96de89d012b3767049c2c6988510b68b31b439dd2a6dd93a30c9',
}
MAX_BYTES = 128 * 1024**2
METADATA_SECONDS = 8
HASH_SECONDS = 15
# Three post-pull metadata commands, host hash, client reap and receipt margin.
POST_PULL_RESERVE = 3 * METADATA_SECONDS + HASH_SECONDS + 4
TEMP_ROOT = Path('/Volumes/WOOW-BUILD/apporo-odoo-build')


def available(probe, maximum, reserve=0):
    if probe.stop.is_set():
        raise RuntimeError('resource-stop')
    return launcher.remaining_timeout(probe.work_deadline - reserve, maximum)


def installed_path(package, text):
    pattern = (r'package:(/data/app/(?:~~[A-Za-z0-9_+=-]+/)?' + re.escape(package)
               + r'-[A-Za-z0-9_+=-]+/base\.apk)')
    match = re.fullmatch(pattern, text.strip())
    if not match:
        raise ValueError('installed-path-not-unique-or-unsafe')
    return match.group(1)


def file_stat(text):
    match = re.fullmatch(r'([0-9a-fA-F]+):([0-9]+):([0-9]+):([0-9]+)', text.strip())
    if not match:
        raise ValueError('invalid-guest-stat')
    mode, size, mtime, inode = match.groups()
    if not stat.S_ISREG(int(mode, 16)) or not 0 < int(size) <= MAX_BYTES or int(inode) <= 0:
        raise ValueError('guest-not-regular-or-invalid-size-inode')
    return dict(mode=mode.lower(), size=int(size), mtime=int(mtime), inode=int(inode))


def snapshot(probe, package):
    def shell(*args):
        return probe.shell(*args, maximum=available(probe, METADATA_SECONDS))['stdout']
    uid = int(launcher.exact_uid(package, shell('cmd', 'package', 'list', 'packages', '-U', package)))
    if uid != probe.receipt['uids'][package]:
        raise ValueError('UID-changed-since-firewall-gate')
    path = installed_path(package, shell('pm', 'path', package))
    return dict(package=package, uid=uid, path=path,
                stat=file_stat(shell('stat', '-c', '%f:%s:%Y:%i', path)))


def bounded_client(probe, argv, maximum, reserve=0):
    """Poll only this owned client; resource stop kills/reaps it before cleanup."""
    budget = available(probe, maximum, reserve)
    start = time.monotonic()
    end = start + budget
    child = None
    result = dict(command=argv, startedAt=launcher.utc(), budgetSeconds=budget,
                  exit=None, timeout=False, stdout='', stderr='', reaped=False)
    try:
        child = subprocess.Popen(argv, env=probe.env, text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        result['pid'] = child.pid
        while True:
            if probe.stop.is_set():
                result['stopped'] = True
                break
            left = min(end, probe.work_deadline - reserve) - time.monotonic()
            if left <= 0:
                result['timeout'] = True
                break
            try:
                stdout, stderr = child.communicate(timeout=min(.2, left))
                result.update(exit=child.returncode, stdout=stdout, stderr=stderr)
                # Completion arriving after the budget/stop is not a success.
                result['timeout'] = time.monotonic() >= min(end, probe.work_deadline - reserve)
                result['stopped'] = probe.stop.is_set()
                break
            except subprocess.TimeoutExpired:
                pass
    finally:
        if child is not None:
            try:
                if child.poll() is None:
                    child.kill()
                stdout, stderr = child.communicate(timeout=2)
                result.update(exit=child.returncode, stdout=stdout, stderr=stderr,
                              reaped=child.poll() is not None)
            finally:
                for stream in (child.stdout, child.stderr):
                    if stream is not None:
                        stream.close()
        result.update(endedAt=launcher.utc(), seconds=time.monotonic() - start)
        probe.record(result)
    return result


def complete(result):
    return (result['exit'] == 0 and result['reaped'] and not result['timeout']
            and not result.get('stopped', False))


def hash_file(path):
    """Child protocol: digest only after a real EOF and unchanged host file stat."""
    digest = hashlib.sha256()
    count = 0
    with open(path, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= MAX_BYTES:
            raise ValueError('invalid-host-file')
        while True:
            chunk = stream.read(1024**2)
            if not chunk:
                break
            count += len(chunk)
            if count > MAX_BYTES:
                raise ValueError('host-file-exceeds-cap')
            digest.update(chunk)
        after = os.fstat(stream.fileno())
    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) !=
            (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or count != before.st_size):
        raise ValueError('host-file-changed-or-short-read')
    return dict(sha256=digest.hexdigest(), bytesRead=count, eof=True)


def resource_check(probe, size=0):
    sample = launcher.resource_guard.bounded_sample(
        timeout=available(probe, 8), diagnostic_path=launcher.EVIDENCE/'artifact-resource.jsonl')
    launcher.resource_guard.validate(sample, probe.mount_device)
    if sample['free']['/Volumes/WOOW-BUILD'] < 2 * launcher.GIB + size + 16 * 1024**2:
        raise RuntimeError('insufficient-transfer-and-evidence-space')
    return sample


def verify_artifacts(probe):
    records = probe.receipt.setdefault('installedArtifactTransfers', [])
    directory = TEMP_ROOT / (launcher.EVIDENCE.name + '-installed-artifacts')
    verified = {}
    owned_directory = False
    try:
        resource_check(probe)
        if TEMP_ROOT.is_symlink() or not TEMP_ROOT.is_dir():
            raise RuntimeError('existing-external-root-required')
        directory.mkdir(mode=0o700, exist_ok=False)
        owned_directory = True
        for package, expected in EXPECTED.items():
            target = directory / (package + '.partial')
            item = dict(package=package, expectedSHA256=expected, target=str(target),
                        startedAt=launcher.utc(), status='BLOCKED-transfer', pullAttempts=0)
            records.append(item)
            owned_file = False
            try:
                before = snapshot(probe, package)
                item['before'] = before
                item['guestSize'] = before['stat']['size']
                item['resources'] = resource_check(probe, item['guestSize'])
                available(probe, 120, POST_PULL_RESERVE)
                # Exclusive creation prevents an old partial from becoming evidence.
                with target.open('xb'):
                    pass
                owned_file = True
                if not launcher.port_open(5038):
                    raise RuntimeError('owned-adb-absent-no-restart')
                item['pullAttempts'] = 1
                pull = bounded_client(probe, probe.adb_command + ['pull', before['path'], str(target)],
                                      120, POST_PULL_RESERVE)
                item['pull'] = pull
                host = target.lstat()
                item.update(hostBytes=host.st_size, partialBytes=host.st_size)
                if not complete(pull):
                    raise RuntimeError('incomplete-adb-sync')
                if not stat.S_ISREG(host.st_mode) or host.st_size != item['guestSize']:
                    raise RuntimeError('host-size-or-type-mismatch')
                after = snapshot(probe, package)
                item['after'] = after
                if after != before:
                    raise RuntimeError('installed-metadata-changed')
                item['eofEvidence'] = 'adb-sync-completed'
                result = bounded_client(probe, [sys.executable, '-B', __file__, 'hash', str(target)],
                                        HASH_SECONDS, 2)
                item['hostHashCommand'] = result
                if not complete(result):
                    raise RuntimeError('host-hash-incomplete')
                hashed = json.loads(result['stdout'])
                item['hostHash'] = hashed
                if (hashed.get('eof') is not True or hashed.get('bytesRead') != item['guestSize']
                        or not re.fullmatch('[a-f0-9]{64}', hashed.get('sha256', ''))):
                    raise RuntimeError('host-hash-not-full-eof')
                if hashed['sha256'] != expected:
                    item['status'] = 'MISMATCH'
                    raise RuntimeError('complete-installed-SHA256-mismatch')
                available(probe, 1)
                verified[package] = hashed['sha256']
                item['status'] = 'PASS'
            except BaseException as error:
                item['error'] = repr(error)
                raise
            finally:
                item['endedAt'] = launcher.utc()
                try:
                    if owned_file and target.exists():
                        item['partialBytes'] = target.lstat().st_size
                    probe.save()  # Preserve bytes/metadata before deleting this run's copy.
                finally:
                    if owned_file:
                        # Never follow a replacement mount to perform cleanup.
                        if (os.path.ismount(launcher.resource_guard.MOUNT)
                                and os.stat(TEMP_ROOT).st_dev == probe.mount_device):
                            target.unlink(missing_ok=True)
                            item['temporaryRemoved'] = True
                        else:
                            item['temporaryRemoved'] = False
                        probe.save()
        return verified
    finally:
        if (owned_directory and os.path.ismount(launcher.resource_guard.MOUNT)
                and os.stat(TEMP_ROOT).st_dev == probe.mount_device):
            directory.rmdir()  # Only our newly created empty directory, never recursive.


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] != 'hash':
        raise SystemExit('host hash mode required')
    print(json.dumps(hash_file(sys.argv[2])))
