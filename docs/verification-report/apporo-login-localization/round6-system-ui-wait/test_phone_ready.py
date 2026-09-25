import unittest
from phone_ready import disable_data_when_phone_ready


class FakeADB:
    def __init__(self, mode='late'):
        self.mode=mode;self.now=0.;self.calls=[];self.observations=0
    def clock(self):return self.now
    def pause(self, seconds):self.now+=seconds
    def __call__(self, *args, required=False, maximum=2):
        assert required is False
        self.calls.append((args,maximum));self.now+=min(.1,maximum)
        code=0
        if args==('devices',):
            out='List of devices attached\nemulator-5580\tdevice'
            if self.mode=='wrong-serial':out='List of devices attached\nemulator-5554\tdevice'
            if self.mode=='multiple':out+='\nemulator-5554\tdevice'
        elif args==('emu','avd','name'):
            out=('Other' if self.mode=='wrong-avd' else 'Apporo_Odoo_UI_API36')+'\nOK'
        elif args==('shell','getprop','ro.kernel.qemu'):
            out='0' if self.mode=='wrong-qemu' else '1'
        elif args==('shell','service','check','phone'):
            self.observations+=1
            found=self.mode!='never' and (self.mode!='late' or self.observations>=3)
            out='Service phone: '+('found' if found else 'not found')
            if self.mode=='late-result':self.now=61
            if self.mode=='malformed':out='Service phone2: found'
            if self.mode=='query-fail':code=1
        elif args==('shell','svc','data','disable'):
            out=''
            if self.mode=='disable-fail':code=1
        else:raise AssertionError(args)
        return {'exit':code,'stdout':out}
    def run(self, deadline=260):
        return disable_data_when_phone_ready(self,deadline,clock=self.clock,pause=self.pause)
    def disables(self):return sum(args==('shell','svc','data','disable') for args,_ in self.calls)


class PhoneReadyTests(unittest.TestCase):
    def test_late_service_disables_only_after_found(self):
        adb=FakeADB();result=adb.run()
        self.assertEqual(3,result['observations']);self.assertEqual(1,adb.disables())
        self.assertEqual(0,result['disableExit']);self.assertLess(adb.now,60)
    def test_never_service_stops_at_sixty_without_disable(self):
        adb=FakeADB('never')
        with self.assertRaises(TimeoutError):adb.run()
        self.assertLessEqual(adb.now,60);self.assertEqual(0,adb.disables())
    def test_wrong_or_multiple_identity_stops_without_disable(self):
        for mode in ('wrong-serial','multiple','wrong-avd','wrong-qemu'):
            with self.subTest(mode=mode):
                adb=FakeADB(mode)
                with self.assertRaises(RuntimeError):adb.run()
                self.assertEqual(0,adb.disables());self.assertLess(adb.now,1)
    def test_disable_failure_is_not_success_or_retried(self):
        adb=FakeADB('disable-fail')
        with self.assertRaises(RuntimeError):adb.run()
        self.assertEqual(1,adb.disables())
    def test_boot_remaining_budget_clamps_every_command(self):
        adb=FakeADB('never')
        with self.assertRaises(TimeoutError):adb.run(deadline=1)
        self.assertLessEqual(adb.now,1);self.assertEqual(0,adb.disables())
        self.assertTrue(all(limit<=1 for _,limit in adb.calls))
    def test_late_or_failed_or_malformed_observation_never_authorizes(self):
        for mode,error in [('late-result',TimeoutError),('query-fail',RuntimeError),('malformed',RuntimeError)]:
            with self.subTest(mode=mode):
                adb=FakeADB(mode)
                with self.assertRaises(error):adb.run()
                self.assertEqual(0,adb.disables())
    def test_no_budget_means_no_commands(self):
        adb=FakeADB()
        with self.assertRaises(TimeoutError):adb.run(deadline=0)
        self.assertEqual([],adb.calls)


if __name__=='__main__':unittest.main()
