"""Point reward proof binds actual identity, route, body, policy revision and replay keys."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit,parse_qsl
from points_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class PointsEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='points-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ['owner','other','merchant','points','reviewer']}
        self.tokens.update(point_day='2026-10-19',yesterday='2026-10-18',valid_from='2026-10-18T00:00:00Z',valid_to='2026-10-20T00:00:00Z',future_from='2026-10-19T20:00:00Z')
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
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_all_four_categories_and_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        r=self.check();self.assertEqual((116,397,8),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['actual_body_and_idempotency_keys_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_reviewer_cannot_substitute_points_operator(self):
        next(h for h in self.case('checkin_policy')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['reviewer_token']
        with self.assertRaises(ValueError):self.check()

    def test_unverified_identity_case_cannot_use_verified_owner(self):
        next(h for h in self.case('identity_unverified')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_body_must_preserve_day_policy_revision_and_client_claim(self):
        original=copy.deepcopy(self.report)
        for name,key,value in [('yesterday','day',self.tokens['point_day']),('wrong_revision','policy_revision',3),('replacement_no_second_reward','policy_id',1),('client_points','points',7)]:
            self.report=copy.deepcopy(original);r=self.case(name)['request'];b=json.loads(r['body']['raw']);b[key]=value;r['body']['raw']=json.dumps(b)
            with self.assertRaises(ValueError):self.check()

    def test_old_receipt_requires_original_idempotency_key(self):
        next(h for h in self.case('old_checkin_receipt')['request']['header'] if h['key']=='Idempotency-Key')['value']='00000000-0000-4000-8000-000000000000'
        with self.assertRaises(ValueError):self.check()

    def test_query_method_and_expected_status_are_actual(self):
        original=copy.deepcopy(self.report)
        self.case('flow_duplicate_page')['request']['url']['query'].pop()
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('publish_replacement')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('one_published_rule_per_task')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_skipped_failed_partial_proof_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','skipped','failed','partial']:
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='duplicate':run['executions'][-1]=copy.deepcopy(run['executions'][0])
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            elif kind=='partial':run['executions'][0]['assertions'].pop()
            else:run['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_fresh_database_required_for_all_other_suites(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--points-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_external_earning_and_spending_disabled(self):
        e=dict(environment(self.tokens))
        self.assertEqual('true',e['POINTS_EARNING_ENABLED'])
        for key in ['POINTS_AI_VERIFIED_ENABLED','POINTS_EXCHANGE_ENABLED','KNOWLEDGE_AI_ENABLED']:self.assertEqual('false',e[key])
        paths={i['path'] for i in self.manifest['items']}
        self.assertNotIn('/api/point/exchange',paths);self.assertNotIn('/api/auth/wechat-login',paths)


if __name__=='__main__':unittest.main()
