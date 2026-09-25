import datetime
import time
from followup_common import *

receipt=dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),serial='emulator-5580',adbServerPort=5038)
try:
    identity()
    shell('am','force-stop',PACKAGE)
    old=json.loads((EVIDENCE/'guard.json').read_text())['previousIME']
    shell('ime','set',old);shell('ime','disable',IME);shell('am','force-stop',HELPER)
    receipt.update(restoredIME=shell('settings','get','secure','default_input_method'),helperDisabled=IME not in shell('ime','list','-s'),noDefaultNetwork='Active default network: none' in shell('dumpsys','connectivity'),rules={t:shell(t,'-S','OUTPUT') for t in ('iptables','ip6tables')})
    shell('cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales','en-US')
    shell('cmd','uimode','night','no')
    receipt['shutdown']=adb('emu','kill')
finally:
    time.sleep(3)
    subprocess.run([str(SDK/'platform-tools/adb'),'-P','5038','kill-server'],env=ENV,check=False)
    for _ in range(30):
        ports={}
        for port in (5038,5580,5581):
            with socket.socket() as probe:ports[str(port)]=probe.connect_ex(('127.0.0.1',port))!=0
        if all(ports.values()):break
        time.sleep(1)
    receipt['portsClosed']=ports
    (EVIDENCE/'shutdown.json').write_text(json.dumps(receipt,indent=2))
    print(json.dumps(receipt,indent=2))
