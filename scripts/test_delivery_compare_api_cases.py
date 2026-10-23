"""Delivery comparison evidence must stay on one route, one identity and read-only."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from delivery_compare_api_cases import (ROOT,OUTPUT,MANIFEST,OPERATION_COUNT,CHECKS,TABLES,BASELINE,
                                        assets,audit,environment,verify_database)

ROLES = ('owner', 'other', 'merchant', 'other_merchant', 'technician')


class DeliveryCompareEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection, self.manifest = assets(); (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='delivery-compare-evidence-', dir=ROOT/'.cache')
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
                'header': headers},
                'response': {'code': case['status']}, 'assertions': [{'assertion': str(i)} for i in range(case['assertions'])]})
        self.report = {'run': {'executions': executions, 'failures': []}}

    def case(self, name):
        return next(x for x in self.report['run']['executions'] if x['item']['name'] == name)

    def check(self):
        self.path.write_text(json.dumps(self.report), encoding='utf-8')
        return audit(self.path, self.collection, self.manifest, self.tokens)

    def test_one_route_covers_four_categories(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check();self.assertEqual((16,60,1),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertEqual({'normal','boundary','error','unauthorized'},set(result['coverage'][next(iter(result['coverage']))]))
        self.assertTrue(result['read_only_evidence'])
        self.assertEqual('false',self.collection['variable'][1]['value'])

    def test_owner_and_merchant_reads_cannot_swap_identity(self):
        original=copy.deepcopy(self.report)
        for name,role in [('delivery_ready','merchant'),('delivery_ready_merchant','owner'),
                          ('delivery_technician_denied','owner'),('delivery_other_owner','merchant')]:
            self.report=copy.deepcopy(original)
            self.case(name)['request']['header']=[{'key':'Authorization','value':'Bearer '+self.tokens[role+'_token']}]+[
                h for h in self.case(name)['request']['header'] if h['key']!='Authorization']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_query_and_route_cannot_be_dropped(self):
        original=copy.deepcopy(self.report)
        self.case('delivery_query')['request']['url']['query']=[]
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('delivery_ready')['request']['url']['path'][-1]='compare-two'
        with self.assertRaises(ValueError):self.check()

    def test_missing_baseline_and_missing_order_keep_their_status(self):
        original=copy.deepcopy(self.report)
        self.case('delivery_missing_baseline')['response']['code']=200
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('delivery_missing_order')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_skipped_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('delivery_ready')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'synthetic'}]
            elif mode=='skipped':self.case('delivery_incomplete')['assertions'][0]['skipped']=True
            else:self.case('delivery_ready')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_method_tampering_rejected(self):
        self.case('delivery_ready')['request']['method']='GET'
        with self.assertRaises(ValueError):self.check()

    def test_each_business_table_must_stay_unchanged_and_delivery_rows_stay_empty(self):
        from unittest.mock import patch
        original=dict(BASELINE);self.addCleanup(lambda:(BASELINE.clear(),BASELINE.update(original)))
        BASELINE.clear();BASELINE.update({t:'synthetic-hash' for t in TABLES})
        with patch('delivery_compare_api_cases.snapshot',return_value=dict(BASELINE)),\
             patch('delivery_compare_api_cases.count_rows',return_value='0'):
            self.assertEqual(CHECKS,set(verify_database(None)['checks']))
        for table in TABLES:
            changed=dict(BASELINE);changed[table]='changed-content-same-row-count'
            with self.subTest(table=table),patch('delivery_compare_api_cases.snapshot',return_value=changed),\
                 patch('delivery_compare_api_cases.count_rows',return_value='0'),self.assertRaises(ValueError):verify_database(None)
        with patch('delivery_compare_api_cases.snapshot',return_value=dict(BASELINE)),\
             patch('delivery_compare_api_cases.count_rows',return_value='1'),self.assertRaises(ValueError):verify_database(None)
        BASELINE.clear()

    def test_no_production_feature_is_enabled(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))

    def test_requires_separate_database_and_build_record_mode(self):
        for flag in ('--rating-quotes-cases','--governance-cases','--relations-price-cases','--record-ci-sources'):
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--delivery-compare-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode)


    def test_native_acceptance_is_explicit_and_private_sessions_are_removed_on_failure(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from delivery_compare_api_cases import verify_ui,backend_port
        with patch.dict('os.environ',{'AUTOCARE_DELIVERY_NATIVE_QA':'0'}):
            self.assertFalse(verify_ui(None,None,None)['executed'])
        with patch.dict('os.environ',{'AUTOCARE_DELIVERY_NATIVE_QA':'1'}):
            with self.assertRaises(ValueError):verify_ui(SimpleNamespace(port=12345),self.tokens,Path(self.temp.name))
            with patch('delivery_compare_api_cases.subprocess.run',return_value=SimpleNamespace(returncode=1)):
                with self.assertRaises(ValueError):verify_ui(SimpleNamespace(port=18080),self.tokens,Path(self.temp.name))
            self.assertFalse((Path(self.temp.name)/'native-delivery-sessions.private.json').exists())


    def test_scan_mode_is_mutually_exclusive_and_requires_system_api_cleanup(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from delivery_compare_api_cases import native_mode,verify_ui
        with patch.dict('os.environ',{'AUTOCARE_DELIVERY_NATIVE_QA':'1','AUTOCARE_PICKUP_SCAN_NATIVE_QA':'1'}):
            with self.assertRaises(ValueError):native_mode()
        work=Path(self.temp.name)
        output=work/'native-pickup-scan.json'
        output.write_text(json.dumps({'passed':True,'session_cleared':True,'system_api_restored':False}),encoding='utf-8')
        with patch.dict('os.environ',{'AUTOCARE_DELIVERY_NATIVE_QA':'0','AUTOCARE_PICKUP_SCAN_NATIVE_QA':'1'}),patch('delivery_compare_api_cases.subprocess.run',return_value=SimpleNamespace(returncode=0)):
            self.assertEqual('pickup-scan',native_mode())
            with self.assertRaises(ValueError):verify_ui(SimpleNamespace(port=18080),self.tokens,work)
            self.assertFalse((work/'native-pickup-scan-sessions.private.json').exists())
            output.write_text(json.dumps({'passed':True,'session_cleared':True,'system_api_restored':True}),encoding='utf-8')
            self.assertTrue(verify_ui(SimpleNamespace(port=18080),self.tokens,work)['system_api_restored'])


if __name__=='__main__':
    unittest.main()
