import unittest
from root_transition import ensure_root


class FakeADB:
    def __init__(self,mode):
        self.mode=mode;self.now=0.;self.calls=[];self.root_sent=False;self.after_root_inventories=0

    def clock(self):return self.now
    def pause(self,seconds):self.now+=seconds

    def __call__(self,*args,required=False,maximum=2):
        assert required is False
        self.calls.append((args,maximum));self.now+=min(.1,maximum)
        code=0;out='';err=''
        if args==('devices',):
            if self.root_sent:self.after_root_inventories+=1
            row='emulator-5580\tdevice'
            if self.mode=='multiple':row+='\nemulator-5554\tdevice'
            if self.mode=='wrong-serial':row='emulator-5554\tdevice'
            if self.root_sent and (self.mode=='never-ready' or self.mode=='closed' and self.after_root_inventories==1):row='emulator-5580\toffline'
            out='List of devices attached\n'+row
        elif args==('get-state',):out='device'
        elif args==('emu','avd','name'):out=('Other_AVD' if self.mode=='wrong-avd' else 'Apporo_Odoo_UI_API36')+'\nOK'
        elif args==('shell','getprop','ro.kernel.qemu'):out='0' if self.mode=='wrong-qemu' else '1'
        elif args==('shell','id','-u'):
            out='0' if self.mode in ('already-root','late','failed-id') or self.root_sent and self.mode=='closed' else '2000'
            if self.mode=='late':self.now=21
            if self.mode=='failed-id':code=1
            if self.mode=='malformed-id':out='uid=0(root)'
        elif args==('root',):
            self.root_sent=True
            code=1 if self.mode=='closed' else 0
            err='adb: unable to connect for root: closed' if code else ''
        else:raise AssertionError('Unexpected command '+repr(args))
        return {'exit':code,'stdout':out,'stderr':err,'timeout':False}

    def run(self,deadline=100):return ensure_root(self,deadline,clock=self.clock,pause=self.pause)
    def roots(self):return sum(args==('root',) for args,_ in self.calls)


class RootTransitionTests(unittest.TestCase):
    def test_already_root_uses_actual_identity_and_uid_without_restart(self):
        adb=FakeADB('already-root');result=adb.run()
        self.assertFalse(result['rootRequested']);self.assertEqual(0,adb.roots())
        self.assertEqual('0',result['shellUid'])

    def test_closed_then_offline_then_matching_identity_and_uid0_succeeds_once(self):
        adb=FakeADB('closed');result=adb.run()
        self.assertEqual(1,adb.roots());self.assertEqual('0',result['shellUid'])
        self.assertEqual(1,result['rootCommandResult']['exit'])
        self.assertIn('closed',result['rootCommandResult']['stderr'])
        self.assertGreaterEqual(adb.after_root_inventories,2)

    def test_exit0_but_nonroot_is_never_authorized_or_repeated(self):
        adb=FakeADB('exit0-nonroot')
        with self.assertRaises(TimeoutError):adb.run()
        self.assertEqual(1,adb.roots());self.assertLessEqual(adb.now,20)

    def test_never_ready_stops_without_server_restart_or_second_root(self):
        adb=FakeADB('never-ready')
        with self.assertRaises(TimeoutError):adb.run()
        self.assertEqual(1,adb.roots())
        self.assertFalse(any('wait-for-device' in args or 'start-server' in args or 'reconnect' in args for args,_ in adb.calls))

    def test_late_uid0_does_not_authorize(self):
        with self.assertRaises(TimeoutError):FakeADB('late').run()

    def test_wrong_or_multiple_identity_immediately_stops(self):
        for mode in ('multiple','wrong-serial','wrong-avd','wrong-qemu'):
            with self.subTest(mode=mode):
                adb=FakeADB(mode)
                with self.assertRaises(RuntimeError):adb.run()
                self.assertEqual(0,adb.roots());self.assertLess(adb.now,2)

    def test_uid_requires_exact_zero_and_successful_command(self):
        for mode in ('failed-id','malformed-id'):
            with self.subTest(mode=mode):
                adb=FakeADB(mode)
                with self.assertRaises(TimeoutError):adb.run()
                self.assertEqual(1,adb.roots())

    def test_clamps_to_remaining_boot_deadline_and_command_budget(self):
        adb=FakeADB('exit0-nonroot')
        with self.assertRaises(TimeoutError):adb.run(deadline=3)
        self.assertLessEqual(adb.now,3)
        self.assertTrue(all(maximum<=3 for _,maximum in adb.calls))
        self.assertTrue(any(maximum<2 for _,maximum in adb.calls))


if __name__=='__main__':unittest.main()
