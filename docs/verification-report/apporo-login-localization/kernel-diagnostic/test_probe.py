"""Hermetic tooling checks: no emulator, adb, network or subprocess execution."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import probe


class ProbeTests(unittest.TestCase):
    def test_capacity_boundaries_apply_to_both_volumes(self):
        self.assertTrue(probe.disk_ok({'internal':3*probe.GIB,'external':3*probe.GIB},True))
        self.assertFalse(probe.disk_ok({'internal':4*probe.GIB,'external':3*probe.GIB-1},True))
        self.assertFalse(probe.disk_ok({'internal':2*probe.GIB-1,'external':8*probe.GIB}))
        self.assertFalse(probe.disk_ok({'internal':8*probe.GIB,'external':2*probe.GIB-1}))
        self.assertTrue(probe.disk_ok({'internal':2*probe.GIB,'external':2*probe.GIB}))

    def test_timeout_never_exceeds_remaining_budget(self):
        with patch.object(probe.time,'monotonic',return_value=100):
            self.assertEqual(1,probe.remaining_timeout(101,4))
            with self.assertRaises(TimeoutError):probe.remaining_timeout(100,4)

    def test_exact_uid_rejects_other_packages_or_multiple_rows(self):
        self.assertEqual('10213',probe.exact_uid('com.apporo.odoo.debug','package:com.apporo.odoo.debug uid:10213'))
        for listing in ('package:com.apporo.odoo.debug.other uid:10213','package:com.apporo.odoo.debug uid:0','package:com.apporo.odoo.debug uid:10213\npackage:other uid:10214'):
            with self.assertRaises(ValueError):probe.exact_uid('com.apporo.odoo.debug',listing)

    def test_absent_server_does_not_auto_start_adb(self):
        p=probe.Probe()
        with patch.object(probe,'port_open',return_value=False),patch.object(probe.subprocess,'run') as run:
            with self.assertRaises(RuntimeError):p.adb('get-state')
            run.assert_not_called()

    def test_cleanup_failure_does_not_skip_later_steps_or_receipt(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(probe,'EVIDENCE',Path(directory)),patch.object(probe,'port_open',return_value=True),patch.object(probe.time,'sleep'),patch.object(probe.os,'killpg') as killpg,patch.object(probe.os,'kill') as kill:
            p=probe.Probe();p.receipt['status']='injected-failure';p.job=Mock(pid=12345)
            p.job.poll.return_value=None
            p.job.wait.side_effect=subprocess.TimeoutExpired('owned-qemu',1)
            p.server_owned=True;p.server_pid=12346
            p.run=Mock(side_effect=RuntimeError('adb failure'))
            p.listener_pid=Mock(return_value=12346)
            p.kernel=Mock();p.kernel.close.side_effect=RuntimeError('log close failure')
            p.cleanup()
            self.assertEqual(2,killpg.call_count)
            kill.assert_called_once_with(12346,probe.signal.SIGTERM)
            receipt=json.loads((Path(directory)/'receipt.json').read_text())
            self.assertFalse(receipt['cleanupResolved'])
            self.assertIn('portsClosed',receipt)
            self.assertTrue(any(x['step']=='close kernel log' and x['status']=='error' for x in receipt['cleanup']))

    def test_preflight_failure_writes_receipt_without_launch_or_kill(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(probe,'EVIDENCE',Path(directory)),patch.object(probe.shutil,'disk_usage',return_value=Mock(free=2*probe.GIB)),patch.object(probe,'port_open',return_value=False),patch.object(probe.subprocess,'Popen') as launch,patch.object(probe.os,'killpg') as kill:
            result=probe.Probe().execute()
            launch.assert_not_called();kill.assert_not_called()
            self.assertTrue(result['cleanupResolved'])
            self.assertEqual('failed-or-blocked',result['status'])
            self.assertTrue((Path(directory)/'receipt.json').exists())

    def test_only_emulator_argument_change_is_show_kernel(self):
        p=probe.Probe()
        original=json.loads(probe.BASE.read_text())['command']
        self.assertEqual(p.sandbox+original+['-show-kernel'],p.emulator_command)
        self.assertEqual('0',p.env['ADB_MDNS_AUTO_CONNECT'])
        self.assertEqual('tcp:localhost:5038',p.env['ADB_SERVER_SOCKET'])
        self.assertNotIn('HTTP_PROXY',p.env)


if __name__=='__main__':unittest.main()
