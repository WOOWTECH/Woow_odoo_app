"""Wait for the authorized guest's phone binder; never skip data-disable."""
import time
from root_transition import SERIAL, AVD


def disable_data_when_phone_ready(adb, boot_deadline, clock=time.monotonic, pause=time.sleep):
    started = clock()
    deadline = min(started + 60, boot_deadline)
    observations = 0

    def query(*args):
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError('Phone readiness deadline exhausted')
        result = adb(*args, required=False, maximum=min(2, remaining))
        if clock() >= deadline:
            raise TimeoutError('Phone observation arrived after deadline')
        if result.get('exit') != 0:
            raise RuntimeError('Phone readiness command failed: ' + repr(args))
        return result.get('stdout', '')

    while clock() < deadline:
        inventory = query('devices').splitlines()
        if inventory != ['List of devices attached', SERIAL + '\tdevice']:
            raise RuntimeError('Phone readiness transport identity mismatch')
        if query('emu', 'avd', 'name').splitlines() != [AVD, 'OK']:
            raise RuntimeError('Phone readiness AVD identity mismatch')
        if query('shell', 'getprop', 'ro.kernel.qemu') != '1':
            raise RuntimeError('Phone readiness requires authorized emulator')
        service = query('shell', 'service', 'check', 'phone')
        observations += 1
        if service == 'Service phone: found':
            query('shell', 'svc', 'data', 'disable')
            return dict(phoneService=service, disableExit=0, observations=observations,
                        elapsedSeconds=clock()-started, budgetSeconds=deadline-started)
        if service != 'Service phone: not found':
            raise RuntimeError('Unexpected phone service readback')
        remaining = deadline - clock()
        if remaining > 0:
            pause(min(.5, remaining))
    raise TimeoutError('Phone service absent within readiness deadline')
