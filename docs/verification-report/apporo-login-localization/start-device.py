import datetime
import json
import os
import shutil
import signal
import socket
import subprocess
import time
from device_common import *

for port in (5038, 5580, 5581):
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', port)) == 0:
            raise SystemExit(f'BLOCKED: dedicated port {port} already in use')
if shutil.disk_usage(ROOT).free < 3 * 1024**3:
    raise SystemExit('BLOCKED: start requires 3 GiB')
sandbox = ['/usr/bin/sandbox-exec', '-f', str(ROOT / 'docs/verification-report/apporo-phase3/review-sandbox.sb')]
subprocess.run(sandbox + [str(SDK / 'platform-tools/adb'), '-P', '5038', '--one-device',
                        'APPORO_EMULATOR_ONLY_NO_USB', 'start-server'], env=ENV, check=True)
command = sandbox + RUNTIME['command']
log = open(EVIDENCE / 'emulator.log', 'w')
job = subprocess.Popen(command, env=ENV, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
receipt = dict(startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), command=command,
               pid=job.pid, environment={k:ENV[k] for k in RUNTIME['environmentPaths']},
               adbServerPort=5038, serial='emulator-5580', usbFilter='APPORO_EMULATOR_ONLY_NO_USB',
               mdnsDisabled=True, automaticEmulatorScanDisabled=True, hostOutboundSandbox=True)
(EVIDENCE / 'runtime.json').write_text(json.dumps(receipt, indent=2))
try:
    for _ in range(120):
        if job.poll() is not None:
            raise RuntimeError(f'emulator exited {job.returncode}')
        if shutil.disk_usage(ROOT).free < 2 * 1024**3:
            raise RuntimeError('less than 2 GiB free')
        try:
            if shell('getprop', 'sys.boot_completed', timeout=4) == '1':
                break
        except subprocess.SubprocessError:
            pass
        time.sleep(2)
    else:
        raise RuntimeError('boot timeout')
    identity()
    shell('cmd', 'connectivity', 'airplane-mode', 'enable')
    shell('svc', 'wifi', 'disable')
    shell('svc', 'data', 'disable')
    shell('settings', 'put', 'global', 'http_proxy', ':0')
    connectivity = shell('dumpsys', 'connectivity')
    (EVIDENCE / 'connectivity-before-install.txt').write_text(connectivity)
    assert 'Active default network: none' in connectivity
    assert shell('settings', 'get', 'global', 'airplane_mode_on') == '1'
    print('BOOTED; identity and no-default-network verified', flush=True)
    print('IMEs: ' + shell('ime', 'list', '-s'), flush=True)
except Exception:
    os.killpg(job.pid, signal.SIGTERM)
    job.wait(timeout=30)
    subprocess.run(ADB[:3] + ['kill-server'], env=ENV, check=False)
    raise
