"""Reject altered dates, closure intents and identities in fulfillment HTTP evidence."""
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
from fulfillment_reads_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class FulfillmentReadsEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='fulfillment-reads-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ('owner','other','merchant','other_merchant','technician')}
        self.tokens['payment_secret']='synthetic-secret'
        self.tokens['slot_date']='2026-10-23'
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=urlsplit(expected['url'].removeprefix('{{base_url}}').replace('{{slot_date}}',self.tokens['slot_date']))
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

    def test_assets_scope_and_closed_default(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        result=self.check();self.assertEqual((170,569,26),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['signed_callback_and_exact_replay_verified']);self.assertTrue(result['slot_close_intent_verified']);self.assertTrue(result['slot_date_query_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_missing_duplicate_failed_skipped_and_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('slot_close')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'failed'}]
            elif mode=='skipped':self.case('merchant_work')['assertions'][0]['skipped']=True
            else:self.case('owner_redemption')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_seed_date_pagination_and_filters_cannot_be_changed(self):
        original=copy.deepcopy(self.report)
        for name,key,value in [('available_slots','date','2026-10-24'),('merchant_slots_boundary','page','1'),('completed_order_list','status','PENDING_PAYMENT')]:
            self.report=copy.deepcopy(original);query=self.case(name)['request']['url']['query']
            next(q for q in query if q['key']==key)['value']=value
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_closure_original_key_and_empty_body_required(self):
        original=copy.deepcopy(self.report)
        for mode in ('key','body'):
            self.report=copy.deepcopy(original);request=self.case('slot_close')['request']
            if mode=='key':next(h for h in request['header'] if h['key']=='Idempotency-Key')['value']='changed-key'
            else:request['body']['raw']='{"injected":true}'
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_foreign_owner_and_shop_tokens_are_actual(self):
        original=copy.deepcopy(self.report)
        for name,role in [('merchant_slots_other_shop','merchant'),('owner_orders_other_owner','owner'),('owner_work_other_owner','owner'),('slot_close_other_shop','merchant')]:
            self.report=copy.deepcopy(original);request=self.case(name)['request']
            next(h for h in request['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens[role+'_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_request_route_and_status_must_match_manifest(self):
        original=copy.deepcopy(self.report)
        self.case('owner_redemption')['request']['url']['path'][-1]='work'
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('slot_close_changed_intent')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_callback_requires_real_signature_and_identical_replay(self):
        self.case('callback_paid')['request']['body']['raw']='{}'
        with self.assertRaises(ValueError):self.check()

    def test_separate_database_and_explicit_local_payment(self):
        self.assertEqual('true',dict(environment(self.tokens))['PAYMENT_LOCAL_TEST_ENABLED'])
        for flag in ('--governance-cases','--fulfillment-cases','--record-ci-sources'):
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--fulfillment-reads-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
