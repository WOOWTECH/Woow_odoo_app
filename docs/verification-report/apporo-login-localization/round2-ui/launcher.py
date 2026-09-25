"""One approved boot plus bounded offline UI session; no rebuild/install/auth request."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android/round2-ui')
SDK = Path('/Users/elmolin/Library/Android/sdk')
BASE = Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/validation/android-emulator-runtime.json')
GIB = 1024**3
PORTS = (5038, 5580, 5581)
PACKAGES = ('com.apporo.odoo.debug', 'com.github.uiautomator')


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(.25)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def remaining_timeout(deadline, maximum):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('monotonic deadline exhausted')
    return min(maximum, remaining)


def exact_uid(package, listing):
    match = re.fullmatch(r'package:' + re.escape(package) + r' uid:(\d+)', listing.strip())
    if not match or int(match.group(1)) < 10000:
        raise ValueError('Unexpected package UID listing')
    return match.group(1)


def disk_ok(free_bytes, starting=False):
    return all(value >= (3 if starting else 2) * GIB for value in free_bytes.values())


class Probe:
    def __init__(self):
        base = json.loads(BASE.read_text())
        self.env = os.environ.copy()
        self.env.update(base['environmentPaths'])
        self.env.update(ADB_SERVER_SOCKET='tcp:localhost:5038', ANDROID_ADB_SERVER_PORT='5038',
                        ADB_MDNS_AUTO_CONNECT='0', ADB_MDNS_OPENSCREEN='0',
                        ADB_LOCAL_TRANSPORT_MAX_PORT='0', ANDROID_SERIAL='emulator-5580',
                        ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK))
        for key in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'):
            self.env.pop(key, None)
        self.adb_path = str(SDK/'platform-tools/adb')
        self.adb_command = [self.adb_path,'-P','5038','-s','emulator-5580']
        self.sandbox = ['/usr/bin/sandbox-exec','-f',str(ROOT/'docs/verification-report/apporo-phase3/review-sandbox.sb')]
        self.emulator_command = self.sandbox + base['command'] + ['-show-kernel']
        self.started = time.monotonic()
        self.work_deadline = self.started + 260
        self.total_deadline = self.started + 300
        self.stop = threading.Event()
        self.monitor_done = threading.Event()
        self.monitor = None
        self.job = None
        self.server_owned = False
        self.server_pid = None
        self.kernel = None
        self.cleaning = False
        self.previous_ime = None
        self.receipt = dict(startedAt=utc(),status='preflight',command=self.emulator_command,
                            environment={k:self.env[k] for k in (*base['environmentPaths'],
                            'ADB_SERVER_SOCKET','ANDROID_ADB_SERVER_PORT','ADB_MDNS_AUTO_CONNECT',
                            'ADB_MDNS_OPENSCREEN','ADB_LOCAL_TRANSPORT_MAX_PORT','ANDROID_SERIAL',
                            'ANDROID_HOME','ANDROID_SDK_ROOT')},
                            totalLimitSeconds=300,workLimitSeconds=260,
                            sandboxSHA256=hashlib.sha256(Path(self.sandbox[2]).read_bytes()).hexdigest(),
                            appLaunched=False,imeChanged=False,install=False,newDump=False,
                            oldXmlRecovered=False,cleanup=[])

    def save(self):
        (EVIDENCE/'receipt.json').write_text(json.dumps(self.receipt,indent=2))

    def record(self, entry):
        entry.update(at=utc(),elapsedSeconds=round(time.monotonic()-self.started,3))
        with (EVIDENCE/'commands.jsonl').open('a') as log:
            log.write(json.dumps(entry)+'\n')

    def run(self, args, maximum=4, cleanup=False):
        if not cleanup and self.stop.is_set():
            raise RuntimeError('Resource/deadline monitor requested stop')
        timeout = remaining_timeout(self.total_deadline if cleanup else self.work_deadline, maximum)
        began = time.monotonic()
        try:
            result = subprocess.run(args,env=self.env,text=True,capture_output=True,timeout=timeout)
            entry = dict(command=args,exit=result.returncode,stdout=result.stdout.strip(),stderr=result.stderr.strip(),timeout=False)
        except subprocess.TimeoutExpired as exc:
            entry = dict(command=args,exit=None,timeout=True,stdout=(exc.stdout or b'').decode(errors='replace') if isinstance(exc.stdout,bytes) else (exc.stdout or ''),stderr='command timeout')
        except Exception as exc:
            entry = dict(command=args,exit=None,timeout=False,error=repr(exc),stdout='',stderr='')
        entry['seconds']=round(time.monotonic()-began,3)
        self.record(entry)
        return entry

    def adb(self, *args, required=True, maximum=4):
        if not port_open(5038):
            raise RuntimeError('Owned adb5038 absent; no automatic restart')
        result = self.run(self.adb_command+list(args),maximum)
        if required and result.get('exit') != 0:
            raise RuntimeError('ADB command failed: '+repr(args))
        return result

    def shell(self, *args, **kwargs):
        return self.adb('shell',*args,**kwargs)

    def monitor_loop(self):
        tick = 0
        with (EVIDENCE/'disk.jsonl').open('a') as disks, (EVIDENCE/'host.jsonl').open('a') as host:
            while not self.monitor_done.is_set():
                began = time.monotonic()
                try:
                    free = {name:shutil.disk_usage(path).free for name,path in [('internal',ROOT),('external','/Volumes/WOOW-BUILD')]}
                    disks.write(json.dumps(dict(at=utc(),elapsed=began-self.started,freeBytes=free))+'\n');disks.flush()
                    if not disk_ok(free):
                        self.receipt['resourceStop']=dict(at=utc(),reason='volume free below 2GiB',freeBytes=free)
                        self.stop.set()
                    if began >= self.work_deadline:
                        self.stop.set()
                    if tick % 3 == 0:
                        commands=[['/usr/sbin/sysctl','vm.swapusage'],['/usr/bin/vm_stat']]
                        if self.job is not None:
                            commands.append(['/bin/ps','-p',str(self.job.pid),'-o','pid=,rss=,%cpu=,etime='])
                        samples=[]
                        for command in commands:
                            try:
                                p=subprocess.run(command,capture_output=True,text=True,timeout=.4)
                                samples.append(dict(command=command,exit=p.returncode,output=p.stdout.strip()))
                            except Exception as exc:samples.append(dict(command=command,error=type(exc).__name__))
                        host.write(json.dumps(dict(at=utc(),elapsed=began-self.started,samples=samples))+'\n');host.flush()
                except Exception as exc:
                    self.receipt['monitorError']=repr(exc);self.stop.set()
                tick += 1
                self.monitor_done.wait(max(0,2-(time.monotonic()-began)))

    def listener_pid(self, cleanup=False):
        result = self.run(['/usr/sbin/lsof','-nP','-iTCP:5038','-sTCP:LISTEN','-t'],maximum=2,cleanup=cleanup)
        pids=result.get('stdout','').splitlines()
        return int(pids[0]) if result.get('exit')==0 and len(pids)==1 and pids[0].isdigit() else None

    def cleanup(self):
        self.cleaning = True
        # UI cleanup has at most the reserved40s, even on early readiness failure.
        self.total_deadline = min(self.total_deadline, time.monotonic()+40)
        # Each step independent; failure must not bypass final receipt or port checks.
        def attempt(name, action):
            try:
                action();self.receipt['cleanup'].append(dict(step=name,status='completed',at=utc()))
            except Exception as exc:
                self.receipt['cleanup'].append(dict(step=name,status='error',error=repr(exc),at=utc()))
        if self.previous_ime is not None and self.server_owned and port_open(5038):
            def guest_cleanup(args):
                result=self.run(self.adb_command+['shell']+args,maximum=2,cleanup=True)
                if result.get('exit')!=0:raise RuntimeError('Guest cleanup command failed')
                return result['stdout']
            attempt('force-stop own App',lambda:guest_cleanup(['am','force-stop','com.apporo.odoo.debug']))
            attempt('restore previous IME',lambda:guest_cleanup(['ime','set',self.previous_ime]))
            attempt('disable helper IME',lambda:guest_cleanup(['ime','disable','com.github.uiautomator/.AdbKeyboard']))
            attempt('force-stop helper',lambda:guest_cleanup(['am','force-stop','com.github.uiautomator']))
            def confirm_ime():
                actual=guest_cleanup(['settings','get','secure','default_input_method'])
                self.receipt['restoredIME']=actual
                assert actual==self.previous_ime
            attempt('confirm restored IME',confirm_ime)
        def signal_job(sig):
            if self.job is not None and self.job.poll() is None:
                event=dict(signal=signal.Signals(sig).name,pid=self.job.pid,pgid=self.job.pid,
                           reason=self.receipt['status'],at=utc(),elapsed=time.monotonic()-self.started)
                self.receipt.setdefault('signals',[]).append(event);self.save()
                os.killpg(self.job.pid,sig)
        def wait_job():
            if self.job is not None:self.job.wait(timeout=remaining_timeout(self.total_deadline-8,22))
        attempt('owned-qemu SIGTERM',lambda:signal_job(signal.SIGTERM))
        attempt('owned-qemu graceful wait',wait_job)
        attempt('owned-qemu SIGKILL if still alive',lambda:signal_job(signal.SIGKILL))
        if self.job is not None:
            attempt('owned-qemu reap',lambda:self.job.wait(timeout=remaining_timeout(self.total_deadline,2)))
        if self.server_owned:
            def stop_server():
                if port_open(5038):self.run([self.adb_path,'-P','5038','kill-server'],maximum=3,cleanup=True)
            attempt('owned-adb kill-server',stop_server)
            def fallback_server():
                if self.server_pid is not None and port_open(5038) and self.listener_pid(cleanup=True)==self.server_pid:
                    os.kill(self.server_pid,signal.SIGTERM)
            attempt('exact owned-adb PID fallback if needed',fallback_server)
        if self.kernel is not None:attempt('close kernel log',self.kernel.close)
        try:
            for _ in range(8):
                closed={str(p):not port_open(p) for p in PORTS}
                if all(closed.values()) or time.monotonic()>=self.total_deadline:break
                time.sleep(.25)
            self.receipt['portsClosed']=closed
            self.receipt['ownedQemuExited']=self.job is None or self.job.poll() is not None
            self.receipt['cleanupResolved']=all(closed.values()) and self.receipt['ownedQemuExited']
        except Exception as exc:
            self.receipt['cleanupResolved']=False;self.receipt['portVerificationError']=repr(exc)
        finally:
            self.monitor_done.set()
            if self.monitor is not None:self.monitor.join(timeout=1.5)
            self.receipt.update(finishedAt=utc(),totalElapsedSeconds=round(time.monotonic()-self.started,3))
            self.save()

    def execute(self, ui_action=None):
        try:
            free={name:shutil.disk_usage(path).free for name,path in [('internal',ROOT),('external','/Volumes/WOOW-BUILD')]}
            self.receipt['startFreeBytes']=free
            if not disk_ok(free,starting=True):raise RuntimeError('Start requires >=3GiB on both volumes')
            if any(port_open(p) for p in PORTS):raise RuntimeError('Dedicated ports already occupied; no operation')
            self.monitor=threading.Thread(target=self.monitor_loop,daemon=True);self.monitor.start()
            self.save()
            # No foreign listener existed. Only this exact sandboxed server launch is permitted.
            self.server_owned=True
            result=self.run(self.sandbox+[self.adb_path,'-P','5038','--one-device','APPORO_EMULATOR_ONLY_NO_USB','start-server'],maximum=8)
            if result.get('exit')!=0:raise RuntimeError('Owned server startup failed')
            self.server_pid=self.listener_pid();self.receipt['ownedAdbPID']=self.server_pid
            if self.server_pid is None:raise RuntimeError('Unable to record unique owned adb listener')
            if not disk_ok({str(p):shutil.disk_usage(p).free for p in (ROOT,Path('/Volumes/WOOW-BUILD'))},starting=True):
                raise RuntimeError('Capacity fell below 3GiB before emulator launch')
            if self.stop.is_set():raise RuntimeError('Monitor stopped before emulator launch')
            self.kernel=(EVIDENCE/'kernel-emulator.raw.log').open('wb')
            self.job=subprocess.Popen(self.emulator_command,env=self.env,stdout=self.kernel,stderr=subprocess.STDOUT,start_new_session=True)
            self.receipt.update(ownedQemuPID=self.job.pid,status='waiting-for-boot');self.save()
            while time.monotonic()<self.work_deadline and not self.stop.is_set():
                if self.job.poll() is not None:raise RuntimeError('Owned emulator exited before boot')
                state=self.adb('get-state',required=False)
                if state.get('exit')==0 and state['stdout']=='device':
                    boot=self.shell('getprop','sys.boot_completed',required=False)
                    if boot.get('exit')==0 and boot['stdout']=='1':break
                self.stop.wait(min(2,max(0,self.work_deadline-time.monotonic())))
            else:raise TimeoutError('Work deadline/resource stop before boot completed')
            self.receipt['bootCompletedAt']=utc();self.receipt['bootElapsedSeconds']=time.monotonic()-self.started
            assert self.adb('emu','avd','name')['stdout'].splitlines()[0]=='Apporo_Odoo_UI_API36'
            assert self.shell('getprop','ro.kernel.qemu')['stdout']=='1'
            assert self.adb('devices')['stdout'].splitlines()[1:]==['emulator-5580\tdevice']
            self.shell('cmd','connectivity','airplane-mode','enable')
            self.shell('svc','wifi','disable');self.shell('svc','data','disable')
            self.shell('settings','put','global','http_proxy',':0')
            assert 'Active default network: none' in self.shell('dumpsys','connectivity')['stdout']
            self.adb('root')
            while self.adb('get-state',required=False).get('stdout')!='device':
                if self.stop.wait(.5):raise RuntimeError('Stop while waiting for rooted adbd')
            assert self.shell('id')['stdout'].startswith('uid=0')
            uids={}
            for package in PACKAGES:
                uid=exact_uid(package,self.shell('cmd','package','list','packages','-U',package)['stdout']);uids[package]=int(uid)
                for table in ('iptables','ip6tables'):
                    rule=[table,'-C','OUTPUT','-m','owner','--uid-owner',uid,'-j','REJECT']
                    result=self.shell(*rule,required=False)
                    if result.get('exit')==1:self.shell(table,'-I','OUTPUT','1','-m','owner','--uid-owner',uid,'-j','REJECT')
                    elif result.get('exit')!=0:raise RuntimeError('Firewall query failed')
                    self.shell(*rule)
            self.receipt['uids']=uids
            assert 'Active default network: none' in self.shell('dumpsys','connectivity')['stdout']
            self.receipt['guestGuardsVerified']=True
            target=EVIDENCE/'previous-failed-dump.xml'
            self.adb('pull','/sdcard/apporo-localization.xml',str(target),maximum=4)
            self.receipt.update(oldXmlRecovered=True,oldXmlSHA256=hashlib.sha256(target.read_bytes()).hexdigest(),status='boot-and-recovery-success')
            if ui_action is not None:
                self.work_deadline=time.monotonic()+720
                self.total_deadline=self.work_deadline+40
                self.receipt.update(uiStartedAt=utc(),uiWorkLimitSeconds=720,uiCleanupReserveSeconds=40)
                ui_action(self)
                self.receipt['status']='offline-ui-complete'
        except Exception as exc:
            self.receipt.update(status='failed-or-blocked',failure=repr(exc))
        finally:
            self.cleanup()
        return self.receipt


def main():
    os.umask(0o077)
    EVIDENCE.mkdir(mode=0o700,exist_ok=False)  # one attempt, no overwrites/retry
    with (EVIDENCE/'attempt.lock').open('x') as lock:lock.write(utc())
    probe=Probe()
    def interrupted(number, frame):
        probe.stop.set()
        probe.receipt.setdefault('hostSignals',[]).append(dict(signal=number,at=utc()))
        if not probe.cleaning:
            raise RuntimeError('Diagnostic interrupted by host signal '+str(number))
    signal.signal(signal.SIGTERM,interrupted)
    signal.signal(signal.SIGINT,interrupted)
    from ui_driver import exercise
    result=probe.execute(exercise)
    # Never print raw kernel/ADB output or environment secrets.
    print(json.dumps({k:result.get(k) for k in ('status','failure','oldXmlRecovered','cleanupResolved','totalElapsedSeconds')}))
    return 0 if result['status']=='offline-ui-complete' and result['cleanupResolved'] else 1


if __name__=='__main__':
    raise SystemExit(main())
