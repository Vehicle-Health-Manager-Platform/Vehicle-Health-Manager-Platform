"""Diagnosis proof binds source/role, monthly policy revisions and original replay keys."""
import copy,json,subprocess,sys,tempfile,unittest
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl
from diagnosis_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment


class DiagnosisEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='diagnosis-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ['owner','other','merchant','other_merchant','technician','manager','handler','second_handler','reviewer']}
        self.tokens.update(month='2026-10',valid_from='2026-10-18T00:00:00Z',valid_to='2026-10-20T00:00:00Z',future_from='2026-10-19T20:00:00Z')
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        self.values={'owner_notice':'101','manager_notice':'201'}
        for case in self.manifest['items']:
            planned=requests[case['name']];resolved=planned['url'].removeprefix('{{base_url}}')
            for key,value in self.values.items():resolved=resolved.replace('{{'+key+'}}',value)
            url=urlsplit(resolved)
            headers=copy.deepcopy(planned.get('header',[]))
            if case['role']!='anonymous':headers.append({'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']})
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers}
            if 'body' in planned:
                raw=planned['body']['raw']
                for key,value in self.tokens.items():raw=raw.replace('{{'+key+'}}',value)
                request['body']={'raw':raw}
            response={'code':case['status']}
            if case['name'] in ['owner_notices','manager_notices']:
                start=101 if case['name']=='owner_notices' else 201
                payload={'data':{'notifications':{'items':[{'notification_id':i} for i in range(start,start+9)]}}}
                response['stream']={'data':list(json.dumps(payload).encode())}
            executions.append({'item':{'name':case['name']},'request':request,'response':response,'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(e for e in self.report['run']['executions'] if e['item']['name']==name)
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8');return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_four_categories_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')));self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        r=self.check();self.assertEqual((169,566,20),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['actual_body_and_idempotency_keys_verified']);self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_manager_cannot_be_substituted_for_assigned_handler(self):
        next(h for h in self.case('reply_first')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['manager_token']
        with self.assertRaises(ValueError):self.check()

    def test_foreign_owner_evidence_requires_other_owner(self):
        next(h for h in self.case('foreign_notice_read')['request']['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_consent_revision_operator_and_message_are_actual(self):
        original=copy.deepcopy(self.report)
        for name,key,value in [('case_create','consent',False),('stale_assignment','expected_revision',2),('transfer','operator_id',2),('reply_second','message','changed')]:
            self.report=copy.deepcopy(original);r=self.case(name)['request'];b=json.loads(r['body']['raw']);b[key]=value;r['body']['raw']=json.dumps(b)
            with self.assertRaises(ValueError):self.check()

    def test_old_receipt_requires_original_key(self):
        next(h for h in self.case('former_handler_old_receipt_denied')['request']['header'] if h['key']=='Idempotency-Key')['value']='00000000-0000-4000-8000-000000000000'
        with self.assertRaises(ValueError):self.check()

    def test_notification_paths_bind_to_recipient_list_response(self):
        original=copy.deepcopy(self.report)
        self.case('owner_read')['request']['url']['path'][-2]='201'
        with self.assertRaises(ValueError):self.check()
        for kind in ['missing','duplicate','invalid','alias']:
            self.report=copy.deepcopy(original);response=self.case('owner_notices')['response']
            if kind=='missing':response.pop('stream')
            else:
                ids=[101]*9 if kind=='duplicate' else ([0]+list(range(102,110)) if kind=='invalid' else list(range(201,210)))
                response['stream']={'data':list(json.dumps({'data':{'notifications':{'items':[{'notification_id':i} for i in ids]}}}).encode())}
            with self.assertRaises(ValueError):self.check()

    def test_query_method_and_conflict_status_cannot_be_substituted(self):
        original=copy.deepcopy(self.report);self.case('queue_duplicate')['request']['url']['query'].pop()
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('policy_update')['request']['method']='POST'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('closed_reply')['response']['code']=200
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
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases','--exchange-cases','--invitation-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--diagnosis-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_real_identity_reward_earning_and_ai_disabled(self):
        e=dict(environment(self.tokens))
        for k in ['INVITE_VERIFIED_IDENTITY_ENABLED','INVITE_REWARD_ENABLED','POINTS_EARNING_ENABLED','POINTS_EXCHANGE_ENABLED','KNOWLEDGE_AI_ENABLED']:self.assertEqual('false',e[k])
        self.assertFalse(any('wechat-login' in i['path'] for i in self.manifest['items']))


if __name__=='__main__':unittest.main()
