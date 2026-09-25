"""A single scoped root request is intent, never evidence of shell privilege."""
import time

SERIAL='emulator-5580'
AVD='Apporo_Odoo_UI_API36'


def ensure_root(adb,boot_deadline,clock=time.monotonic,pause=time.sleep):
    started=clock();deadline=min(started+20,boot_deadline)
    requested=False;root_result=None

    def query(*args,maximum=2):
        remaining=deadline-clock()
        if remaining<=0:raise TimeoutError('Root transition deadline exhausted')
        result=adb(*args,required=False,maximum=min(maximum,remaining))
        if clock()>=deadline:raise TimeoutError('Root observation arrived after deadline')
        return result

    def correct_identity_ready():
        devices=query('devices')
        if devices.get('exit')!=0:return False
        lines=devices.get('stdout','').splitlines()
        if not lines or lines[0]!='List of devices attached':
            raise RuntimeError('Cannot establish scoped transport inventory')
        rows=[line.split() for line in lines[1:] if line.strip()]
        if len(rows)>1 or any(len(row)!=2 or row[0]!=SERIAL for row in rows):
            raise RuntimeError('Unexpected or multiple transport identity; stop')
        if not rows or rows[0][1]!='device':return False
        state=query('get-state')
        if state.get('exit')!=0 or state.get('stdout')!='device':return False
        name=query('emu','avd','name')
        if name.get('exit')!=0:return False
        if name.get('stdout','').splitlines()[:1]!=[AVD]:
            raise RuntimeError('AVD identity mismatch; stop')
        emulator=query('shell','getprop','ro.kernel.qemu')
        if emulator.get('exit')!=0:return False
        if emulator.get('stdout')!='1':raise RuntimeError('Not the authorized emulator; stop')
        return True

    while clock()<deadline:
        if correct_identity_ready():
            identity=query('shell','id','-u')
            if identity.get('exit')==0 and identity.get('stdout')=='0':
                return dict(rootRequested=requested,rootCommandResult=root_result,
                            serial=SERIAL,avd=AVD,shellUid='0',
                            elapsedSeconds=clock()-started,budgetSeconds=deadline-started)
            if not requested:
                requested=True
                root_result=query('root',maximum=4)
                # Both exit0 and connection-closed require subsequent identity + id readback.
        remaining=deadline-clock()
        if remaining>0:pause(min(.5,remaining))
    raise TimeoutError('Unique emulator did not demonstrate shell uid0 within root deadline')
