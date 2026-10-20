"""Acceptance evidence must bind identity and actual approved public UUID."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.parse import urlsplit, parse_qsl
from experience_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment, prepare


class ExperienceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='experience-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','manager','handler','reviewer')}
        self.reference='11111111-1111-4111-8111-111111111111'
        definitions={x['name']:x for x in self.manifest['items']};executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']]
            url=urlsplit(item['request']['url'].removeprefix('{{base_url}}').replace('{{experience_reference}}',self.reference))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            response={'code':case['status']}
            if case['name']=='public_list':
                response['stream']={'type':'Buffer','data':list(json.dumps({'code':0,'data':{'items':[{'experience_id':self.reference}]}}).encode())}
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers},
                'response':response,'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_complete_scope_and_disabled_default(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((131,448,14),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['detail_bound_to_actual_experience_list'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_review_permission_cannot_be_substituted(self):
        self.case('curator_not_reviewer')['request']['header'][0]['value']='Bearer '+self.tokens['reviewer_token']
        with self.assertRaises(ValueError):self.check()

    def test_detail_must_target_real_public_list_reference(self):
        self.case('old_uuid_never_revives')['request']['url']['path'][-1]='22222222-2222-4222-8222-222222222222'
        with self.assertRaises(ValueError):self.check()

    def test_replaced_or_invalid_public_list_reference_rejected(self):
        for reference in ('22222222-2222-4222-8222-222222222222','invalid'):
            self.case('public_list')['response']['stream']['data']=list(json.dumps({'code':0,'data':{'items':[{'experience_id':reference}]}}).encode())
            with self.assertRaises(ValueError):self.check()

    def test_unapproved_empty_or_duplicate_list_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for body in ({'code':1,'data':{'items':[{'experience_id':self.reference}]}},{'code':0,'data':{'items':[]}}):
            self.report=copy.deepcopy(original)
            self.case('public_list')['response']['stream']['data']=list(json.dumps(body).encode())
            with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original)
        self.report['run']['executions'].append(copy.deepcopy(self.case('public_list')))
        with self.assertRaises(ValueError):self.check()

    def test_missing_skipped_or_failed_execution_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','skipped','failed'):
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            else:run['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_suite_requires_fresh_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--experience-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_only_isolated_experience_enabled(self):
        config=dict(environment(self.tokens))
        self.assertEqual('true',config['EXPERIENCE_PUBLICATION_ENABLED'])
        self.assertEqual('false',config['KNOWLEDGE_AI_ENABLED'])
        names={x['name'] for x in self.manifest['items']}
        self.assertTrue({'test_card_cannot_consent','curation_not_rebound','old_uuid_never_revives','old_consent_key_rejected'}<=names)

    def test_consumer_readiness_requires_both_real_and_test_source_cards(self):
        mysql=Mock();mysql.text.side_effect=['0','1','2','1:1:0,2:2:1']
        with patch('experience_api_cases.time.sleep') as pause:
            prepare(mysql)
        self.assertEqual(2,pause.call_count)
        mysql.text.side_effect=['2','1:1:1,2:2:0']
        with self.assertRaises(ValueError):prepare(mysql)

    def test_consumer_timeout_does_not_accept_partial_generation(self):
        mysql=Mock();mysql.text.return_value='1'
        with patch('experience_api_cases.time.monotonic',side_effect=[0,31]):
            with self.assertRaises(ValueError):prepare(mysql)


if __name__=='__main__':unittest.main()
