"""Evidence must prove coupon money, identities and all three exact notice replays."""
import copy
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from coupon_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment, cases
from fulfillment_api_cases import assemble


class CouponEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='coupon-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician')}
        self.tokens['payment_secret']='synthetic-coupon-payment-only-secret'
        definitions={x['name']:x for x in self.manifest['items']};executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']];url=urlsplit(item['request']['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),
                'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},'header':headers}
            if 'callback_mode' in case:
                mode=case['callback_mode'];timestamp='1' if mode=='expired' else '1800000000';nonce='synthetic-nonce'
                body={'payment_id':1,'amount':'invalid' if mode=='bad_body' else '7.34','currency':'CNY',
                      'event_id':'failed' if 'failed' in case['name'] else 'succeeded','status':case['notice_status']}
                if '/refunds/' in case['path']:body['refund_id']=1
                request['body']={'raw':json.dumps(body)}
                headers += [{'key':k,'value':v} for k,v in [('X-Test-Timestamp',timestamp),('X-Test-Nonce',nonce),('X-Test-Signature','')]]
                self.sign(request)
                if mode=='bad_signature':headers[-1]['value']='0'*64
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},
                               'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT/'.cache').resolve()) and path.name.startswith('coupon-evidence-')
        self.temp.cleanup()

    def sign(self,request):
        headers={x['key']:x for x in request['header']}
        message=headers['X-Test-Timestamp']['value']+'\n'+headers['X-Test-Nonce']['value']+'\n'+request['body']['raw']+'\n'
        headers['X-Test-Signature']['value']=hmac.new(self.tokens['payment_secret'].encode(),message.encode(),hashlib.sha256).hexdigest()

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_complete_evidence_generated_scope_and_closed_default(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((160,547,20),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertEqual(3,result['signed_notice_replay_pairs_verified'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertIn('skipRequest',''.join(self.collection['event'][0]['script']['exec']))

    def test_missing_duplicate_failed_or_skipped_case_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','failure','skipped'):
            with self.subTest(kind=kind):
                self.report=copy.deepcopy(original);run=self.report['run']
                if kind=='missing':run['executions'].pop()
                elif kind=='duplicate':run['executions'][-1]=run['executions'][0]
                elif kind=='failure':run['failures']=[{'error':'synthetic'}]
                else:run['executions'][0]['assertions'][0]['skipped']=True
                with self.assertRaises(ValueError):self.check()

    def test_other_shop_case_requires_actual_other_shop_token(self):
        self.case('service_redeem_foreign_shop')['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
        with self.assertRaises(ValueError):self.check()

    def test_signed_gross_amount_cannot_substitute_discounted_cash(self):
        request=self.case('payment_paid')['request'];body=json.loads(request['body']['raw']);body['amount']='12.34'
        request['body']['raw']=json.dumps(body);self.sign(request)
        with self.assertRaises(ValueError):self.check()

    def test_valid_signature_cannot_target_other_refund(self):
        request=self.case('refund_succeeded')['request'];body=json.loads(request['body']['raw']);body['refund_id']=2
        request['body']['raw']=json.dumps(body);self.sign(request)
        with self.assertRaises(ValueError):self.check()

    def test_tampered_refund_bytes_rejected(self):
        self.case('refund_failed')['request']['body']['raw']+=' '
        with self.assertRaises(ValueError):self.check()

    def test_valid_new_signature_is_not_an_exact_replay(self):
        request=self.case('refund_succeeded_replay')['request'];request['body']['raw']+=' ';self.sign(request)
        with self.assertRaises(ValueError):self.check()

    def test_suite_flags_reject_shared_database_before_docker(self):
        for flags in [('--owner-cases','--coupon-cases'),('--fulfillment-cases','--coupon-cases'),('--owner-cases','--fulfillment-cases')]:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),*flags],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_local_mode_does_not_configure_supplier_dispatch(self):
        config=dict(environment(self.tokens))
        self.assertEqual('local-payment-test',config['SPRING_PROFILES_ACTIVE'])
        self.assertEqual('true',config['PAYMENT_LOCAL_TEST_ENABLED'])
        self.assertNotIn('PAYMENT_LOCAL_TEST_QUERY_URL',config)
        self.assertNotIn('REFUND_RECOVERY_ENABLED',config)

    def test_duplicate_case_names_fail_before_execution(self):
        items=cases();items.append(copy.deepcopy(items[0]))
        with self.assertRaisesRegex(ValueError,'Duplicate'):assemble(items,'synthetic','closed',20)

    def test_failed_refund_case_cannot_send_success_notice(self):
        request=self.case('refund_failed')['request'];body=json.loads(request['body']['raw']);body['status']='SUCCEEDED'
        request['body']['raw']=json.dumps(body);self.sign(request)
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
