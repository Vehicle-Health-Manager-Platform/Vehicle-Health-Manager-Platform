"""Coupon evidence must preserve frozen terms, revisions, identities and write keys."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from coupon_catalog_api_cases import ROOT,OUTPUT,MANIFEST,OPERATION_COUNT,CHECKS,assets,audit,environment

ROLES = ('owner', 'other', 'merchant', 'other_merchant', 'technician', 'catalog', 'coupon', 'plain')



class CouponCatalogEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection, self.manifest = assets(); (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='coupon-catalog-evidence-', dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup); self.path = Path(self.temp.name)/'report.json'
        self.tokens = {role+'_token': 'synthetic-'+role for role in ROLES}
        executions = []
        requests = {item['name']: item['request'] for item in self.collection['item']}
        for case in self.manifest['items']:
            url = urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers = [] if case['role'] == 'anonymous' else [{'key': 'Authorization', 'value': 'Bearer '+self.tokens[case['role']+'_token']}]
            headers += copy.deepcopy(requests[case['name']].get('header', []))
            executions.append({'item': {'name': case['name']}, 'request': {'method': case['method'],
                'url': {'path': url.path.strip('/').split('/'), 'query': [{'key': k, 'value': v} for k, v in parse_qsl(url.query, keep_blank_values=True)]},
                'header': headers, **({'body': copy.deepcopy(requests[case['name']]['body'])} if 'body' in requests[case['name']] else {})},
                'response': {'code': case['status']}, 'assertions': [{'assertion': str(i)} for i in range(case['assertions'])]})
        self.report = {'run': {'executions': executions, 'failures': []}}

    def case(self, name):
        return next(x for x in self.report['run']['executions'] if x['item']['name'] == name)

    def check(self):
        self.path.write_text(json.dumps(self.report), encoding='utf-8')
        return audit(self.path, self.collection, self.manifest, self.tokens)

    def test_scoped_assets_and_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        r=self.check();self.assertEqual((96,354,9),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['coupon_catalog_intents_verified']);self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])
    def test_terms_versions_selection_and_receipts_bound_to_original_body(self):
        original=copy.deepcopy(self.report)
        for name in ('catalog_draft','catalog_edit','edit_changed_intent','catalog_publish','catalog_pool','pool_4','catalog_claim','withdraw_service_pool','old_pool_receipt','stop_service'):
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']='{}'
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_replay_write_keys_cannot_be_substituted(self):
        original=copy.deepcopy(self.report)
        for name in ('catalog_edit_replay','edit_old_receipt','old_pool_receipt','catalog_claim_replay'):
            self.report=copy.deepcopy(original)
            next(h for h in self.case(name)['request']['header'] if h['key']=='Idempotency-Key')['value']='00000000-0000-4000-8000-000000000000'
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_reads_cannot_carry_body_or_write_key(self):
        original=copy.deepcopy(self.report)
        self.case('offers_catalog')['request']['body']={'mode':'raw','raw':'{}'}
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('merchant_catalog')['request']['header'].append({'key':'Idempotency-Key','value':'unexpected'})
        with self.assertRaises(ValueError):self.check()
    def test_cross_shop_and_coupon_permissions_use_actual_identities(self):
        original=copy.deepcopy(self.report)
        for name,role in [('edit_foreign','merchant'),('merchant_other_store','merchant'),('pool_catalog_denied','coupon'),('pool_plain_denied','coupon'),('claim_other_owner','owner')]:
            self.report=copy.deepcopy(original)
            next(h for h in self.case(name)['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens[role+'_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_query_and_scope_cannot_change(self):
        original=copy.deepcopy(self.report)
        for name in ('offers_duplicate','edit_query','offers_other_shop','merchant_page_size'):
            self.report=copy.deepcopy(original);self.case(name)['request']['url']['query']=[]
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('edit_foreign')['request']['url']['path'][-1]='2'
        with self.assertRaises(ValueError):self.check()
    def test_missing_duplicate_failed_skipped_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('catalog_edit')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'synthetic'}]
            elif mode=='skipped':self.case('offers_catalog')['assertions'][0]['skipped']=True
            else:self.case('pool_catalog')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()
    def test_published_edit_and_lifetime_rejections_cannot_be_forged(self):
        original=copy.deepcopy(self.report)
        for name in ('edit_published','claim_lifetime_limit','claim_exhausted'):
            self.report=copy.deepcopy(original);self.case(name)['response']['code']=200
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_no_external_or_production_switches(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))
    def test_every_other_mode_requires_separate_database(self):
        for flag in ('--coupon-cases','--relations-price-cases','--plan-evidence-cases','--governance-cases','--record-ci-sources'):
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--coupon-catalog-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode)

if __name__=='__main__':unittest.main()
