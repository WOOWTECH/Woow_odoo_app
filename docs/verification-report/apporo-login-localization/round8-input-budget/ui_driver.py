import base64
import hashlib
import json
from pathlib import Path
import re
import struct
import time
import xml.etree.ElementTree as ET
import launcher
from readiness import bounds,field,text_node,form_ready,safe_missing_credentials,await_ready
from system_ui_wait import modal_present,wait_center
from connectivity import verify_no_default_network

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
        self.wait_used=False;self.failed=False;self.failure_evidence_used=False
        self.p.receipt['systemUiWaitCount']=0

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

    def image(self,name,diagnostic=False,deadline=None):
        guest='/sdcard/apporo-round2-'+name+'.png';target=self.directory/(name+'.png')
        if diagnostic:
            capture=self.forensic(['shell','screencap','-p',guest],maximum=8)
            pulled=self.forensic(['pull',guest,str(target)],maximum=15)
            (self.directory/(name+'-capture.json')).write_text(json.dumps(dict(capture=capture,pull=pulled),indent=2))
        else:
            self.p.shell('screencap','-p',guest,maximum=8 if deadline is None else launcher.remaining_timeout(deadline,8))
            self.p.adb('pull',guest,str(target),maximum=15 if deadline is None else launcher.remaining_timeout(deadline,15))
            assert target.read_bytes().startswith(b'\x89PNG')
        return target

    def readiness_snapshot(self,stage,deadline):
        snapshot=self.dump(stage,deadline)
        if not modal_present(snapshot[0]):return snapshot
        event=dict(action='System UI modal inspection',initialXml=snapshot[1].name,
                   readinessDeadline=deadline,status='inspecting',waitAlreadyUsed=self.wait_used)
        self.p.receipt.setdefault('systemUiModalEvents',[]).append(event)
        try:
            before=self.image(snapshot[1].stem+'-modal-before',deadline=deadline)
            event['beforeScreenshot']=before.name
            header=before.read_bytes()[:24]
            if len(header)!=24 or header[:8]!=b'\x89PNG\r\n\x1a\n' or header[12:16]!=b'IHDR':
                raise ValueError('Invalid fresh PNG dimensions')
            screen=struct.unpack('>II',header[16:24])
            initial=stage=='en-US-ready-login' and not self.results
            # Validate the first observation, then use only the fresh confirmation's bounds.
            wait_center(snapshot[0],screen,initial,self.wait_used)
            fresh=self.dump(stage+'-modal-confirm',deadline)
            event['beforeXml']=fresh[1].name
            center=wait_center(fresh[0],screen,initial,self.wait_used)
            if center is None:raise ValueError('Modal changed before Wait; no action')
            launcher.remaining_timeout(deadline,4)
            self.wait_used=True
            self.p.receipt['systemUiWaitCount']=1
            event.update(status='Wait-requested',freshCenter=list(center))
            self.p.record(dict(event));self.p.save()
            self.p.shell('input','tap',str(center[0]),str(center[1]),maximum=launcher.remaining_timeout(deadline,4))
            event['status']='Wait-tapped'
            after=self.dump(stage+'-modal-after',deadline)
            event['afterXml']=after[1].name
            event['afterScreenshot']=self.image(after[1].stem,deadline=deadline).name
            launcher.remaining_timeout(deadline,1)
            # Reappearance/any other modal stops immediately, never a second Wait.
            wait_center(after[0],screen,False,self.wait_used)
            event['status']='modal-cleared-awaiting-Apporo-readiness'
            return after
        except Exception as exc:
            event.update(status='stopped',error=repr(exc))
            raise
        finally:
            self.p.record(dict(event));self.p.save()

    def failure(self, stage, deadline, error):
        # Latch before any observation. Only cleanup and this one read-only capture remain.
        self.failed=True;self.p.stop.set()
        if self.failure_evidence_used:return
        self.failure_evidence_used=True
        event=dict(stage=stage,error=repr(error),status='observation-only',commands=[])
        self.p.receipt['failureObservation']=event
        deadline=min(deadline,self.p.work_deadline)
        def observe(args,maximum):
            limit=launcher.remaining_timeout(deadline,maximum)
            result=self.forensic(args,maximum=limit)
            event['commands'].append(result)
            if result.get('exit')!=0 or result.get('timeout') is not False:
                raise RuntimeError('Failure observation unavailable; no retry')
        try:
            if self.p.work_deadline-time.monotonic()<=0 or deadline-time.monotonic()<=0:
                event['status']='skipped-no-work-budget';return
            self.sequence+=1
            name=f'{self.sequence:03d}-{stage}-FAILED'
            guest='/sdcard/apporo-round8-'+name
            observe(['shell','uiautomator','dump',guest+'.xml'],60)
            self.p.receipt['newDump']=True
            observe(['pull',guest+'.xml',str(self.directory/(name+'.xml'))],15)
            observe(['shell','screencap','-p',guest+'.png'],8)
            observe(['pull',guest+'.png',str(self.directory/(name+'.png'))],15)
            event.update(xml=name+'.xml',screenshot=name+'.png')
        except Exception as exc:
            event.update(status='observation-failed',observationError=repr(exc))
        finally:
            self.p.save()

    def ready(self,stage,credential=False,extra=lambda root:True,deadline=None):
        if self.failed:raise RuntimeError('UI stopped; no further action')
        labels=[self.w['username'],self.w['password']] if credential else [self.w['server_url'],self.w['database_name']]
        button=self.w['login_button'] if credential else self.w['next_button']
        deadline=min(time.monotonic()+120,self.p.work_deadline) if deadline is None else min(deadline,self.p.work_deadline)
        try:
            return await_ready(lambda end:self.readiness_snapshot(stage,end),lambda root:form_ready(root,labels,button) and extra(root),deadline)
        except Exception as exc:
            self.failure(stage,deadline,exc)
            raise

    def checkpoint(self,name,expected,credential=False):
        root,xml=self.ready(name,credential,lambda r:text_node(r,expected) is not None)
        image=self.image(name)
        self.results.append(dict(id=name,result='PASS',expected=expected,actual=text_node(root,expected).get('text'),xml=xml.name,screenshot=image.name))
        self.persist()

    def tap(self,node,deadline=None):
        if self.failed:raise RuntimeError('UI stopped; no further mutation')
        x1,y1,x2,y2=bounds(node)
        self.p.shell('input','tap',str((x1+x2)//2),str((y1+y2)//2),maximum=4 if deadline is None else launcher.remaining_timeout(deadline,4))

    def next(self):
        root,_=self.ready(self.locale+'-before-next')
        self.tap(text_node(root,self.w['next_button']))

    def missing_login(self):
        root,xml=self.ready(self.locale+'-before-missing-login',True)
        assert safe_missing_credentials(root,self.w['username'],self.w['password']), 'Never submit complete credentials'
        self.p.record(dict(action='local missing-field login tap',xml=xml.name,usernameBlank=not field(root,self.w['username']).get('text','').strip(),passwordBlank=not field(root,self.w['password']).get('text','').strip()))
        self.tap(text_node(root,self.w['login_button']))

    def enter(self,key,value,credential=False):
        if self.failed:raise RuntimeError('UI stopped; no further input')
        assert key in ('server_url','database_name','username'), 'No password input permitted'
        deadline=min(time.monotonic()+120,self.p.work_deadline)
        name=f'{self.locale}-input-{key}-{len(self.inputs)+1}'
        attempt=dict(id=name,expected=value,status='not-sent',appliedToGuest='unknown')
        self.p.receipt.setdefault('inputAttempts',[]).append(attempt)
        try:
            assert self.p.shell('settings','get','secure','default_input_method',maximum=launcher.remaining_timeout(deadline,4))['stdout']==IME
            label=self.w[key]
            root,before=self.ready(self.locale+'-before-input-'+key,credential,deadline=deadline)
            launcher.remaining_timeout(deadline,4)
            self.tap(field(root,label),deadline=deadline)
            self.ready(self.locale+'-focused-'+key,credential,lambda r:field(r,label).get('focused')=='true',deadline=deadline)
            payload=base64.b64encode(value.encode()).decode()
            limit=launcher.remaining_timeout(deadline,15)
            attempt['status']='requested-once'
            result=self.p.shell('am','broadcast','-a','ADB_KEYBOARD_SET_TEXT','-p',HELPER,'--es','text',payload,required=False,maximum=limit)
            attempt['command']=result
            if result.get('exit')!=0 or result.get('timeout') is not False:
                attempt['status']='unknown-command-outcome'
                raise RuntimeError('SET_TEXT incomplete; may already be applied; never resend')
            launcher.remaining_timeout(deadline,4)
            attempt['status']='command-completed-readback-pending'
            self.p.shell('am','broadcast','-a','ADB_KEYBOARD_HIDE','-p',HELPER,maximum=launcher.remaining_timeout(deadline,4))
            root,after=self.ready(name,credential,lambda r:field(r,label).get('text')==value,deadline=deadline)
            actual=field(root,label).get('text')
            if actual!=value:raise ValueError('Exact raw input readback mismatch')
            image=self.image(name,deadline=deadline)
            launcher.remaining_timeout(deadline,1)
            self.inputs.append(dict(id=name,fieldLabel=label,expected=value,actual=actual,beforeXml=before.name,xml=after.name,screenshot=image.name,result='PASS',method='verified ADBKeyboard base64'))
            attempt.update(status='verified-raw-readback',appliedToGuest='verified')
            self.persist()
        except Exception as exc:
            attempt['failure']=repr(exc)
            self.failure(name,deadline,exc)
            raise

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
    from installed_artifacts import verify_artifacts
    verified=verify_artifacts(probe)
    package_info=probe.shell('dumpsys','package',PACKAGE,maximum=8)['stdout']
    assert re.search(r'\bversionCode=1\b',package_info) and 'versionName=1.0-debug' in package_info
    helper_info=probe.shell('dumpsys','package',HELPER,maximum=8)['stdout']
    assert re.search(r'\bversionCode=2004001\b',helper_info) and 'versionName=2.4.0' in helper_info
    probe.receipt['installedHelperVersion']='2.4.0/2004001'
    probe.receipt['installedArtifactSHA256']=verified
    probe.receipt['installedVersion']='1.0-debug/1'
    verify_no_default_network(probe)
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
