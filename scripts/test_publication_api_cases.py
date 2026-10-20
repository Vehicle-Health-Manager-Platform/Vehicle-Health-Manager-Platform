"""Acceptance evidence must bind identity and actual approved public UUID."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from publication_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class PublicationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='publication-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','manager','handler','reviewer')}
        self.reference='11111111-1111-4111-8111-111111111111'
        definitions={x['name']:x for x in self.manifest['items']};executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']]
            url=urlsplit(item['request']['url'].removeprefix('{{base_url}}').replace('{{public_reference}}',self.reference))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            response={'code':case['status']}
            if case['name']=='public_list':
                response['stream']={'type':'Buffer','data':list(json.dumps({'code':0,'data':{'items':[{'public_id':self.reference}]}}).encode())}
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
        self.assertEqual((134,456,14),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['detail_reference_bound_to_actual_public_list'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_review_permission_cannot_be_substituted(self):
        self.case('manager_cannot_review')['request']['header'][0]['value']='Bearer '+self.tokens['reviewer_token']
        with self.assertRaises(ValueError):self.check()

    def test_detail_must_target_real_public_list_reference(self):
        self.case('old_uuid_never_revived')['request']['url']['path'][-1]='22222222-2222-4222-8222-222222222222'
        with self.assertRaises(ValueError):self.check()

    def test_replaced_or_invalid_public_list_reference_rejected(self):
        for reference in ('22222222-2222-4222-8222-222222222222','invalid'):
            self.case('public_list')['response']['stream']['data']=list(json.dumps({'code':0,'data':{'items':[{'public_id':reference}]}}).encode())
            with self.assertRaises(ValueError):self.check()

    def test_unapproved_empty_or_duplicate_list_evidence_rejected(self):
        original=copy.deepcopy(self.report)
        for body in ({'code':1,'data':{'items':[{'public_id':self.reference}]}},{'code':0,'data':{'items':[]}}):
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
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--publication-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_only_isolated_publication_enabled(self):
        config=dict(environment(self.tokens))
        self.assertEqual('true',config['COMMUNITY_PUBLICATION_ENABLED'])
        self.assertEqual('false',config['KNOWLEDGE_AI_ENABLED'])
        names={x['name'] for x in self.manifest['items']}
        self.assertTrue({'unknown_source_denied','preview_client_identity','old_uuid_never_revived','public_scope_frozen'}<=names)


if __name__=='__main__':unittest.main()
