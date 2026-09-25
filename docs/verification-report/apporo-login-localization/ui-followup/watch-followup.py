import shutil
import signal
import time
from followup_common import *

runtime=json.loads((EVIDENCE/'runtime.json').read_text())
pid=runtime['pid'];minimum=shutil.disk_usage(ROOT).free
while True:
    try:os.kill(pid,0)
    except ProcessLookupError:break
    free=shutil.disk_usage(ROOT).free;minimum=min(minimum,free)
    (EVIDENCE/'disk.json').write_text(json.dumps(dict(startFreeGiB=runtime['startFreeGiB'],minimumFreeGiB=minimum/1024**3,latestFreeGiB=free/1024**3)))
    if free<2*1024**3:
        (EVIDENCE/'DISK-STOP.txt').write_text('Below 2GiB; stopping owned UI driver before owned emulator/adb shutdown')
        driver=EVIDENCE/'active-ui-pid.txt'
        if driver.exists():
            ui_pid=int(driver.read_text())
            process=subprocess.run(['ps','-p',str(ui_pid),'-o','command='],capture_output=True,text=True)
            if 'ui-followup/verify-followup.py' in process.stdout:os.kill(ui_pid,signal.SIGTERM)
        subprocess.run([sys.executable,'-B',str(Path(__file__).with_name('stop-followup.py'))],timeout=60,check=False)
        raise SystemExit(2)
    time.sleep(2)
