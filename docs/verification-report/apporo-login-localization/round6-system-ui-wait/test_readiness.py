import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import xml.etree.ElementTree as ET
from readiness import field,form_ready,await_ready,safe_missing_credentials
from ui_driver import UI

EVIDENCE=Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android')


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.ready=ET.parse(EVIDENCE/'localization/en-US-light-login.xml').getroot()
        self.empty=ET.parse(EVIDENCE/'kernel-diagnostic-01/previous-failed-dump.xml').getroot()
        self.clock=0
        self.clicked=[]

    def predicate(self,root):return form_ready(root,['Server URL','Database Name'],'Next')

    def pause(self,seconds):self.clock+=seconds

    def test_real_empty_compose_snapshot_never_ready(self):
        self.assertFalse(self.predicate(self.empty));self.assertIsNone(field(self.empty,'Server URL'))

    def test_observed_label_selects_field_without_global_index(self):
        self.assertTrue(self.predicate(self.ready))
        self.assertEqual('android.widget.EditText',field(self.ready,'Database Name').get('class'))
        self.assertIsNone(field(self.ready,'服务器网址'))

    def test_empty_then_ready_allows_only_ready_snapshot_action(self):
        snapshots=iter([self.empty,self.ready])
        def fetch(deadline):self.clock+=1;return next(snapshots),'saved.xml'
        result=await_ready(fetch,self.predicate,10,clock=lambda:self.clock,pause=self.pause)
        self.clicked.append(field(result[0],'Server URL'))
        self.assertEqual(1,len(self.clicked));self.assertIsNotNone(self.clicked[0])

    def test_empty_until_deadline_never_clicks(self):
        def fetch(deadline):self.clock+=1;return self.empty,'saved-empty.xml'
        with self.assertRaises(TimeoutError):
            result=await_ready(fetch,self.predicate,3,clock=lambda:self.clock,pause=self.pause)
            self.clicked.append(result)
        self.assertEqual([],self.clicked)

    def test_dump_timeout_is_not_retried_or_clicked(self):
        fetch=Mock(side_effect=TimeoutError('dump60s'))
        with self.assertRaises(TimeoutError):
            result=await_ready(fetch,self.predicate,120,clock=lambda:0,pause=lambda _:None)
            self.clicked.append(result)
        self.assertEqual(1,fetch.call_count);self.assertEqual([],self.clicked)

    def test_ready_snapshot_arriving_after_deadline_never_clicks(self):
        def fetch(deadline):self.clock=121;return self.ready,'late-ready.xml'
        with self.assertRaises(TimeoutError):
            result=await_ready(fetch,self.predicate,120,clock=lambda:self.clock,pause=self.pause)
            self.clicked.append(result)
        self.assertEqual([],self.clicked)

    def test_duplicate_label_is_ambiguous_not_guessed(self):
        root=copy.deepcopy(self.ready)
        for parent in root.iter():
            children=list(parent)
            for i,n in enumerate(children[:-1]):
                if n.get('text')=='Server URL':
                    parent.append(copy.deepcopy(n));parent.append(copy.deepcopy(children[i+1]));break
            else:continue
            break
        self.assertIsNone(field(root,'Server URL'))

    def test_complete_credentials_are_rejected_but_one_empty_is_allowed(self):
        root=copy.deepcopy(self.ready)
        for n in root.iter('node'):
            if n.get('text')=='Server URL':n.set('text','Username')
            if n.get('text')=='Database Name':n.set('text','Password')
        field(root,'Username').set('text','local-user')
        self.assertTrue(safe_missing_credentials(root,'Username','Password'))
        field(root,'Password').set('text','not-allowed')
        self.assertFalse(safe_missing_credentials(root,'Username','Password'))

    def test_failed_dump_still_pulls_and_preserves_own_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Mock(receipt={});p.shell.side_effect=TimeoutError('dump timed out')
            ui=UI(p);ui.directory=Path(directory)
            def pull(args,maximum):
                Path(args[-1]).write_text(ET.tostring(self.empty,encoding='unicode'))
                return {'exit':0}
            ui.forensic=pull
            with self.assertRaises(TimeoutError):ui.dump('timeout',__import__('time').monotonic()+120)
            self.assertTrue((Path(directory)/'001-timeout.xml').exists())
            self.assertTrue(json.loads((Path(directory)/'001-timeout-dump.json').read_text())['xmlSaved'])


if __name__=='__main__':unittest.main()
