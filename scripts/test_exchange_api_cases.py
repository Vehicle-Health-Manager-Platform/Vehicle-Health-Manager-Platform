"""Exchange proof must preserve source versions, identity, immutable receipts and actual bodies."""
import copy,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl
from exchange_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class ExchangeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='exchange-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ['owner','other','merchant','points','coupon']}
        self.tokens.update(point_day='2026-10-19',valid_from='2026-10-18T00:00:00Z',valid_to='2026-10-20T00:00:00Z')
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            planned=requests[case['name']];url=urlsplit(planned['url'].removeprefix('{{base_url}}'))
            headers=copy.deepcopy(planned.get('header',[]))
            if case['role']!='anonymous':headers.append({'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']})
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers}
            if 'body' in planned:
                raw=planned['body']['raw']
                for key,value in self.tokens.items():raw=raw.replace('{{'+key+'}}',value)
                request['body']={'raw':raw}
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(e for e in self.report['run']['executions'] if e['item']['name']==name)
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8');return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_all_categories_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        r=self.check();self.assertEqual((196,668,17),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['actual_body_and_idempotency_keys_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_coupon_permission_cannot_substitute_points_permission(self):
        next(h for h in self.case('cash_catalog')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['coupon_token']
        with self.assertRaises(ValueError):self.check()

    def test_unverified_user_must_not_use_funded_owner(self):
        next(h for h in self.case('unverified_exchange')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_source_catalog_and_pool_revisions_are_actual(self):
        original=copy.deepcopy(self.report)
        for name,key,value in [('wrong_catalog_revision','catalog_revision',3),('invalid_pool_revision','pool_revision',1),('replacement_lifetime_limit','catalog_id',1),('cash_update','points_cost',8)]:
            self.report=copy.deepcopy(original);r=self.case(name)['request'];b=json.loads(r['body']['raw']);b[key]=value;r['body']['raw']=json.dumps(b)
            with self.assertRaises(ValueError):self.check()

    def test_stopped_receipt_requires_original_key(self):
        next(h for h in self.case('service_receipt_after_source_stop')['request']['header'] if h['key']=='Idempotency-Key')['value']='00000000-0000-4000-8000-000000000000'
        with self.assertRaises(ValueError):self.check()

    def test_method_query_status_cannot_be_substituted(self):
        original=copy.deepcopy(self.report);self.case('offers_duplicate')['request']['url']['query'].pop()
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('cash_update')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('replacement_lifetime_limit')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_skipped_failed_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','skipped','failed','partial']:
            self.report=copy.deepcopy(original);r=self.report['run']
            if kind=='missing':r['executions'].pop()
            elif kind=='duplicate':r['executions'][-1]=copy.deepcopy(r['executions'][0])
            elif kind=='skipped':r['executions'][0]['assertions'][0]['skipped']=True
            elif kind=='partial':r['executions'][0]['assertions'].pop()
            else:r['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_each_business_suite_requires_new_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--exchange-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_only_isolated_earning_and_exchange_enabled(self):
        e=dict(environment(self.tokens));self.assertEqual('true',e['POINTS_EARNING_ENABLED']);self.assertEqual('true',e['POINTS_EXCHANGE_ENABLED'])
        self.assertEqual('false',e['POINTS_AI_VERIFIED_ENABLED']);self.assertEqual('false',e['KNOWLEDGE_AI_ENABLED'])
        self.assertFalse(any('/callback/' in i['path'] for i in self.manifest['items']))


if __name__=='__main__':unittest.main()
