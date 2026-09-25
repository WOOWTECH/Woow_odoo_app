"""Offline only: fake ADB/processes, owned temporary files, no device imports."""
import hashlib
import importlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import installed_artifacts as gate
import launcher
import ui_driver


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.p = launcher.Probe()
        self.p.work_deadline = time.monotonic() + 720
        self.p.mount_device = self.root.stat().st_dev
        self.p.receipt['uids'] = dict(zip(gate.EXPECTED, (10213, 10214)))
        self.p.save = Mock()
        self.p.record = Mock()
        self.payload = b'complete artifact'
        self.expected = {k:hashlib.sha256(self.payload).hexdigest() for k in gate.EXPECTED}
        self.calls = []
        self.metadata_count = {}
        self.change = None
        self.mode = 'pass'
        self.p.shell = Mock(side_effect=self.shell)
        for target, kwargs in [
            ('installed_artifacts.TEMP_ROOT', {'new':self.root}),
            ('installed_artifacts.EXPECTED', {'new':self.expected}),
            ('launcher.EVIDENCE', {'new':self.root/'new-run'}),
            ('installed_artifacts.resource_check', {'return_value':{}}),
            ('installed_artifacts.os.path.ismount', {'return_value':True}),
            ('launcher.port_open', {'return_value':True}),
            ('installed_artifacts.bounded_client', {'side_effect':self.client}),
        ]:
            p = patch(target, **kwargs); p.start(); self.addCleanup(p.stop)

    def shell(self, *args, **kwargs):
        self.assertLessEqual(kwargs['maximum'], 8)
        package = args[-1] if args[0] != 'stat' else next(k for k in gate.EXPECTED if k in args[-1])
        key = (package, args[0])
        self.metadata_count[key] = self.metadata_count.get(key, 0) + 1
        later = self.metadata_count[key] > 1
        if args[0] == 'cmd':
            uid = self.p.receipt['uids'][package] + (1 if later and self.change == 'uid' else 0)
            name = package + ('.other' if self.change == 'similar-uid' else '')
            out = f'package:{name} uid:{uid}'
        elif args[0] == 'pm':
            suffix = 'changed' if later and self.change == 'path' else 'original'
            out = f'package:/data/app/~~random==/{package}-{suffix}==/base.apk'
            if self.change == 'split':out += '\npackage:/data/app/split.apk'
        else:
            size = len(self.payload) + (1 if later and self.change == 'size' else 0)
            inode = 10 + (1 if later and self.change == 'inode' else 0)
            out = f'81a4:{size}:123:{inode}'
        return {'exit':0, 'stdout':out}

    def client(self, probe, argv, maximum, reserve=0):
        self.calls.append(argv)
        result = dict(exit=0, timeout=False, reaped=True, stdout='', stderr='')
        if 'pull' in argv:
            self.assertEqual(argv[:5], [self.p.adb_path,'-P','5038','-s','emulator-5580'])
            self.assertEqual(maximum, 120)
            self.assertEqual(reserve, gate.POST_PULL_RESERVE)
            target = Path(argv[-1])
            payload = self.payload
            if self.mode == 'short':payload = payload[:-1]
            if self.mode == 'long':payload += b'x'
            if self.mode == 'mismatch':payload = b'x'*len(payload)
            target.write_bytes(payload)
            if self.mode == 'timeout':result.update(timeout=True, exit=-9)
            if self.mode == 'nonzero':result['exit'] = 1
            if self.mode == 'stop':result['stopped'] = True
            if self.mode == 'write-error':raise OSError('injected write error')
            if self.mode == 'helper-fail' and 'com.github.uiautomator' in argv[-1]:result['exit'] = 1
        else:
            self.assertEqual(maximum, 15)
            hashed = gate.hash_file(argv[-1])
            if self.mode == 'no-eof':hashed['eof'] = False
            if self.mode == 'short-hash':hashed['bytesRead'] -= 1
            if self.mode == 'hash-timeout':result['timeout'] = True
            result['stdout'] = json.dumps(hashed)
        return result

    def assert_no_temp(self):
        self.assertFalse((self.root/'new-run-installed-artifacts').exists())

    def test_complete_both_full_artifacts_and_delete_only_owned_copies(self):
        sentinel = self.root/'original.apk'; sentinel.write_bytes(b'original')
        result = gate.verify_artifacts(self.p)
        self.assertEqual(result, self.expected)
        self.assertEqual(sum('pull' in c for c in self.calls), 2)
        self.assert_no_temp()
        self.assertEqual(sentinel.read_bytes(), b'original')
        for record in self.p.receipt['installedArtifactTransfers']:
            self.assertEqual(record['status'], 'PASS')
            self.assertEqual(record['before'], record['after'])
            self.assertTrue(record['hostHash']['eof'])
            self.assertEqual(record['eofEvidence'], 'adb-sync-completed')
            self.assertTrue(record['temporaryRemoved'])
        self.assertNotIn('installedArtifactSHA256', self.p.receipt)

    def test_transfer_exit_size_eof_timeout_write_failure_all_block_without_retry(self):
        for mode in ('short','long','timeout','nonzero','stop','write-error','no-eof','short-hash','hash-timeout'):
            with self.subTest(mode=mode):
                self.mode = mode; self.calls.clear(); self.metadata_count.clear()
                with self.assertRaises((RuntimeError,OSError)):gate.verify_artifacts(self.p)
                self.assertEqual(sum('pull' in c for c in self.calls), 1)
                self.assert_no_temp()
                self.assertEqual(self.p.receipt['installedArtifactTransfers'][-1]['status'],'BLOCKED-transfer')

    def test_complete_mismatch_is_distinct_from_transfer_failure(self):
        self.mode = 'mismatch'
        with self.assertRaisesRegex(RuntimeError,'SHA256-mismatch'):gate.verify_artifacts(self.p)
        record = self.p.receipt['installedArtifactTransfers'][-1]
        self.assertEqual(record['status'],'MISMATCH')
        self.assertTrue(record['hostHash']['eof'])
        self.assert_no_temp()

    def test_uid_path_inode_size_changes_fail_closed(self):
        for change in ('uid','path','inode','size','similar-uid','split'):
            with self.subTest(change=change):
                self.change=change; self.calls.clear(); self.metadata_count.clear()
                with self.assertRaises((RuntimeError,ValueError)):gate.verify_artifacts(self.p)
                self.assertLessEqual(sum('pull' in c for c in self.calls), 1)
                self.assert_no_temp()

    def test_existing_partial_directory_never_reused_or_deleted(self):
        directory = self.root/'new-run-installed-artifacts'; directory.mkdir()
        old = directory/'old.partial'; old.write_bytes(b'old')
        with self.assertRaises(FileExistsError):gate.verify_artifacts(self.p)
        self.assertEqual(old.read_bytes(), b'old')
        self.assertEqual(self.calls, [])

    def test_insufficient_remaining_budget_does_not_start_pull(self):
        self.p.work_deadline = time.monotonic() + gate.POST_PULL_RESERVE - 1
        with self.assertRaises(TimeoutError):gate.verify_artifacts(self.p)
        self.assertEqual(self.calls, []); self.assert_no_temp()

    def test_resource_stop_prevents_pull(self):
        self.p.stop.set()
        with self.assertRaises(RuntimeError):gate.verify_artifacts(self.p)
        self.assertEqual(self.calls, []); self.assert_no_temp()

    def test_one_success_other_failure_never_launches_or_changes_ime(self):
        self.mode = 'helper-fail'
        with patch.object(ui_driver,'UI') as ui:
            with self.assertRaises(RuntimeError):ui_driver.exercise(self.p)
            ui.assert_not_called()
        self.assertEqual(sum('pull' in c for c in self.calls), 2)
        self.assertNotIn('installedArtifactSHA256', self.p.receipt)
        self.assertIsNone(self.p.previous_ime)
        self.assertFalse(any(c.args[0] in ('am','ime','settings','dumpsys') for c in self.p.shell.call_args_list))
        self.assert_no_temp()

    def test_versions_must_both_pass_before_ime(self):
        for info in ('versionCode=9 versionName=1.0-debug','versionCode=1 versionName=1.0-debug'):
            self.p.shell = Mock(return_value={'stdout':info})
            with patch.object(gate,'verify_artifacts',return_value=self.expected),patch.object(ui_driver,'UI') as ui:
                with self.assertRaises(AssertionError):ui_driver.exercise(self.p)
                ui.assert_not_called()
            self.assertFalse(any(c.args[0] in ('am','ime','settings') for c in self.p.shell.call_args_list))


class ProtocolTests(unittest.TestCase):
    def test_path_rejects_empty_multiple_split_wrong_package_and_shell_chars(self):
        package = 'com.apporo.odoo.debug'
        for text in ('', 'package:/data/app/base.apk',
                     'package:/data/app/com.apporo.odoo.debug-other/split.apk',
                     'package:/data/app/com.apporo.odoo.debug.other-x/base.apk',
                     'package:/data/app/com.apporo.odoo.debug-x;id/base.apk',
                     'package:/data/app/com.apporo.odoo.debug-x/base.apk\npackage:/data/app/other.apk'):
            with self.subTest(text=text),self.assertRaises(ValueError):gate.installed_path(package,text)

    def test_stat_rejects_nonregular_zero_oversize_and_malformed(self):
        for text in ('41ed:8:1:1','81a4:0:1:1',f'81a4:{gate.MAX_BYTES+1}:1:1','garbage','81a4:8:1:0'):
            with self.subTest(text=text),self.assertRaises(ValueError):gate.file_stat(text)
        self.assertEqual(gate.file_stat(f'81a4:{gate.MAX_BYTES}:1:1')['size'],gate.MAX_BYTES)

    def test_host_hash_reads_all_bytes_to_eof(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'own'; data=b'x'*(1024**2+17); path.write_bytes(data)
            self.assertEqual(gate.hash_file(path),dict(sha256=hashlib.sha256(data).hexdigest(),bytesRead=len(data),eof=True))

    def test_resource_floor_and_mount_change(self):
        p=Mock(mount_device=7,work_deadline=time.monotonic()+100)
        p.stop.is_set.return_value=False
        for device,internal,external in ((8,4,4),(7,1,4),(7,4,1),(7,4,2)):
            sample=dict(device=device,free={'/':internal*launcher.GIB,'/Volumes/WOOW-BUILD':external*launcher.GIB})
            with patch.object(launcher.resource_guard,'bounded_sample',return_value=sample):
                with self.assertRaises(RuntimeError):gate.resource_check(p,gate.MAX_BYTES)

    def test_imports_do_not_execute_subprocess_or_start_runtime(self):
        with patch.object(subprocess,'Popen',side_effect=AssertionError('unexpected subprocess')),patch.object(subprocess,'run',side_effect=AssertionError('unexpected subprocess')):
            importlib.reload(gate)
            importlib.reload(ui_driver)
            importlib.reload(launcher)

    def test_ui_fixture_order_retains_raw_whitespace(self):
        ui=ui_driver.UI(Mock(receipt={})); ui.p.shell=Mock()
        events=[]
        ui.enter=lambda key,value,credential=False:events.append(('input',key,value))
        ui.checkpoint=lambda name,*args:events.append(('checkpoint',name))
        ui.next=Mock();ui.missing_login=Mock();ui.tap=Mock();ui.image=Mock(return_value=Path('image.png'));ui.persist=Mock()
        import xml.etree.ElementTree as ET
        root=ET.fromstring('<hierarchy><node class="android.widget.EditText" text="example.invalid" hint="Server URL"/><node class="android.widget.EditText" text="local-db" hint="Database Name"/></hierarchy>')
        # Inspect the exact approved source order without replacing product assertions.
        source=Path(ui_driver.__file__).read_text()
        self.assertLess(source.index("'-database-required'"),source.index("self.enter('database_name',' local-db ')"))
        self.assertLess(source.index("'-username-required'"),source.index("self.enter('username',' local-user ',True)"))
        self.assertEqual(source.count('self.enter('),6)
        self.assertIn("assert key in ('server_url','database_name','username')",source)


class ClientTests(unittest.TestCase):
    def run_client(self, duration, budget=120, stop_at=None, deadline=720):
        clock=[0.0]
        p=Mock(work_deadline=deadline,env={})
        p.stop.is_set.side_effect=lambda: stop_at is not None and clock[0]>=stop_at
        child=Mock(returncode=None,stdout=io.StringIO(),stderr=io.StringIO())
        killed=[False]
        def communicate(timeout):
            if killed[0]:child.returncode=-9;return ('partial','killed')
            clock[0]+=min(timeout,max(0,duration-clock[0]))
            if clock[0]<duration:raise subprocess.TimeoutExpired('fake',timeout)
            child.returncode=0;return ('complete','')
        def kill():killed[0]=True
        child.communicate.side_effect=communicate;child.kill.side_effect=kill
        child.poll.side_effect=lambda:child.returncode
        with patch.object(gate.time,'monotonic',side_effect=lambda:clock[0]),patch.object(subprocess,'Popen',return_value=child):
            result=gate.bounded_client(p,['fake'],budget,gate.POST_PULL_RESERVE)
        return result,child

    def test_transfer_longer_than_old15s_but_under120s_succeeds(self):
        result,child=self.run_client(20)
        self.assertTrue(gate.complete(result));self.assertEqual(result['budgetSeconds'],120)
        child.kill.assert_not_called()

    def test_transfer_timeout_stop_and_deadline_kill_and_reap_only_child(self):
        for kwargs in ({'duration':121},{'duration':30,'stop_at':2},{'duration':30,'deadline':gate.POST_PULL_RESERVE+1}):
            with self.subTest(kwargs=kwargs):
                result,child=self.run_client(**kwargs)
                self.assertFalse(gate.complete(result));self.assertTrue(result['reaped'])
                child.kill.assert_called_once()


if __name__=='__main__':unittest.main()
