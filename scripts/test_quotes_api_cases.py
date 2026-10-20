"""Actual role, query, source and replay executions are required for health evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from quotes_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class QuotesEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='quotes-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician','staff')}
        executions=[]
        requests={item['name']:item['request'] for item in self.collection['item']}
        for case in self.manifest['items']:
            url=urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            headers+=copy.deepcopy(requests[case['name']].get('header',[]))
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers,**({'body':copy.deepcopy(requests[case['name']]['body'])} if 'body' in requests[case['name']] else {})},
                'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_current_assets_default_disabled_four_kinds(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        r=self.check();self.assertEqual((105,361,4),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_staff_and_other_shop_tokens_cannot_be_replaced(self):
        original=copy.deepcopy(self.report)
        for name in ['staff_write','other_store_final']:
            self.report=copy.deepcopy(original);self.case(name)['request']['header'][0]['value']='Bearer '+self.tokens['merchant_token']
            with self.assertRaises(ValueError):self.check()

    def test_selectors_are_checked_on_actual_query(self):
        original=copy.deepcopy(self.report)
        for name in ['standards_query_1','own_query_1','save_query']:
            self.report=copy.deepcopy(original);self.case(name)['request']['url']['query'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_duplicate_and_trailing_json_are_not_replaced_with_empty_body(self):
        original=copy.deepcopy(self.report)
        for name in ['duplicate_price','duplicate_status','trailing_json','large_json']:
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']='{}'
            with self.assertRaises(ValueError):self.check()

    def test_failed_and_old_receipt_keys_are_actual(self):
        original=copy.deepcopy(self.report)
        for name in ['audit_failure','retry_failed_key','old_price_receipt','old_shelf_on_receipt']:
            self.report=copy.deepcopy(original)
            h=next(h for h in self.case(name)['request']['header'] if h['key']=='Idempotency-Key');h['value']='00000000-0000-4000-8000-000000000000'
            with self.assertRaises(ValueError):self.check()

    def test_audit_failure_or_inactive_source_status_cannot_be_forged(self):
        original=copy.deepcopy(self.report)
        for name in ['audit_failure','inactive_cannot_reprice','deleted_cannot_shelve']:
            self.report=copy.deepcopy(original);self.case(name)['response']['code']=200
            with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_skipped_partial_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','failed','skipped','partial']:
            self.report=copy.deepcopy(original);r=self.report['run']
            if kind=='missing':r['executions'].pop()
            elif kind=='duplicate':r['executions'][-1]=copy.deepcopy(r['executions'][0])
            elif kind=='failed':r['failures']=[{'error':'synthetic'}]
            elif kind=='skipped':self.case('final_own')['assertions'][-1]['skipped']=True
            else:r['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_suite_cannot_share_database_with_any_other_mode(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases','--exchange-cases','--invitation-cases','--diagnosis-cases','--discovery-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--quotes-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_formal_suppliers_not_enabled(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))

if __name__=='__main__':unittest.main()
