import base64
import hashlib
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
import launcher
from readiness import bounds,field,text_node,form_ready,safe_missing_credentials,await_ready

PACKAGE='com.apporo.odoo.debug'
HELPER='com.github.uiautomator'
IME=HELPER+'/.AdbKeyboard'
RES=launcher.ROOT/'app/src/main/res'
KEYS=['server_url','database_name','username','password','next_button','login_button','back_button',
      'login_server_url_required','login_database_required','login_username_required','login_password_required',
      'error_https','error_invalid_url']


def words(locale):
    directory={'en-US':'values','zh-TW':'values-zh-rTW','zh-CN':'values-zh-rCN'}[locale]
    values={n.get('name'):''.join(n.itertext()) for n in ET.parse(RES/directory/'strings.xml').getroot()}
    return {k:values[k] for k in KEYS}


class UI:
    def __init__(self,probe):
        self.p=probe;self.directory=launcher.EVIDENCE
        self.sequence=0;self.results=[];self.inputs=[];self.locale=None;self.w=None

    def persist(self):
        (self.directory/'ui-results.json').write_text(json.dumps(self.results,ensure_ascii=False,indent=2))
        (self.directory/'input-results.json').write_text(json.dumps(self.inputs,ensure_ascii=False,indent=2))

    def forensic(self,args,maximum=15):
        if not launcher.port_open(5038):return {'exit':None,'stderr':'dedicated server absent'}
        remaining=self.p.work_deadline-time.monotonic()
        if remaining<=0:return {'exit':None,'stderr':'UI phase deadline exhausted; forensic transfer not started'}
        return self.p.run(self.p.adb_command+list(args),maximum=min(maximum,remaining),cleanup=True)

    def dump(self,stage,deadline):
        self.sequence+=1
        name=f'{self.sequence:03d}-{stage}'
        guest='/sdcard/apporo-round2-'+name+'.xml'
        target=self.directory/(name+'.xml')
        result=None
        try:
            # Reserve pull time inside the screen120s budget; each dump itself<=60s.
            limit=min(60,deadline-time.monotonic()-16)
            if limit<=0:raise TimeoutError('No dump/pull budget left for readiness')
            result=self.p.shell('uiautomator','dump',guest,maximum=limit)
            self.p.receipt['newDump']=True
        finally:
            pulled=self.forensic(['pull',guest,str(target)],maximum=min(15,max(.01,deadline-time.monotonic())))
            (self.directory/(name+'-dump.json')).write_text(json.dumps(dict(command=result,pull=pulled,xmlSaved=target.exists()),indent=2))
        if not target.exists():raise RuntimeError('Dump XML not available; evidence of pull failure saved')
        return ET.parse(target).getroot(),target

    def image(self,name,diagnostic=False):
        guest='/sdcard/apporo-round2-'+name+'.png';target=self.directory/(name+'.png')
        if diagnostic:
            capture=self.forensic(['shell','screencap','-p',guest],maximum=8)
            pulled=self.forensic(['pull',guest,str(target)],maximum=15)
            (self.directory/(name+'-capture.json')).write_text(json.dumps(dict(capture=capture,pull=pulled),indent=2))
        else:
            self.p.shell('screencap','-p',guest,maximum=8)
            self.p.adb('pull',guest,str(target),maximum=15)
            assert target.read_bytes().startswith(b'\x89PNG')
        return target

    def ready(self,stage,credential=False,extra=lambda root:True):
        labels=[self.w['username'],self.w['password']] if credential else [self.w['server_url'],self.w['database_name']]
        button=self.w['login_button'] if credential else self.w['next_button']
        deadline=min(time.monotonic()+120,self.p.work_deadline)
        try:
            return await_ready(lambda end:self.dump(stage,end),lambda root:form_ready(root,labels,button) and extra(root),deadline)
        except Exception:
            self.image(stage+'-FAILED',diagnostic=True)
            raise

    def checkpoint(self,name,expected,credential=False):
        root,xml=self.ready(name,credential,lambda r:text_node(r,expected) is not None)
        image=self.image(name)
        self.results.append(dict(id=name,result='PASS',expected=expected,actual=text_node(root,expected).get('text'),xml=xml.name,screenshot=image.name))
        self.persist()

    def tap(self,node):
        x1,y1,x2,y2=bounds(node)
        self.p.shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))

    def next(self):
        root,_=self.ready(self.locale+'-before-next')
        self.tap(text_node(root,self.w['next_button']))

    def missing_login(self):
        root,xml=self.ready(self.locale+'-before-missing-login',True)
        assert safe_missing_credentials(root,self.w['username'],self.w['password']), 'Never submit complete credentials'
        self.p.record(dict(action='local missing-field login tap',xml=xml.name,usernameBlank=not field(root,self.w['username']).get('text','').strip(),passwordBlank=not field(root,self.w['password']).get('text','').strip()))
        self.tap(text_node(root,self.w['login_button']))

    def enter(self,key,value,credential=False):
        assert key in ('server_url','database_name','username'), 'No password input permitted'
        assert self.p.shell('settings','get','secure','default_input_method')['stdout']==IME
        label=self.w[key]
        root,before=self.ready(self.locale+'-before-input-'+key,credential)
        self.tap(field(root,label))
        self.ready(self.locale+'-focused-'+key,credential,lambda r:field(r,label).get('focused')=='true')
        payload=base64.b64encode(value.encode()).decode()
        self.p.shell('am','broadcast','-a','ADB_KEYBOARD_SET_TEXT','-p',HELPER,'--es','text',payload)
        self.p.shell('am','broadcast','-a','ADB_KEYBOARD_HIDE','-p',HELPER)
        name=f'{self.locale}-input-{key}-{len(self.inputs)+1}'
        root,after=self.ready(name,credential,lambda r:field(r,label).get('text')==value)
        image=self.image(name)
        self.inputs.append(dict(id=name,fieldLabel=label,expected=value,actual=field(root,label).get('text'),beforeXml=before.name,xml=after.name,screenshot=image.name,result='PASS',method='verified ADBKeyboard base64'))
        self.persist()

    def run(self):
        for locale in ('en-US','zh-TW','zh-CN'):
            self.locale=locale;self.w=words(locale)
            # One intentional cold launch per locale, never a retry after failure.
            self.p.shell('am','force-stop',PACKAGE)
            self.p.shell('cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales',locale)
            self.p.shell('cmd','uimode','night','no')
            self.p.shell('am','start','-n',PACKAGE+'/io.woowtech.odoo.ui.MainActivity')
            self.p.receipt['appLaunched']=True
            self.checkpoint(locale+'-ready-login',self.w['server_url'])
            self.enter('server_url','  https://https://example.invalid  ')
            self.next();self.checkpoint(locale+'-database-required',self.w['login_database_required'])
            self.enter('database_name',' local-db ')
            self.enter('server_url',' http://example.invalid ')
            self.next();self.checkpoint(locale+'-https-required',self.w['error_https'])
            self.enter('server_url','bad host')
            self.next();self.checkpoint(locale+'-invalid-url',self.w['error_invalid_url'])
            self.enter('server_url','  https://https://example.invalid  ')
            self.next()
            self.missing_login();self.checkpoint(locale+'-username-required',self.w['login_username_required'],True)
            self.enter('username',' local-user ',True)
            self.missing_login();self.checkpoint(locale+'-password-required',self.w['login_password_required'],True)
            root,_=self.ready(locale+'-before-form-back',True,lambda r:text_node(r,self.w['back_button'],True) is not None)
            self.tap(text_node(root,self.w['back_button'],True))
            root,xml=self.ready(locale+'-canonical-return',extra=lambda r:field(r,self.w['server_url']).get('text')=='example.invalid' and field(r,self.w['database_name']).get('text')=='local-db')
            image=self.image(locale+'-canonical-return')
            self.results.append(dict(id=locale+'-canonical-return',result='PASS',expected={'server_url':'example.invalid','database':'local-db'},actual={'server_url':field(root,self.w['server_url']).get('text'),'database':field(root,self.w['database_name']).get('text')},xml=xml.name,screenshot=image.name));self.persist()
        self.p.receipt.update(uiCheckpointsPassed=len(self.results),localInputChecksPassed=len(self.inputs),passwordEverEntered=False,completeCredentialsSubmitted=False,blocked=['Settings requires authorized sign-in','server-response auth banners not safely reachable without submitting complete credentials'])


def exercise(probe):
    # Revalidate installed bytes, not merely the on-disk APK left by a unit build.
    reference=json.loads((launcher.ROOT/'docs/verification-report/apporo-login-localization/apk-verification.json').read_text())
    helper=json.loads(Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android/localization/keyboard-receipt.json').read_text())
    verified={}
    for package,expected in [(PACKAGE,reference['sha256']),(HELPER,helper['sha256'])]:
        listing=probe.shell('pm','path',package,maximum=8)['stdout'].splitlines()
        assert len(listing)==1 and listing[0].startswith('package:/data/app/')
        actual=probe.shell('sha256sum',listing[0].removeprefix('package:'),maximum=15)['stdout'].split()[0]
        assert actual==expected
        verified[package]=actual
    package_info=probe.shell('dumpsys','package',PACKAGE,maximum=8)['stdout']
    assert re.search(r'\bversionCode=1\b',package_info) and 'versionName=1.0-debug' in package_info
    helper_info=probe.shell('dumpsys','package',HELPER,maximum=8)['stdout']
    assert re.search(r'\bversionCode=2004001\b',helper_info) and 'versionName=2.4.0' in helper_info
    probe.receipt['installedHelperVersion']='2.4.0/2004001'
    probe.receipt['installedArtifactSHA256']=verified
    probe.receipt['installedVersion']='1.0-debug/1'
    assert 'Active default network: none' in probe.shell('dumpsys','connectivity')['stdout']
    previous=probe.shell('settings','get','secure','default_input_method')['stdout']
    assert previous not in ('','null',IME)
    probe.previous_ime=previous;probe.receipt['previousIME']=previous
    assert IME in probe.shell('ime','list','-a','-s')['stdout']
    probe.shell('ime','enable',IME);probe.shell('ime','set',IME)
    assert probe.shell('settings','get','secure','default_input_method')['stdout']==IME
    probe.receipt['imeChanged']=True;probe.save()
    ui=UI(probe)
    try:ui.run()
    finally:ui.persist()
