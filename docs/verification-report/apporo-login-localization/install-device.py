import datetime
import hashlib
import json
import re
import time
from device_common import *

identity()
assert 'Active default network: none' in shell('dumpsys','connectivity')
assert shell('settings','get','global','airplane_mode_on') == '1'
print(adb('root'))
time.sleep(3)
identity()
assert shell('id').startswith('uid=0')
package = 'com.apporo.odoo.debug'
shell('am','force-stop',package)
apk = ROOT / 'app/build/outputs/apk/apporo/debug/app-apporo-debug.apk'
verified = json.loads((ROOT / 'docs/verification-report/apporo-login-localization/apk-verification.json').read_text())
assert hashlib.sha256(apk.read_bytes()).hexdigest() == verified['sha256']
assert verified['realFirebaseApiKeyMatches'] and verified['syntheticMarkerAbsent']
import sys
if '--guard-only' not in sys.argv:
    print(adb('install','-r',str(apk),timeout=90))
package_uid = shell('cmd','package','list','packages','-U',package)
uid = re.fullmatch(r'package:' + re.escape(package) + r' uid:(\d+)', package_uid).group(1)
assert int(uid) >= 10000
rules = {}
for table in ('iptables','ip6tables'):
    shell(table,'-I','OUTPUT','1','-m','owner','--uid-owner',uid,'-j','REJECT')
    shell(table,'-C','OUTPUT','-m','owner','--uid-owner',uid,'-j','REJECT')
    rules[table] = shell(table,'-S','OUTPUT')
assert 'Active default network: none' in shell('dumpsys','connectivity')
receipt = dict(timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(), uid=int(uid),
               package=package, serial='emulator-5580',adbServerPort=5038, rules=rules,
               noDefaultNetworkBeforeAndAfterInstall=True, airplaneMode=True,
               appLaunched=False, apk=verified)
(EVIDENCE / 'install-guard.json').write_text(json.dumps(receipt,indent=2))
print('Installed verified real APK; actual UID',uid,'IPv4/IPv6 REJECT verified BEFORE launch')
