"""Reject wrong operator/staff identities and accidental caching of one-time employee codes."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from merchant_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class MerchantEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='merchant-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician','operator','review_operator','staff','pending_merchant')}
        definitions={x['name']:x for x in self.manifest['items']};executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']];url=urlsplit(item['request']['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},'header':headers},
                'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT/'.cache').resolve()) and path.name.startswith('merchant-evidence-')
        self.temp.cleanup()

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def test_fresh_assets_complete_scope_and_closed_default(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((119,397,17),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['employee_code_requests_without_idempotency_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_review_operator_case_cannot_use_onboard_operator_token(self):
        self.case('pending_review_permission_not_onboard')['request']['header'][0]['value']='Bearer '+self.tokens['operator_token']
        with self.assertRaises(ValueError):self.check()

    def test_pending_activation_case_cannot_use_active_merchant_token(self):
        self.case('pending_activation_cannot_use_signed_token')['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
        with self.assertRaises(ValueError):self.check()

    def test_employee_code_requests_must_never_carry_cache_key(self):
        self.case('code_issue')['request']['header'].append({'key':'Idempotency-Key','value':'synthetic-key'})
        with self.assertRaisesRegex(ValueError,'must not use'):self.check()

    def test_missing_duplicate_skipped_failed_or_wrong_status_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','skipped','failed','status'):
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='duplicate':run['executions'][-1]=run['executions'][0]
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            elif kind=='failed':run['failures']=[{'error':'synthetic'}]
            else:run['executions'][0]['response']['code']=500
            with self.assertRaises(ValueError):self.check()

    def test_merchant_suite_cannot_share_other_suite_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--merchant-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_business_flags_enable_only_local_management(self):
        config=dict(environment(self.tokens))
        self.assertEqual('true',config['MERCHANT_ONBOARDING_ENABLED'])
        self.assertEqual('true',config['MERCHANT_STAFF_ENABLED'])
        self.assertNotIn('PAYMENT_LOCAL_TEST_ENABLED',config)


if __name__=='__main__':unittest.main()
