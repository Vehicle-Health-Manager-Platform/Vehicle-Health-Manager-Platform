"""Actual role, query, source and replay executions are required for health evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from discovery_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class DiscoveryEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='discovery-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician','staff')}
        executions=[]
        requests={item['name']:item['request'] for item in self.collection['item']}
        for case in self.manifest['items']:
            url=urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers,**({'body':copy.deepcopy(requests[case['name']]['body'])} if 'body' in requests[case['name']] else {})},
                'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_assets_are_current_disabled_and_cover_nine_operations(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        r=self.check();self.assertEqual((105,349,9),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_staff_read_and_cross_shop_identity_are_actual(self):
        original=copy.deepcopy(self.report)
        for name in ['staff_location_read','other_store_before']:
            self.report=copy.deepcopy(original);self.case(name)['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
            with self.assertRaises(ValueError):self.check()

    def test_unknown_or_duplicate_selectors_cannot_be_removed(self):
        original=copy.deepcopy(self.report)
        for name in ['projects_query_0','projects_query_1','quotes_query_2','storefront_query_1']:
            self.report=copy.deepcopy(original);self.case(name)['request']['url']['query'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_coordinate_bodies_must_match_actual_execution(self):
        original=copy.deepcopy(self.report)
        for name in ['nearby','distance','location_set','location_clear']:
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']='{}'
            with self.assertRaises(ValueError):self.check()

    def test_scope_privacy_and_old_receipt_assertions_cannot_be_skipped(self):
        original=copy.deepcopy(self.report)
        for name in ['storefront','nearby','old_location_receipt','location_stays_clear','own_store_unchanged']:
            self.report=copy.deepcopy(original);self.case(name)['assertions'][-1]['skipped']=True
            with self.assertRaises(ValueError):self.check()

    def test_method_or_status_change_rejected(self):
        original=copy.deepcopy(self.report)
        self.case('location_set')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('staff_location_write')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_partial_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','failed','partial']:
            self.report=copy.deepcopy(original);r=self.report['run']
            if kind=='missing':r['executions'].pop()
            elif kind=='duplicate':r['executions'][-1]=copy.deepcopy(r['executions'][0])
            elif kind=='failed':r['failures']=[{'error':'synthetic'}]
            else:r['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_suites_cannot_share_a_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases','--exchange-cases','--invitation-cases','--diagnosis-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--discovery-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_no_supplier_features_enabled(self):
        config=dict(environment(self.tokens));self.assertEqual({'MERCHANT_STAFF_ENABLED':'true','WECHAT_APP_ID':'fulfillment-cases'},config)
        self.assertTrue(all('callback' not in c['path'] and '/auth/' not in c['path'] for c in self.manifest['items']))

if __name__=='__main__':unittest.main()
