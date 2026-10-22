"""Read-only rating quote evidence rejects identity, query and content substitution."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from rating_quotes_api_cases import ROOT,OUTPUT,MANIFEST,OPERATION_COUNT,CHECKS,TABLES,BASELINE,EXPECTED_COUNTS,assets,audit,environment,verify_database

ROLES = ('owner', 'other', 'merchant', 'other_merchant', 'technician')


class RatingQuotesEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection, self.manifest = assets(); (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='rating-quotes-evidence-', dir=ROOT/'.cache')
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

    def test_one_read_operation_covers_four_categories(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check();self.assertEqual((18,67,1),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertEqual({'normal','boundary','error','unauthorized'},set(result['coverage'][next(iter(result['coverage']))]))
        self.assertTrue(result['read_only_intents_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertTrue(all(x['request']['method']=='GET' for x in self.collection['item']))

    def test_role_substitution_rejected(self):
        original=copy.deepcopy(self.report)
        for name,role in [('quotes_merchant_denied','owner'),('quotes_technician_denied','other_merchant'),('quotes_anonymous','owner')]:
            self.report=copy.deepcopy(original)
            self.case(name)['request']['header']=[{'key':'Authorization','value':'Bearer '+self.tokens[role+'_token']}]+[
                h for h in self.case(name)['request']['header'] if h['key']!='Authorization']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_owner_scoped_read_cannot_become_store_scoped(self):
        original=copy.deepcopy(self.report)
        for name in ('quotes','quotes_other_owner'):
            self.report=copy.deepcopy(original)
            self.case(name)['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_query_and_path_substitution_rejected(self):
        original=copy.deepcopy(self.report)
        for name in ('quotes_page_zero','quotes_page_size','quotes_region_digits','quotes_unknown_query','quotes_duplicate_page'):
            self.report=copy.deepcopy(original);self.case(name)['request']['url']['query']=[]
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('quotes_zero_project')['request']['url']['path'][-1]='1'
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('quotes_inactive_project')['request']['url']['path'][-1]='1'
        with self.assertRaises(ValueError):self.check()

    def test_body_or_write_key_is_not_read_only_evidence(self):
        original=copy.deepcopy(self.report)
        self.case('quotes')['request']['body']={'mode':'raw','raw':'{}'}
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('quotes_region')['request']['header'].append({'key':'Idempotency-Key','value':'unexpected'})
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_skipped_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('quotes')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'synthetic'}]
            elif mode=='skipped':self.case('quotes_empty_page')['assertions'][0]['skipped']=True
            else:self.case('quotes')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_status_and_method_tampering_rejected(self):
        original=copy.deepcopy(self.report)
        self.case('quotes_missing_project')['response']['code']=200
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('quotes')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()

    def test_each_business_table_content_must_remain_unchanged(self):
        from unittest.mock import patch
        original=dict(BASELINE);self.addCleanup(lambda:(BASELINE.clear(),BASELINE.update(original)))
        BASELINE.clear();BASELINE.update({t:'synthetic-hash' for t in TABLES})
        with patch('rating_quotes_api_cases.snapshot',return_value=dict(BASELINE)),\
             patch('rating_quotes_api_cases.row_counts',return_value=dict(EXPECTED_COUNTS)):
            self.assertEqual(CHECKS,set(verify_database(None)['checks']))
        for table in TABLES:
            changed=dict(BASELINE);changed[table]='changed-content-same-row-count'
            with self.subTest(table=table),patch('rating_quotes_api_cases.snapshot',return_value=changed),\
                 patch('rating_quotes_api_cases.row_counts',return_value=dict(EXPECTED_COUNTS)),self.assertRaises(ValueError):verify_database(None)
        BASELINE.clear()
        with patch('rating_quotes_api_cases.snapshot',return_value={t:'synthetic-hash' for t in TABLES}),\
             patch('rating_quotes_api_cases.row_counts',return_value=dict(EXPECTED_COUNTS)),self.assertRaises(ValueError):verify_database(None)
        BASELINE.clear();BASELINE.update({t:'synthetic-hash' for t in TABLES})
        with patch('rating_quotes_api_cases.snapshot',return_value=dict(BASELINE)),\
             patch('rating_quotes_api_cases.row_counts',return_value=dict(EXPECTED_COUNTS,order=3)),self.assertRaises(ValueError):verify_database(None)
        BASELINE.clear()

    def test_no_production_feature_is_enabled(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))

    def test_requires_separate_database_and_build_record_mode(self):
        for flag in ('--plan-evidence-cases','--governance-cases','--relations-price-cases','--record-ci-sources'):
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--rating-quotes-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode)


if __name__=='__main__':unittest.main()
