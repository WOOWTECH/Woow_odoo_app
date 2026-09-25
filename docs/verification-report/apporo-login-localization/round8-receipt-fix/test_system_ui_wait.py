"""Offline mocks from round5 XML; no runtime, subprocesses, or device calls."""
import copy
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

from system_ui_wait import wait_center, modal_present
from ui_driver import UI, words

EVIDENCE = Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android')
FIXTURE = EVIDENCE/'round5-installed-pull/022-en-US-ready-login.xml'


class SystemUiWaitTests(unittest.TestCase):
    def setUp(self):
        self.root = ET.parse(FIXTURE).getroot()
        self.app = ET.parse(EVIDENCE/'localization/en-US-light-login.xml').getroot()

    def node(self, resource):
        return next(n for n in self.root.iter('node') if n.get('resource-id') == 'android:id/'+resource)

    def reject(self):
        with self.assertRaises(ValueError):wait_center(self.root, (1080, 2400), True, False)

    def test_exact_saved_dialog_once_and_no_close_coordinates(self):
        self.assertEqual((540, 1358), wait_center(self.root, (1080, 2400), True, False))
        with self.assertRaises(ValueError):wait_center(self.root, (1080, 2400), True, True)

    def test_apporo_or_other_app_anr_rejected(self):
        for title in ("Apporo platform isn't responding", "Other isn't responding", 'System UI isn’t responding'):
            with self.subTest(title=title):
                self.node('alertTitle').set('text', title);self.reject()

    def test_wrong_package_rejected(self):
        self.node('aerr_wait').set('package', 'com.apporo.odoo.debug');self.reject()

    def test_multiple_wait_rejected(self):
        self.root[0].append(copy.deepcopy(self.node('aerr_wait')));self.reject()

    def test_disabled_rejected(self):
        self.node('aerr_wait').set('enabled', 'false');self.reject()

    def test_not_clickable_rejected(self):
        self.node('aerr_wait').set('clickable', 'false');self.reject()

    def test_wrong_class_rejected(self):
        self.node('aerr_wait').set('class', 'android.widget.TextView');self.reject()

    def test_invalid_offscreen_reversed_and_negative_bounds_rejected(self):
        for value in ('[70,1295][1081,1421]', '[-1,1295][1010,1421]', '[70,1295][70,1421]', 'prefix[70,1295][1010,1421]'):
            with self.subTest(bounds=value):
                self.node('aerr_wait').set('bounds', value);self.reject()

    def test_overlap_close_rejected(self):
        self.node('aerr_wait').set('bounds', '[70,1169][1010,1295]');self.reject()

    def test_outside_parent_rejected(self):
        self.node('aerr_wait').set('bounds', '[0,1295][1010,1421]');self.reject()

    def test_later_readiness_rejected(self):
        with self.assertRaises(ValueError):wait_center(self.root, (1080, 2400), False, False)

    def test_unknown_modal_rejected(self):
        self.node('alertTitle').set('text', 'Unknown permission');self.reject()

    def test_app_snapshot_not_modal_or_checkpoint_override(self):
        self.assertFalse(modal_present(self.app))
        self.assertIsNone(wait_center(self.app, (1080, 2400), True, True))

    def ui(self, directory):
        probe = Mock();probe.receipt = {};probe.work_deadline = time.monotonic()+720
        ui = UI(probe);ui.directory = Path(directory);ui.w = words('en-US')
        image = ui.directory/'fresh.png'
        image.write_bytes(b'\x89PNG\r\n\x1a\n'+b'\x00\x00\x00\rIHDR'+struct.pack('>II', 1080, 2400))
        ui.image = Mock(return_value=image)
        return ui

    def test_integration_fresh_coordinates_one_tap_before_after_and_same_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = self.ui(directory)
            fresh = copy.deepcopy(self.root)
            next(n for n in fresh.iter('node') if n.get('text') == 'Wait').set('bounds', '[80,1300][1000,1400]')
            ui.dump = Mock(side_effect=[(self.root, Path('initial.xml')), (fresh, Path('fresh.xml')), (self.app, Path('after.xml'))])
            deadline = time.monotonic()+120
            result = ui.readiness_snapshot('en-US-ready-login', deadline)
            self.assertIs(self.app, result[0])
            self.assertTrue(all(call.args[1] == deadline for call in ui.dump.call_args_list))
            ui.p.shell.assert_called_once()
            self.assertEqual(('input', 'tap', '540', '1350'), ui.p.shell.call_args.args)
            self.assertEqual(1, ui.p.receipt['systemUiWaitCount'])
            event = ui.p.receipt['systemUiModalEvents'][0]
            self.assertEqual('fresh.xml', event['beforeXml']);self.assertEqual('after.xml', event['afterXml'])
            self.assertIn('beforeScreenshot', event);self.assertIn('afterScreenshot', event)
            self.assertEqual(['initial-modal-before', 'after'], [c.args[0] for c in ui.image.call_args_list])
            self.assertEqual([], ui.results)

    def test_reappeared_modal_saves_after_evidence_stops_without_second_tap(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = self.ui(directory)
            ui.dump = Mock(side_effect=[(self.root, Path('initial.xml')), (self.root, Path('fresh.xml')), (self.root, Path('after.xml'))])
            with self.assertRaises(ValueError):ui.readiness_snapshot('en-US-ready-login', time.monotonic()+120)
            ui.p.shell.assert_called_once()
            self.assertEqual('after.xml', ui.p.receipt['systemUiModalEvents'][0]['afterXml'])
            self.assertIn('afterScreenshot', ui.p.receipt['systemUiModalEvents'][0])
            self.assertEqual([], ui.results)

    def test_confirmation_becomes_other_modal_never_tapped(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = self.ui(directory);bad = copy.deepcopy(self.root)
            next(n for n in bad.iter('node') if n.get('resource-id') == 'android:id/alertTitle').set('text', "Apporo isn't responding")
            ui.dump = Mock(side_effect=[(self.root, Path('initial.xml')), (bad, Path('fresh.xml'))])
            with self.assertRaises(ValueError):ui.readiness_snapshot('en-US-ready-login', time.monotonic()+120)
            ui.p.shell.assert_not_called()

    def test_expired_deadline_never_taps(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = self.ui(directory)
            ui.dump = Mock(return_value=(self.root, Path('fresh.xml')))
            with self.assertRaises(TimeoutError):ui.readiness_snapshot('en-US-ready-login', time.monotonic()-1)
            ui.p.shell.assert_not_called()

    def test_wait_does_not_make_wrong_locale_fields_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = self.ui(directory);ui.w = words('zh-TW')
            ui.readiness_snapshot = Mock(side_effect=[(self.app, Path('wrong-locale.xml')), RuntimeError('stop fixture')])
            with patch('readiness.time.sleep'), patch('launcher.port_open',return_value=False):
                with self.assertRaises(RuntimeError):ui.ready('en-US-ready-login')
            self.assertEqual([], ui.results)
            ui.p.shell.assert_not_called()
            ui.p.run.assert_not_called()


if __name__ == '__main__':unittest.main()
