import datetime
import json
import socket
import time
from device_common import *

receipt=dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(),serial='emulator-5580',adbServerPort=5038)
try:
    identity()
    old=json.loads((EVIDENCE/'keyboard-receipt.json').read_text())['previousIME']
    shell('am','force-stop','com.apporo.odoo.debug')
    shell('ime','set',old)
    shell('ime','disable','com.github.uiautomator/.AdbKeyboard')
    shell('am','force-stop','com.github.uiautomator')
    receipt['restoredIME']=shell('settings','get','secure','default_input_method')
    receipt['helperDisabled']= 'com.github.uiautomator/.AdbKeyboard' not in shell('ime','list','-s')
    receipt['noDefaultNetwork']='Active default network: none' in shell('dumpsys','connectivity')
    receipt['firewallCounters']={t:shell(t,'-L','OUTPUT','-n','-v') for t in ('iptables','ip6tables')}
    shell('cmd','locale','set-app-locales','com.apporo.odoo.debug','--user','0','--locales','en-US')
    shell('cmd','uimode','night','no')
    receipt['shutdown']=adb('emu','kill')
finally:
    time.sleep(3)
    subprocess.run([str(SDK/'platform-tools/adb'),'-P','5038','kill-server'],env=ENV,check=False)
    for _ in range(30):
        ports={}
        for port in (5038,5580,5581):
            with socket.socket() as s:
                ports[str(port)]=s.connect_ex(('127.0.0.1',port)) != 0
        if all(ports.values()):break
        time.sleep(1)
    receipt['portsClosed']=ports
    (EVIDENCE/'shutdown-receipt.json').write_text(json.dumps(receipt,indent=2))
    print(json.dumps(receipt,indent=2))
