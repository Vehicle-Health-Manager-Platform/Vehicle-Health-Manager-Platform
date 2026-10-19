"""Reject incomplete journey evidence, wrong identities and false callback/replay claims."""
import copy
import hashlib
import hmac
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from fulfillment_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class FulfillmentEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='fulfillment-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician')}
        self.tokens['payment_secret']='synthetic-local-payment-only-secret'
        cases={x['name']:x for x in self.manifest['items']}
        executions=[]
        for item in self.collection['item']:
            case=cases[item['name']];url=urlsplit(item['request']['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),
                'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},'header':headers}
            if 'callback_mode' in case:
                mode=case['callback_mode'];timestamp='1' if mode=='expired' else '1800000000';nonce='synthetic-nonce'
                body={'payment_id':1,'amount':'invalid' if mode=='bad_body' else '12.34','currency':'CNY'}
                raw=json.dumps(body);request['body']={'raw':raw}
                signature=hmac.new(self.tokens['payment_secret'].encode(),(timestamp+'\n'+nonce+'\n'+raw+'\n').encode(),hashlib.sha256).hexdigest()
                if mode=='bad_signature':signature='0'*64
                headers += [{'key':k,'value':v} for k,v in [('X-Test-Timestamp',timestamp),('X-Test-Nonce',nonce),('X-Test-Signature',signature)]]
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},
                               'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT/'.cache').resolve()) and path.name.startswith('fulfillment-evidence-')
        self.temp.cleanup()

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def case(self,name):
        return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def test_complete_evidence_and_fresh_closed_assets(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((102,341,16),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['signed_callback_and_exact_replay_verified'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertIn('skipRequest',''.join(self.collection['event'][0]['script']['exec']))

    def test_missing_duplicated_or_failed_execution_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','failure','skipped'):
            with self.subTest(kind=kind):
                self.report=copy.deepcopy(original);run=self.report['run']
                if kind=='missing':run['executions'].pop()
                elif kind=='duplicate':run['executions'][-1]=run['executions'][0]
                elif kind=='failure':run['failures']=[{'error':'synthetic'}]
                else:run['executions'][0]['assertions'][0]['skipped']=True
                with self.assertRaises(ValueError):self.check()

    def test_cross_shop_case_requires_actual_other_shop_identity(self):
        self.case('merchant_order_foreign')['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
        with self.assertRaises(ValueError):self.check()

    def test_jwt_is_not_callback_authorization(self):
        self.case('callback_paid')['request']['header'].append({'key':'Authorization','value':'Bearer '+self.tokens['owner_token']})
        with self.assertRaises(ValueError):self.check()

    def test_signed_callback_cannot_be_unsigned_or_tampered(self):
        self.case('callback_paid')['request']['body']['raw']='{"payment_id":2,"amount":"12.34","currency":"CNY"}'
        with self.assertRaises(ValueError):self.check()

    def test_successful_replay_requires_identical_signed_bytes(self):
        request=self.case('callback_replay')['request'];request['body']['raw']+=' '
        headers={x['key']:x for x in request['header']}
        message=headers['X-Test-Timestamp']['value']+'\n'+headers['X-Test-Nonce']['value']+'\n'+request['body']['raw']+'\n'
        headers['X-Test-Signature']['value']=hmac.new(self.tokens['payment_secret'].encode(),message.encode(),hashlib.sha256).hexdigest()
        with self.assertRaises(ValueError):self.check()

    def test_negative_signature_case_cannot_send_valid_signature(self):
        request=self.case('callback_bad_signature')['request'];headers={x['key']:x for x in request['header']}
        raw=headers['X-Test-Timestamp']['value']+'\n'+headers['X-Test-Nonce']['value']+'\n'+request['body']['raw']+'\n'
        headers['X-Test-Signature']['value']=hmac.new(self.tokens['payment_secret'].encode(),raw.encode(),hashlib.sha256).hexdigest()
        with self.assertRaises(ValueError):self.check()

    def test_backend_mode_is_explicitly_isolated_local_payment(self):
        config=dict(environment(self.tokens))
        self.assertEqual('local-payment-test',config['SPRING_PROFILES_ACTIVE'])
        self.assertEqual('true',config['PAYMENT_LOCAL_TEST_ENABLED'])
        self.assertEqual('true',config['SERVICE_ARCHIVE_ENABLED'])
        self.assertFalse(any('WECHAT_PAY' in key for key in config))


if __name__=='__main__':unittest.main()
