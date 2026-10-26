"""Reject incomplete, substituted or incorrectly authorized dispute execution evidence."""
import copy
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit,parse_qsl
from dispute_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class DisputeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='dispute-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ('owner','other','merchant','other_merchant','technician')}
        self.tokens['payment_secret']='synthetic-secret'
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=urlsplit(expected['url'].removeprefix('{{base_url}}'))
            headers=copy.deepcopy(expected['header'])
            if case['role']!='anonymous':headers.append({'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']})
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},'header':headers}
            if 'body' in expected:request['body']=copy.deepcopy(expected['body'])
            if 'callback_mode' in case:
                mode=case['callback_mode'];timestamp='1' if mode=='expired' else '1800000000';nonce='synthetic-nonce'
                raw=json.dumps({'payment_id':1,'amount':'invalid' if mode=='bad_body' else '12.34','currency':'CNY'})
                request['body']={'raw':raw}
                signature=hmac.new(self.tokens['payment_secret'].encode(),(timestamp+'\n'+nonce+'\n'+raw+'\n').encode(),hashlib.sha256).hexdigest()
                if mode=='bad_signature':signature='0'*64
                headers.extend({'key':k,'value':v} for k,v in [('X-Test-Timestamp',timestamp),('X-Test-Nonce',nonce),('X-Test-Signature',signature)])
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(e for e in self.report['run']['executions'] if e['item']['name']==name)
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8');return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_current_assets_four_categories_and_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        result=self.check();self.assertEqual((144,485,18),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['dispute_write_intents_verified']);self.assertTrue(result['signed_callback_and_exact_replay_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_missing_duplicate_failed_skipped_partial_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('review_accept')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'failed'}]
            elif mode=='skipped':self.case('review_accept')['assertions'][0]['skipped']=True
            else:self.case('handle_first')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_cross_shop_and_owner_require_actual_foreign_token(self):
        original=copy.deepcopy(self.report)
        for name,role in [('handle_other_shop','merchant'),('review_other_owner','owner'),('dispute_open_other_owner','owner')]:
            self.report=copy.deepcopy(original);request=self.case(name)['request']
            next(h for h in request['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens[role+'_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_accept_reject_note_and_key_intents_cannot_be_substituted(self):
        original=copy.deepcopy(self.report)
        for name in ('dispute_open','handle_first','handle_second','review_reject','review_accept','handle_too_long'):
            for mode in ('body','key'):
                self.report=copy.deepcopy(original);request=self.case(name)['request']
                if mode=='body':request['body']['raw']='{}'
                else:next(h for h in request['header'] if h['key']=='Idempotency-Key')['value']='other-key'
                with self.subTest(name=name,mode=mode),self.assertRaises(ValueError):self.check()

    def test_altered_route_method_and_query_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('route','method','query'):
            self.report=copy.deepcopy(original);request=self.case('review_accept')['request']
            if mode=='route':request['url']['path'][-1]='confirm'
            elif mode=='method':request['method']='PUT'
            else:request['url']['query']=[{'key':'order_id','value':'2'}]
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_early_review_and_disputed_assignment_must_fail(self):
        original=copy.deepcopy(self.report)
        for name in ('review_before_handling','assignment_disputed','assignment_after_rejection','review_already_resolved'):
            self.report=copy.deepcopy(original);self.case(name)['response']['code']=200
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_signed_payment_callback_still_required(self):
        self.case('callback_paid')['request']['body']['raw']='{}'
        with self.assertRaises(ValueError):self.check()

    def test_separate_database_and_explicit_test_environment(self):
        config=dict(environment(self.tokens));self.assertEqual('250',config['SERVICE_NOTIFICATION_DELAY_MS'])
        self.assertEqual('true',config['PAYMENT_LOCAL_TEST_ENABLED'])
        for flag in ('--workflow-cases','--fulfillment-cases','--record-ci-sources'):
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--dispute-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
