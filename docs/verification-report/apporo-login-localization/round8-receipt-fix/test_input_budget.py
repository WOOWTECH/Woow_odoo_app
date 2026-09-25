"""Offline only: class-specific input budget, sticky stop, and guest/host receipts."""
import base64
from contextlib import ExitStack
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import launcher
from ui_driver import UI, IME, words
from readiness import field

RAW='  https://https://example.invalid  '
FIXTURE=launcher.EVIDENCE.parent/'localization/en-US-light-login.xml'


class InputBudgetTests(unittest.TestCase):
    def setUp(self):
        self.stack=ExitStack();self.addCleanup(self.stack.close)
        self.directory=Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.clock=[100.0]
        self.stack.enter_context(patch('launcher.time.monotonic',side_effect=lambda:self.clock[0]))
        self.stack.enter_context(patch('launcher.port_open',return_value=True))
        self.p=Mock();self.p.stop=threading.Event();self.p.receipt={};self.p.work_deadline=820
        self.p.adb_command=['fake-adb'];self.ui=UI(self.p);self.ui.directory=self.directory
        self.ui.locale='en-US';self.ui.w=words('en-US')
        self.root=ET.parse(FIXTURE).getroot()
        field(self.root,self.ui.w['server_url']).set('text',RAW)
        field(self.root,self.ui.w['server_url']).set('focused','true')
        self.ui.ready=Mock(return_value=(self.root,Path('readback.xml')))
        self.ui.image=Mock(return_value=Path('readback.png'))
        self.commands=[];self.send_seconds=12;self.result=dict(exit=0,timeout=False,stdout='Broadcast completed',stderr='')
        def shell(*args,**kwargs):
            self.commands.append((args,kwargs))
            if args[:3]==('settings','get','secure'):return dict(stdout=IME)
            if 'ADB_KEYBOARD_SET_TEXT' in args:
                self.clock[0]+=self.send_seconds
                return self.result
            return dict(exit=0,timeout=False,stdout='')
        self.p.shell.side_effect=shell
        self.p.run.return_value=dict(exit=0,timeout=False,stdout='')

    def sends(self):return [x for x in self.commands if 'ADB_KEYBOARD_SET_TEXT' in x[0]]

    def test_12_second_success_with_exact_raw_readback_only(self):
        self.ui.enter('server_url',RAW)
        self.assertEqual(1,len(self.sends()));args,kw=self.sends()[0]
        self.assertEqual(15,kw['maximum']);self.assertFalse(kw['required'])
        self.assertEqual(RAW,base64.b64decode(args[-1]).decode())
        self.assertEqual(RAW,self.ui.inputs[0]['actual']);self.assertEqual('PASS',self.ui.inputs[0]['result'])
        self.assertEqual({220}, {c.kwargs['deadline'] for c in self.ui.ready.call_args_list})
        self.assertTrue(all(kw.get('maximum')==4 for args,kw in self.commands if 'ADB_KEYBOARD_SET_TEXT' not in args))
        self.p.run.assert_not_called()

    def test_partial_timeout_may_be_applied_never_resend_or_hide_or_pass(self):
        self.result=dict(exit=None,timeout=True,stdout='Broadcasting: Intent ...',stderr='command timeout')
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        self.assertTrue(self.p.stop.is_set());self.assertTrue(self.ui.failed)
        self.assertEqual('unknown',self.p.receipt['inputAttempts'][0]['appliedToGuest'])
        self.assertFalse(any('ADB_KEYBOARD_HIDE' in a for a,k in self.commands))
        self.assertEqual([],self.ui.inputs);self.assertEqual([],self.ui.results)
        self.assertEqual(4,self.p.run.call_count) # one XML/PNG observation, no interpretation
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        with self.assertRaises(RuntimeError):self.ui.tap(field(self.root,self.ui.w['server_url']))
        self.assertEqual(1,len(self.sends()));self.assertEqual(4,self.p.run.call_count)

    def test_raw_readback_mismatch_cannot_pass_even_after_command_success(self):
        field(self.root,self.ui.w['server_url']).set('text',RAW.strip())
        with self.assertRaisesRegex(ValueError,'raw input'):self.ui.enter('server_url',RAW)
        self.assertEqual([],self.ui.inputs);self.assertTrue(self.p.stop.is_set())
        self.assertEqual(1,len(self.sends()))

    def test_command_clamps_remaining_screen_deadline(self):
        def ready(*args,**kwargs):
            if 'focused' in args[0]:self.clock[0]=217
            return self.root,Path('readback.xml')
        self.ui.ready.side_effect=ready;self.send_seconds=1
        self.ui.enter('server_url',RAW)
        self.assertEqual(3,self.sends()[0][1]['maximum'])

    def test_command_clamps_remaining_ui_work_deadline(self):
        self.p.work_deadline=107;self.send_seconds=1
        self.ui.enter('server_url',RAW)
        self.assertEqual(7,self.sends()[0][1]['maximum'])

    def test_exhausted_budget_never_sends_or_uses_cleanup_reserve(self):
        self.p.work_deadline=100
        with self.assertRaises(TimeoutError):self.ui.enter('server_url',RAW)
        self.assertEqual([],self.commands);self.p.run.assert_not_called()
        self.assertEqual('skipped-no-work-budget',self.p.receipt['failureObservation']['status'])

    def test_late_completion_never_hides_or_passes(self):
        self.p.work_deadline=105;self.send_seconds=6
        with self.assertRaises(TimeoutError):self.ui.enter('server_url',RAW)
        self.assertEqual([],self.ui.inputs);self.p.run.assert_not_called()
        self.assertFalse(any('ADB_KEYBOARD_HIDE' in a for a,k in self.commands))

    def test_observation_failure_latches_and_does_not_mask_primary_failure(self):
        self.result=dict(exit=None,timeout=True,stdout='partial')
        self.p.run.side_effect=RuntimeError('forensic failed')
        with self.assertRaisesRegex(RuntimeError,'may already be applied'):self.ui.enter('server_url',RAW)
        self.assertEqual(1,self.p.run.call_count)
        self.assertEqual('observation-failed',self.p.receipt['failureObservation']['status'])
        self.assertTrue(self.p.stop.is_set())

    def test_observation_save_failure_preserves_original_input_exception(self):
        original=RuntimeError('original input timeout')
        def shell(*args,**kwargs):
            if args[:3]==('settings','get','secure'):return dict(stdout=IME)
            if 'ADB_KEYBOARD_SET_TEXT' in args:raise original
            return dict(exit=0,timeout=False,stdout='')
        self.p.shell.side_effect=shell
        self.p.save.side_effect=OSError('receipt unavailable')
        with self.assertRaises(RuntimeError) as caught:self.ui.enter('server_url',RAW)
        self.assertIs(caught.exception,original)
        event=self.p.receipt['failureObservation']
        self.assertEqual(event['error'],repr(original))
        self.assertIn('receipt unavailable',event['saveError'])
        self.assertTrue(self.ui.failed);self.assertTrue(self.p.stop.is_set())
        self.assertEqual([],self.ui.inputs)

    def test_observation_and_save_failures_do_not_replace_raw_mismatch(self):
        field(self.root,self.ui.w['server_url']).set('text',RAW.strip())
        self.p.run.side_effect=RuntimeError('capture unavailable')
        self.p.save.side_effect=OSError('receipt unavailable')
        with self.assertRaisesRegex(ValueError,'Exact raw input readback mismatch'):
            self.ui.enter('server_url',RAW)
        event=self.p.receipt['failureObservation']
        self.assertIn('capture unavailable',event['observationError'])
        self.assertIn('receipt unavailable',event['saveError'])
        self.assertEqual(1,len(self.sends()));self.assertEqual([],self.ui.inputs)
        self.assertTrue(self.p.stop.is_set())

    def test_observation_stops_at_work_deadline_without_touching_reserve(self):
        self.result=dict(exit=None,timeout=True,stdout='partial');self.p.work_deadline=115
        def observe(*args,**kw):
            self.assertTrue(self.p.stop.is_set());self.assertLessEqual(kw['maximum'],3)
            self.clock[0]+=3
            return dict(exit=0,timeout=False,stdout='')
        self.p.run.side_effect=observe
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        self.assertEqual(1,self.p.run.call_count)

    def test_forensic_failure_still_reaches_actual_execute_cleanup(self):
        from test_connectivity import ConnectivityIntegrationTests
        def fail(probe):
            # Use the real enter/failure path inside the real execute/finally cleanup.
            self.ui.p=probe
            probe.shell=Mock(side_effect=lambda *args,**kw:
                dict(stdout=IME) if args[:3]==('settings','get','secure') else
                dict(exit=None,timeout=True,stdout='possibly applied'))
            probe.run=Mock(side_effect=RuntimeError('observation failed'))
            self.ui.enter('server_url',RAW)
        ConnectivityIntegrationTests().execute(ui_failure=fail)
        self.assertEqual('observation-failed',self.ui.p.receipt['failureObservation']['status'])
        self.assertEqual('failed-or-blocked',self.ui.p.receipt['status'])
        self.assertEqual([],self.ui.inputs)

    def test_focus_consumes_screen_budget_no_broadcast_after_expiry(self):
        def ready(*args,**kwargs):
            if 'focused' in args[0]:self.clock[0]=220
            return self.root,Path('readback.xml')
        self.ui.ready.side_effect=ready
        with self.assertRaises(TimeoutError):self.ui.enter('server_url',RAW)
        self.assertEqual([],self.sends());self.p.run.assert_not_called()

    def test_timeout_flag_with_exit_zero_and_matching_raw_is_still_unknown(self):
        self.result=dict(exit=0,timeout=True,stdout='partial')
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        self.assertEqual([],self.ui.inputs);self.assertEqual(1,len(self.sends()))

    def test_nonzero_command_never_hides_retries_or_passes(self):
        self.result=dict(exit=1,timeout=False,stdout='error')
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        self.assertFalse(any('ADB_KEYBOARD_HIDE' in a for a,k in self.commands))
        self.assertEqual([],self.ui.inputs);self.assertEqual(1,len(self.sends()))

    def test_probe_latch_rejects_subsequent_mutation_before_subprocess(self):
        with patch.object(launcher,'EVIDENCE',self.directory):
            probe=launcher.Probe();probe.stop.set()
            with patch.object(launcher.subprocess,'run') as run:
                with self.assertRaisesRegex(RuntimeError,'requested stop'):
                    probe.shell('am','broadcast','-a','ADB_KEYBOARD_SET_TEXT')
                run.assert_not_called()

    def test_password_never_sent(self):
        with self.assertRaises(AssertionError):self.ui.enter('password','forbidden')
        self.assertEqual([],self.commands)

    def test_readiness_failure_latches_before_one_capture(self):
        self.ui.ready=UI.ready.__get__(self.ui)
        self.ui.readiness_snapshot=Mock(side_effect=RuntimeError('dump failed'))
        def observe(*args,**kwargs):
            self.assertTrue(self.p.stop.is_set())
            return dict(exit=0,timeout=False,stdout='')
        self.p.run.side_effect=observe
        with self.assertRaises(RuntimeError):self.ui.enter('server_url',RAW)
        self.assertEqual(4,self.p.run.call_count);self.assertEqual([],self.sends())


class GuestCleanupTests(unittest.TestCase):
    def cleanup(self, timeout=False, remaining=100, ime='original.IME', previous=True, receipt_error=False):
        clock=[100.0];calls=[]
        with tempfile.TemporaryDirectory() as d, patch.object(launcher,'EVIDENCE',Path(d)), \
             patch.object(launcher.time,'monotonic',side_effect=lambda:clock[0]), \
             patch.object(launcher,'port_open',return_value=True) as port, \
             patch.object(launcher.os,'kill') as kill, patch.object(launcher.os,'killpg') as killpg:
            p=launcher.Probe();p.total_deadline=100+remaining;p.server_owned=True
            p.previous_ime='original.IME' if previous else None
            p.receipt['status']='offline-ui-complete'
            def run(args,maximum=4,cleanup=False):
                calls.append((args,maximum,clock[0],p.total_deadline))
                self.assertLessEqual(clock[0]+maximum,p.total_deadline)
                clock[0]+=maximum if timeout else .1
                if 'kill-server' in args:port.return_value=False
                if receipt_error and 'shell' in args and 'settings' not in args:
                    raise OSError('receipt write failed after possible mutation')
                return dict(exit=None if timeout and 'settings' not in args else 0,
                            timeout=timeout and 'settings' not in args,stdout=ime)
            p.run=Mock(side_effect=run)
            p.cleanup();kill.assert_not_called();killpg.assert_not_called()
            return p.receipt,calls

    def test_four_guest_timeouts_not_retried_final_ime_match_host_separate(self):
        r,calls=self.cleanup(timeout=True)
        guest=[c for c in calls if 'shell' in c[0]]
        self.assertEqual(5,len(guest));self.assertTrue(all(c[1]==5 for c in guest))
        self.assertEqual({140},{c[3] for c in calls})
        self.assertTrue(r['hostCleanupResolved']);self.assertTrue(r['cleanupResolved'])
        self.assertTrue(r['originalImeReadbackMatched']);self.assertEqual('original.IME',r['restoredIME'])
        self.assertEqual(4,list(r['guestCleanupOutcome'].values()).count('unknown-timeout'))
        self.assertFalse(r['guestCleanupCommandsCompleted']);self.assertFalse(launcher.successful(r))

    def test_all_acknowledged_and_ime_verified_required_for_success(self):
        r,_=self.cleanup()
        self.assertTrue(r['guestCleanupCommandsCompleted']);self.assertTrue(launcher.successful(r))
        self.assertEqual(4,list(r['guestCleanupOutcome'].values()).count('acknowledged'))

    def test_mismatched_ime_cannot_be_masked_by_host_success(self):
        r,_=self.cleanup(ime='wrong.IME')
        self.assertTrue(r['hostCleanupResolved']);self.assertTrue(r['guestCleanupCommandsCompleted'])
        self.assertFalse(r['originalImeReadbackMatched']);self.assertFalse(launcher.successful(r))

    def test_shared_clock_exhaustion_never_resets_missing_steps_unknown(self):
        r,calls=self.cleanup(timeout=True,remaining=7)
        guest=[c for c in calls if 'shell' in c[0]]
        self.assertEqual([5,2],[c[1] for c in guest]);self.assertEqual({107},{c[3] for c in calls})
        self.assertIn('not-attempted',r['guestCleanupOutcome'].values())
        self.assertFalse(r['guestCleanupCommandsCompleted']);self.assertFalse(launcher.successful(r))

    def test_receipt_error_after_possible_mutation_is_not_claimed_unattempted(self):
        r,calls=self.cleanup(receipt_error=True)
        self.assertEqual(5,len([c for c in calls if 'shell' in c[0]]))
        self.assertEqual(4,list(r['guestCleanupOutcome'].values()).count('failed'))
        self.assertTrue(r['hostCleanupResolved']);self.assertTrue(r['originalImeReadbackMatched'])
        self.assertFalse(r['guestCleanupCommandsCompleted']);self.assertFalse(launcher.successful(r))

    def test_not_required_only_when_no_guest_actions(self):
        r,calls=self.cleanup(previous=False)
        self.assertFalse(any('shell' in c[0] for c in calls))
        self.assertFalse(r['guestCleanupRequired'])
        self.assertEqual({'not-required'},set(r['guestCleanupOutcome'].values()))
        self.assertTrue(launcher.successful(r))


if __name__=='__main__':unittest.main()
