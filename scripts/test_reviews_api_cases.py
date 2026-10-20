"""Actual role, query, replay, moderation and default-off executions are required."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from reviews_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class ReviewsEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='reviews-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','other_merchant','technician','staff','reviewer','manager')}
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

    def test_current_assets_default_disabled(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        r=self.check();self.assertEqual((139,465,14),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_role_tokens_cannot_be_replaced(self):
        original=copy.deepcopy(self.report)
        for name in ['create_wrong_role','other_owner_order','admin_manager_denied','admin_queue','text_preview_wrong_role']:
            self.report=copy.deepcopy(original);self.case(name)['request']['header'][0]['value']='Bearer '+self.tokens['owner_token']
            with self.assertRaises(ValueError):self.check()

    def test_strict_query_is_checked_on_actual_request(self):
        original=copy.deepcopy(self.report)
        for name in ['first_pending_error','submit_query','publish_query','text_query','admin_queue_duplicate']:
            self.report=copy.deepcopy(original);self.case(name)['request']['url']['query'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_duplicate_and_trailing_json_are_not_replaced_with_empty_body(self):
        original=copy.deepcopy(self.report)
        for name in ['duplicate_rating','duplicate_content','trailing_json','large_json','publish_large_json','text_large_json']:
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']='{}'
            with self.assertRaises(ValueError):self.check()

    def test_submitted_text_must_be_the_previewed_redaction(self):
        preview=self.case('text_preview')
        preview['response']['stream']=json.dumps({'data':{'public_text':'师傅讲解清楚,电话[已隐藏]。'}},ensure_ascii=False)
        clean='师傅讲解清楚,电话[已隐藏]。';dirty='师傅讲解清楚,电话13800001234。'
        def substitute(old,new):
            for case in self.report['run']['executions']:
                body=(case['request'].get('body') or {}).get('raw')
                if isinstance(body,str) and old in body:case['request']['body']['raw']=body.replace(old,new)
        substitute('{{clean_text}}',dirty)
        with self.assertRaises(ValueError):self.check()
        substitute(dirty,clean)
        self.check()

    def test_every_idempotency_key_is_actual(self):
        original=copy.deepcopy(self.report)
        for name in ['create','create_replay','publish','publish_replay','text_submit','admin_approve','consent','unpublish']:
            self.report=copy.deepcopy(original)
            h=next(h for h in self.case(name)['request']['header'] if h['key']=='Idempotency-Key');h['value']='00000000-0000-4000-8000-000000000000'
            with self.assertRaises(ValueError):self.check()

    def test_privilege_publication_and_eligibility_status_cannot_be_forged(self):
        original=copy.deepcopy(self.report)
        for name,code in [('admin_manager_denied',200),('other_owner_order',200),('publication_test_order',403),('self_review_other_owner',404)]:
            if name not in [x['item']['name'] for x in self.report['run']['executions']]:continue
            self.report=copy.deepcopy(original);self.case(name)['response']['code']=code
            with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_failed_skipped_partial_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ['missing','duplicate','failed','skipped','partial']:
            self.report=copy.deepcopy(original);r=self.report['run']
            if kind=='missing':r['executions'].pop()
            elif kind=='duplicate':r['executions'][-1]=copy.deepcopy(r['executions'][0])
            elif kind=='failed':r['failures']=[{'error':'synthetic'}]
            elif kind=='skipped':self.case('text_history_kept')['assertions'][-1]['skipped']=True
            else:r['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):self.check()

    def test_suite_cannot_share_database_with_any_other_mode(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases','--export-cases','--points-cases','--exchange-cases','--invitation-cases','--diagnosis-cases','--discovery-cases','--quotes-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--reviews-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_public_switches_are_enabled_only_inside_isolation(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases','REVIEW_TEXT_PUBLICATION_ENABLED':'true','REVIEW_SUMMARY_ENABLED':'true'},dict(environment(self.tokens)))
        source=(ROOT/'backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java').read_text(encoding='utf-8')
        # 生产默认必须仍为关闭：两个 bean 的 @Value 回退值都必须是 false。
        self.assertIn('${REVIEW_SUMMARY_ENABLED:false}',source)
        self.assertEqual(2,source.count('${REVIEW_TEXT_PUBLICATION_ENABLED:false}'))

    def test_public_projection_keeps_only_whitelisted_fields(self):
        ratings=next(x for x in self.collection['item'] if x['name']=='ratings')
        script=json.dumps(ratings,ensure_ascii=False)
        self.assertIn('submitted_date',script)
        for leaked in ['user_id','review_id','order_id','merchant_id','content']:
            self.assertNotIn('d.items[0].'+leaked,script)


if __name__=='__main__':unittest.main()
