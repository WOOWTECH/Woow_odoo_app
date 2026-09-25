import json
import os
import shutil
import signal
import time
from device_common import *
pid=json.loads((EVIDENCE/'runtime.json').read_text())['pid']
minimum=shutil.disk_usage(ROOT).free
while True:
    try:
        os.kill(pid,0)
    except ProcessLookupError:
        break
    free=shutil.disk_usage(ROOT).free
    minimum=min(minimum,free)
    (EVIDENCE/'runtime-disk.json').write_text(json.dumps(dict(minimumFreeGiB=minimum/1024**3,latestFreeGiB=free/1024**3)))
    if free < 2 * 1024**3:
        (EVIDENCE/'DISK-STOP.txt').write_text('Below 2GiB: stopping owned emulator and adb5038 only')
        # Stop our UI driver first: it must not auto-start adb after server shutdown.
        ui_pid_file=EVIDENCE/'active-ui-pid.txt'
        if ui_pid_file.exists():
            ui_pid=int(ui_pid_file.read_text())
            command=subprocess.check_output(['ps','-p',str(ui_pid),'-o','command='],text=True)
            if 'apporo-login-localization/ui-retry.py' in command:
                os.kill(ui_pid,signal.SIGTERM)
        subprocess.run(['python3','-B',str(ROOT/'docs/verification-report/apporo-login-localization/stop-device.py')],timeout=60,check=False)
        raise SystemExit(2)
    time.sleep(2)
