import datetime
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import time
from followup_common import *

assert not (EVIDENCE/'runtime.json').exists(), 'Do not overwrite an earlier attempt'
for port in (5038,5580,5581):
    with socket.socket() as probe:
        assert probe.connect_ex(('127.0.0.1',port)) != 0, f'Port {port} already owned'
free = shutil.disk_usage(ROOT).free
assert free >= 3*1024**3, 'Start requires 3 GiB'
sandbox = ['/usr/bin/sandbox-exec','-f',str(ROOT/'docs/verification-report/apporo-phase3/review-sandbox.sb')]
subprocess.run(sandbox+[str(SDK/'platform-tools/adb'),'-P','5038','--one-device','APPORO_EMULATOR_ONLY_NO_USB','start-server'],env=ENV,check=True)
command = sandbox + RUNTIME['command']
job = subprocess.Popen(command, env=ENV, stdout=open(EVIDENCE/'emulator.log','w'),stderr=subprocess.STDOUT,start_new_session=True)
(EVIDENCE/'runtime.json').write_text(json.dumps(dict(startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),pid=job.pid,command=command,environment={k:ENV[k] for k in RUNTIME['environmentPaths']},startFreeGiB=free/1024**3,adbServerPort=5038,serial='emulator-5580',usbFilter='APPORO_EMULATOR_ONLY_NO_USB',mdnsDisabled=True,automaticEmulatorScanDisabled=True),indent=2))
try:
    for _ in range(120):
        assert job.poll() is None, 'Emulator exited'
        assert shutil.disk_usage(ROOT).free >= 2*1024**3, 'Disk stop'
        try:
            if shell('getprop','sys.boot_completed',timeout=4)=='1':break
        except subprocess.SubprocessError:pass
        time.sleep(2)
    else:raise RuntimeError('Boot timeout')
    identity()
    shell('cmd','connectivity','airplane-mode','enable')
    shell('svc','wifi','disable');shell('svc','data','disable')
    shell('settings','put','global','http_proxy',':0')
    connectivity=shell('dumpsys','connectivity')
    (EVIDENCE/'connectivity-before-actions.txt').write_text(connectivity)
    assert 'Active default network: none' in connectivity
    assert shell('settings','get','global','airplane_mode_on')=='1'
    # FIRST XML operation: recover previous failed dump before any new dump can overwrite it.
    print(adb('pull','/sdcard/apporo-localization.xml',str(EVIDENCE/'previous-failed-dump.xml')))
    recovered=(EVIDENCE/'previous-failed-dump.xml').read_bytes()
    (EVIDENCE/'previous-failed-dump.sha256').write_text(hashlib.sha256(recovered).hexdigest()+'\n')
    print(adb('root'));time.sleep(3);identity()
    assert shell('id').startswith('uid=0')
    uids={}
    for package in (PACKAGE,HELPER):
        shell('am','force-stop',package)
        listing=shell('cmd','package','list','packages','-U',package)
        uid=re.fullmatch(r'package:'+re.escape(package)+r' uid:(\d+)',listing).group(1)
        assert int(uid)>=10000
        uids[package]=int(uid)
        for table in ('iptables','ip6tables'):
            shell(table,'-I','OUTPUT','1','-m','owner','--uid-owner',uid,'-j','REJECT')
            shell(table,'-C','OUTPUT','-m','owner','--uid-owner',uid,'-j','REJECT')
    old=shell('settings','get','secure','default_input_method')
    (EVIDENCE/'guard.json').write_text(json.dumps(dict(uids=uids,previousIME=old,appLaunched=False,noDefaultNetwork=True,rules={t:shell(t,'-S','OUTPUT') for t in ('iptables','ip6tables')}),indent=2))
    print('READY: old XML recovered, both UIDs guarded; no new dump, install, IME activation or App launch')
except Exception:
    os.killpg(job.pid,signal.SIGTERM);job.wait(timeout=30)
    subprocess.run([str(SDK/'platform-tools/adb'),'-P','5038','kill-server'],env=ENV,check=False)
    raise
