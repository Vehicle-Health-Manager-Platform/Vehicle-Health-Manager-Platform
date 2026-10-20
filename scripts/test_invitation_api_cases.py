"""Invitation proof binds source/role, monthly policy revisions and original replay keys."""
import copy,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl
from invitation_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class InvitationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='invitation-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ['owner','other','merchant','other_merchant','technician','invite','reviewer']}
        self.tokens.update(month='2026-10',valid_from='2026-10-18T00:00:00Z',valid_to='2026-10-20T00:00:00Z',future_from='2026-10-19T20:00:00Z')
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

    def test_fresh_assets_four_categories_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')));self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        r=self.check();self.assertEqual((136,462,14),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['actual_body_and_idempotency_keys_verified']);self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_invite_operator_permission_not_reviewer(self):
        next(h for h in self.case('policy_create')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['reviewer_token']
        with self.assertRaises(ValueError):self.check()

    def test_foreign_shop_evidence_requires_actual_other_shop(self):
        next(h for h in self.case('other_shop_records_empty')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['merchant_token']
        with self.assertRaises(ValueError):self.check()

    def test_threshold_version_and_future_window_are_actual(self):
        original=copy.deepcopy(self.report)
        for name,key,value in [('policy_update','required_count',11),('stale_policy_update','expected_revision',2),('future_policy','valid_from',self.tokens['valid_from']),('client_eligibility','eligible',False)]:
            self.report=copy.deepcopy(original);r=self.case(name)['request'];b=json.loads(r['body']['raw']);b[key]=value;r['body']['raw']=json.dumps(b)
            with self.assertRaises(ValueError):self.check()

    def test_old_publish_receipt_requires_original_key(self):
        next(h for h in self.case('old_publish_receipt')['request']['header'] if h['key']=='Idempotency-Key')['value']='00000000-0000-4000-8000-000000000000'
        with self.assertRaises(ValueError):self.check()

    def test_query_method_and_conflict_status_cannot_be_substituted(self):
        original=copy.deepcopy(self.report);self.case('records_duplicate_page')['request']['url']['query'].pop()
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('policy_update')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('unique_active_policy')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_skipped_partial_proof_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','failed','skipped','partial']:
            self.report=copy.deepcopy(original);r=self.report['run']
            if kind=='missing':r['executions'].pop()
            elif kind=='duplicate':r['executions'][-1]=copy.deepcopy(r['executions'][0])
            elif kind=='failed':r['failures']=[{'error':'synthetic'}]
            elif kind=='skipped':r['executions'][0]['assertions'][0]['skipped']=True
            else:r['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_business_suites_cannot_share_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases','--exchange-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--invitation-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_real_identity_reward_earning_and_ai_disabled(self):
        e=dict(environment(self.tokens))
        for k in ['INVITE_VERIFIED_IDENTITY_ENABLED','INVITE_REWARD_ENABLED','POINTS_EARNING_ENABLED','POINTS_EXCHANGE_ENABLED','KNOWLEDGE_AI_ENABLED']:self.assertEqual('false',e[k])
        self.assertFalse(any('wechat-login' in i['path'] for i in self.manifest['items']))


if __name__=='__main__':unittest.main()
