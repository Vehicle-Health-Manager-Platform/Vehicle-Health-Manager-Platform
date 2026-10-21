import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from urllib.parse import urlsplit,parse_qsl
from finance_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment,verify_supplier


class FinanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='finance-evidence-',dir=ROOT/'.cache')
        self.path=Path(self.temp.name)/'report.json'
        self.assertTrue(self.path.parent.resolve().is_relative_to((ROOT/'.cache').resolve()))
        self.addCleanup(self.temp.cleanup)
        self.tokens={r+'_token':'synthetic-'+r for r in ['owner','other','merchant','other_merchant','staff']}
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=urlsplit(expected['url'].removeprefix('{{base_url}}'))
            headers=copy.deepcopy(expected['header'])+([] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}])
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers}
            if 'body' in expected:request['body']=copy.deepcopy(expected['body'])
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def case(self,name):return next(i for i in self.report['run']['executions'] if i['item']['name']==name)

    def test_assets_are_current_four_categories_cover_all_eight_operations(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        result=self.check();self.assertEqual(8,len(result['coverage']));self.assertEqual(72,result['requests'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_missing_duplicate_skipped_failed_and_partial_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ['missing','duplicate','skipped','failed','partial']:
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.report['run']['executions'][0]))
            elif mode=='skipped':self.case('refund_reconcile')['assertions'][0]['skipped']=True
            elif mode=='failed':self.report['run']['failures']=[{'error':'failed'}]
            else:self.case('commissions')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_cross_actor_or_cross_shop_tokens_cannot_be_forged(self):
        original=copy.deepcopy(self.report)
        for name in ['payment_other_owner','refund_detail_staff','decision_other_shop','refund_reconcile_owner','commissions_other_shop']:
            self.report=copy.deepcopy(original)
            next(h for h in self.case(name)['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['owner_token']
            # Owner-denial needs an actually different token too.
            if name=='refund_reconcile_owner':next(h for h in self.case(name)['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['merchant_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_strict_queries_methods_bodies_and_versions_cannot_be_substituted(self):
        original=copy.deepcopy(self.report)
        changes=[('commissions_duplicate',lambda x:x['request']['url']['query'].pop()),
                 ('payment_recover',lambda x:x['request'].update(method='GET')),
                 ('payment_recover_injected_status',lambda x:x['request']['body'].update(raw='{}')),
                 ('withdraw',lambda x:x['request']['body'].update(raw='{"revision":2}')),
                 ('refund_accept',lambda x:x['request']['body'].update(raw='{"revision":3,"decision":"REJECT"}'))]
        for name,change in changes:
            self.report=copy.deepcopy(original);change(self.case(name))
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_original_write_keys_are_bound_and_reconcile_has_no_client_key(self):
        for name in ['payment_recover','refund_reconcile']:
            self.assertFalse(any(h['key']=='Idempotency-Key' for h in self.case(name)['request']['header']))
        self.case('withdraw')['request']['header'][1:1]=[{'key':'Idempotency-Key','value':'different'}]
        with self.assertRaises(ValueError):self.check()

    def test_environment_has_only_isolated_profile_supplier_and_no_background_recovery(self):
        env=dict(environment({'payment_secret':'synthetic-test-key'}))
        self.assertEqual('local-payment-test',env['SPRING_PROFILES_ACTIVE'])
        self.assertEqual('http://127.0.0.1:18091',env['PAYMENT_LOCAL_TEST_QUERY_URL'])
        self.assertEqual('false',env['PAYMENT_QUERY_RECOVERY_ENABLED'])
        self.assertEqual('false',env['REFUND_RECOVERY_ENABLED'])

    def test_supplier_statistics_are_exact_and_ownership_is_checked(self):
        backend=mock.Mock();backend.id='owned-backend'
        wanted={'payment_1_queries':1,'payment_4_queries':1,'fresh_refund_queries':1,'fresh_refund_posts':1,'failed_refund_queries':1,'failed_refund_posts':0,'invalid_submissions':0}
        with mock.patch('run_performance_baseline.Docker.run') as run:
            run.return_value.stdout=json.dumps(wanted).encode();self.assertTrue(verify_supplier(backend)['passed']);backend.guard.assert_called()
            for key in ['failed_refund_posts','fresh_refund_posts','payment_4_queries','invalid_submissions']:
                changed=dict(wanted);changed[key]+=1;run.return_value.stdout=json.dumps(changed).encode()
                with self.subTest(key=key),self.assertRaises(ValueError):verify_supplier(backend)

    def test_suite_requires_separate_database_and_ci_input_recording(self):
        for flag in ['--owner-cases','--reviews-cases','--coupon-cases','--record-ci-sources']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--finance-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
