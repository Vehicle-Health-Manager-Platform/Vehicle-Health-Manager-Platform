"""Fail closed when inbox-derived IDs, read receipts or actual HTTP evidence differ."""
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
from workflow_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class WorkflowEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='workflow-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ('owner','other','merchant','other_merchant','technician')}
        self.tokens['payment_secret']='synthetic-secret'
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=expected['url'].removeprefix('{{base_url}}')
            for route,n in [('owner',13),('merchant',14),('tech',15)]:url=url.replace('{{'+route+'_notice_id}}',str(n))
            url=urlsplit(url);headers=copy.deepcopy(expected['header'])
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
            payload={}
            for route,n in [('owner',13),('merchant',14),('tech',15)]:
                if case['name']==route+'_notifications':payload={'unread_count':4 if route=='tech' else 6,'notifications':{'items':[{'kind':'ORDER_COMPLETE','order_id':1,'notification_id':n,'read_at':None}]}}
                if case['name'] in [route+s for s in ('_notice_read','_notice_read_replay','_notice_read_new_key')]:payload={'notification_id':n,'read_at':'2026-10-21T10:00:00+08:00'}
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status'],'stream':{'type':'Buffer','data':list(json.dumps({'data':payload}).encode())}},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(e for e in self.report['run']['executions'] if e['item']['name']==name)
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8');return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_complete_assets_scope_and_default_closed(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        result=self.check();self.assertEqual((171,567,26),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['inbox_ids_and_read_receipts_verified']);self.assertTrue(result['signed_callback_and_exact_replay_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_missing_duplicate_failed_skipped_and_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('tech_notice_read')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'failed'}]
            elif mode=='skipped':self.case('owner_notifications')['assertions'][0]['skipped']=True
            else:self.case('merchant_notice_read')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_notification_path_must_use_actual_inbox_id(self):
        self.case('owner_notice_read')['request']['url']['path'][-2]='999'
        with self.assertRaises(ValueError):self.check()

    def test_inbox_must_be_unread_completed_order_and_actual_recipient(self):
        original=copy.deepcopy(self.report)
        for key,value in [('order_id',2),('kind','ORDER_PAID'),('notification_id',0),('read_at','already-read')]:
            self.report=copy.deepcopy(original);stream=self.case('owner_notifications')['response']['stream'];payload=json.loads(bytes(stream['data']))
            payload['data']['notifications']['items'][0][key]=value;stream['data']=list(json.dumps(payload).encode())
            with self.subTest(key=key),self.assertRaises(ValueError):self.check()

    def test_read_body_and_key_are_bound_to_declared_intent(self):
        original=copy.deepcopy(self.report)
        for mode in ('body','key'):
            self.report=copy.deepcopy(original);request=self.case('merchant_notice_read')['request']
            if mode=='body':request['body']['raw']='{"injected":true}'
            else:next(h for h in request['header'] if h['key']=='Idempotency-Key')['value']='different-key'
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_new_key_cannot_change_original_read_timestamp(self):
        stream=self.case('tech_notice_read_new_key')['response']['stream'];payload=json.loads(bytes(stream['data']));payload['data']['read_at']='changed';stream['data']=list(json.dumps(payload).encode())
        with self.assertRaises(ValueError):self.check()

    def test_foreign_tokens_and_query_cannot_be_substituted(self):
        original=copy.deepcopy(self.report)
        self.case('pickup_context_foreign')['request']['header'].append({'key':'Authorization','value':'Bearer '+self.tokens['merchant_token']})
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('owner_notifications_boundary')['request']['url']['query'][0]['value']='1'
        with self.assertRaises(ValueError):self.check()

    def test_signed_callback_remains_required(self):
        self.case('callback_paid')['request']['body']['raw']='{}'
        with self.assertRaises(ValueError):self.check()

    def test_suite_is_isolated_and_cannot_mix_with_other_suites(self):
        self.assertEqual('250',dict(environment(self.tokens))['SERVICE_NOTIFICATION_DELAY_MS'])
        for flag in ('--finance-cases','--fulfillment-cases','--record-ci-sources'):
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--workflow-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
