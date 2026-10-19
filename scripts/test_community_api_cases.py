"""Private scopes and dynamic public references require actual identity and search evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from community_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class CommunityEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='community-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','manager','handler','second_handler','knowledge','reviewer')}
        self.reference='11111111-1111-4111-8111-111111111111'
        definitions={x['name']:x for x in self.manifest['items']};executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']]
            url=urlsplit(item['request']['url'].removeprefix('{{base_url}}').replace('{{knowledge_reference}}',self.reference))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            response={'code':case['status']}
            if case['name']=='knowledge_search':
                body={'code':0,'data':{'method':'KEYWORD','items':[{'reference':self.reference}]}}
                response['stream']={'type':'Buffer','data':list(json.dumps(body).encode())}
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers},
                'response':response,'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT/'.cache').resolve()) and path.name.startswith('community-evidence-')
        self.temp.cleanup()

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def search_body(self,body):self.case('knowledge_search')['response']['stream']['data']=list(json.dumps(body).encode())

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_scope_default_closed_and_complete_evidence(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((176,590,23),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['detail_reference_bound_to_actual_search'])
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_private_denial_case_cannot_substitute_different_operator(self):
        self.case('knowledge_cannot_read_private_question')['request']['header'][0]['value']='Bearer '+self.tokens['manager_token']
        with self.assertRaises(ValueError):self.check()

    def test_detail_must_target_the_actual_search_reference(self):
        self.case('knowledge_detail')['request']['url']['path'][-1]='22222222-2222-4222-8222-222222222222'
        with self.assertRaises(ValueError):self.check()

    def test_changed_search_reference_cannot_authorize_original_detail(self):
        self.search_body({'code':0,'data':{'method':'KEYWORD','items':[{'reference':'22222222-2222-4222-8222-222222222222'}]}})
        with self.assertRaises(ValueError):self.check()

    def test_non_keyword_search_does_not_prove_implemented_retrieval(self):
        self.search_body({'code':0,'data':{'method':'VECTOR','items':[{'reference':self.reference}]}})
        with self.assertRaises(ValueError):self.check()

    def test_invalid_reference_rejected(self):
        self.search_body({'code':0,'data':{'method':'KEYWORD','items':[{'reference':'not-a-uuid'}]}})
        with self.assertRaises(ValueError):self.check()

    def test_empty_query_is_preserved_and_cannot_be_silently_dropped(self):
        self.check()
        request=self.case('knowledge_search_error')['request']
        request['url']['query']=[x for x in request['url']['query'] if x['key']!='q']
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_skipped_or_failed_execution_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','skipped','failed'):
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='duplicate':run['executions'][-1]=run['executions'][0]
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            else:run['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_community_suite_requires_its_own_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--community-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_ai_and_publication_remain_explicitly_disabled(self):
        config=dict(environment(self.tokens))
        self.assertEqual('true',config['KNOWLEDGE_ENABLED'])
        self.assertEqual('false',config['KNOWLEDGE_AI_ENABLED'])
        self.assertEqual('false',config['COMMUNITY_PUBLICATION_ENABLED'])
        self.assertNotIn('/api/knowledge/answer',{x['path'] for x in self.manifest['items']})


if __name__=='__main__':unittest.main()
