"""Ordinary APK, offline only; never populate a password or submit an auth request."""
import base64
import datetime
import re
import time
import xml.etree.ElementTree as ET
from device_common import *

PACKAGE = 'com.apporo.odoo.debug'
IME = 'com.github.uiautomator/.AdbKeyboard'
BASE_EVIDENCE = EVIDENCE
EVIDENCE = EVIDENCE / 'retry'
EVIDENCE.mkdir(exist_ok=True)
RESULTS = []
ACTIONS = []

def record(action):
    ACTIONS.append(dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(), action=action))
    (EVIDENCE / 'ui-actions.json').write_text(json.dumps(ACTIONS, ensure_ascii=False, indent=2))

def dump():
    shell('uiautomator','dump','/sdcard/apporo-localization.xml',timeout=60)
    return shell('cat','/sdcard/apporo-localization.xml')

def wait_text(text, tries=12):
    for _ in range(tries):
        xml = dump()
        if any(n.get('text') == text for n in ET.fromstring(xml).iter('node')):
            return xml
        time.sleep(1)
    (EVIDENCE/'failed-wait.xml').write_text(xml)
    raise AssertionError('Expected locale-specific text missing: '+text)

def capture(name, text):
    xml = wait_text(text)
    assert PACKAGE in shell('dumpsys','activity','activities')
    assert 'io.woowtech.odoo.ui.MainActivity' in shell('dumpsys','window','windows')
    (EVIDENCE/(name+'.xml')).write_text(xml)
    image = subprocess.check_output(ADB+['exec-out','screencap','-p'],env=ENV,timeout=20)
    assert image.startswith(b'\x89PNG')
    (EVIDENCE/(name+'.png')).write_bytes(image)
    RESULTS.append(dict(id=name,expected=text,result='PASS',xml=name+'.xml',screenshot=name+'.png'))
    (EVIDENCE/'ui-results.json').write_text(json.dumps(RESULTS,ensure_ascii=False,indent=2))
    record('captured '+name+' after visible text: '+text)

def tap_node(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))

def tap_text(text):
    xml=wait_text(text)
    node=next(n for n in ET.fromstring(xml).iter('node') if n.get('text')==text)
    record('tap observed text '+text+' '+node.get('bounds'))
    tap_node(node)

def enter(index,text):
    assert shell('settings','get','secure','default_input_method')==IME
    xml=dump()
    edits=[n for n in ET.fromstring(xml).iter('node') if n.get('class')=='android.widget.EditText']
    assert len(edits)==2
    record('ADBKeyboard set local fixture in observed EditText '+str(index)+' '+edits[index].get('bounds'))
    tap_node(edits[index]);time.sleep(.5)
    payload=base64.b64encode(text.encode()).decode()
    output=shell('am','broadcast','-a','ADB_KEYBOARD_SET_TEXT','-p','com.github.uiautomator','--es','text',payload)
    record(output)
    time.sleep(.3)
    shell('am','broadcast','-a','ADB_KEYBOARD_HIDE','-p','com.github.uiautomator')
    wait_text(text)

locales = {
 'en-US': ['Server URL','Next','Login','Server URL is required','Database name is required',
           'Username is required','Password is required','Secure connection required (HTTPS)','Invalid server URL'],
 'zh-TW': ['伺服器網址','下一步','登入','請輸入伺服器網址','請輸入資料庫名稱',
           '請輸入使用者名稱','請輸入密碼','需要安全連線 (HTTPS)','伺服器網址無效'],
 'zh-CN': ['服务器网址','下一步','登录','请输入服务器网址','请输入数据库名称',
           '请输入用户名','请输入密码','需要安全连接 (HTTPS)','服务器网址无效']
}
identity()
assert adb('devices').splitlines()[1:] == ['emulator-5580\tdevice']
assert 'Active default network: none' in shell('dumpsys','connectivity')
guest_ps=shell('ps','-A','-o','PID,ARGS')
assert 'uiautomator dump' not in guest_ps and 'com.android.commands.uiautomator' not in guest_ps
host_ps=subprocess.check_output(['ps','-axo','pid,command'],text=True)
assert not any('adb -P 5038' in line and 'uiautomator dump' in line for line in host_ps.splitlines())
(EVIDENCE/'preflight-processes.txt').write_text('No previous host/guest dump process remains.\n'+guest_ps)
for package,receipt_name in [('com.apporo.odoo.debug','install-guard.json'),('com.github.uiautomator','keyboard-receipt.json')]:
    uid=str(json.loads((BASE_EVIDENCE/receipt_name).read_text())['uid'])
    assert shell('cmd','package','list','packages','-U',package)=='package:'+package+' uid:'+uid
    for t in ('iptables','ip6tables'):
        shell(t,'-C','OUTPUT','-m','owner','--uid-owner',uid,'-j','REJECT')
record('Preflight passed: old dump ended; dedicated identity, no default network, both UIDs double-stack blocked')

def launch(locale,mode):
    shell('am','force-stop',PACKAGE)
    shell('cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales',locale)
    shell('cmd','uimode','night','yes' if mode=='dark' else 'no')
    shell('am','start','-n',PACKAGE+'/io.woowtech.odoo.ui.MainActivity')
    record('cold launch '+locale+' '+mode)
    wait_text(locales[locale][0])

try:
    # Priority acceptance: both Chinese locales, then English; all empty URLs/light/dark.
    for locale in ('zh-TW','zh-CN','en-US'):
        for mode in ('light','dark'):
            launch(locale,mode)
            capture(locale+'-'+mode+'-login',locales[locale][0])
            tap_text(locales[locale][1])
            capture(locale+'-'+mode+'-url-required',locales[locale][3])
    # Then remaining form errors, with only local fixtures and always a blank password.
    for locale,words in locales.items():
        label,next_button,login_button,url_req,db_req,user_req,pass_req,https,invalid=words
        launch(locale,'light')
        enter(0,'  https://https://fixture.invalid  ')
        tap_text(next_button)
        capture(locale+'-database-required',db_req)
        enter(1,' local-db ')
        enter(0,' http://fixture.invalid ')
        tap_text(next_button)
        capture(locale+'-https-required',https)
        enter(0,'bad host')
        tap_text(next_button)
        capture(locale+'-invalid-url',invalid)
        enter(0,'  https://https://fixture.invalid  ')
        tap_text(next_button)
        wait_text(login_button)
        tap_text(login_button)
        capture(locale+'-username-required',user_req)
        enter(0,' local-user ')
        tap_text(login_button)
        capture(locale+'-password-required',pass_req)
        xml=dump()
        backs=[n for n in ET.fromstring(xml).iter('node') if n.get('content-desc') in ('Back','返回')]
        assert len(backs)==1
        record('tap observed form Back '+backs[0].get('bounds'));tap_node(backs[0])
        capture(locale+'-canonical-url','fixture.invalid')
        wait_text('local-db')
    record('Completed; no password entered, no auth request, no Odoo/FCM business operation')
    print(json.dumps(dict(checks=len(RESULTS),passed=len(RESULTS),failures=0),indent=2))
finally:
    subprocess.run(['python3','-B',str(ROOT/'docs/verification-report/apporo-login-localization/stop-device.py')],check=True)
