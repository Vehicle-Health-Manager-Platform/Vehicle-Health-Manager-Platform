import copy
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from owner_api_cases import ROOT, OUTPUT, MANIFEST, assets
from run_api_acceptance import audit_owner_cases


class OwnerCasesTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        cache=ROOT/'.cache';cache.mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='owner-api-evidence-',dir=cache)
        self.addCleanup(self.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant')}
        definitions={x['name']:x for x in self.manifest['items']}
        executions=[]
        for item in self.collection['item']:
            case=definitions[item['name']]
            url=urlsplit(item['request']['url'].removeprefix('{{base_url}}'))
            executions.append({'item':{'name':item['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},
                'header':[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]},
                'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'failures':[],'executions':executions}}

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT/'.cache').resolve()) and path.name.startswith('owner-api-evidence-')
        self.temp.cleanup()

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit_owner_cases(self.path,self.collection,self.manifest,self.tokens)

    def test_scope_generated_assets_and_closed_default(self):
        self.assertEqual(61,self.manifest['cases'])
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        text='\n'.join(self.collection['event'][0]['script']['exec'])
        self.assertIn('isolated_owner_cases',text);self.assertIn('skipRequest',text)
        self.assertNotIn('synthetic-owner',json.dumps(self.collection))

    def test_complete_all_four_categories_of_eight_operations(self):
        result=self.check()
        self.assertEqual(61,result['requests']);self.assertEqual(204,result['assertions'])
        self.assertEqual(8,len(result['coverage']))

    def test_missing_duplicate_or_failed_case_cannot_pass(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','failed'):
            with self.subTest(kind=kind):
                self.report=copy.deepcopy(original)
                if kind=='missing':self.report['run']['executions'].pop()
                elif kind=='duplicate':self.report['run']['executions'][-1]=self.report['run']['executions'][0]
                else:self.report['run']['failures']=[{'error':'synthetic failure'}]
                with self.assertRaises(ValueError):self.check()

    def test_wrong_identity_cannot_impersonate_cross_owner_case(self):
        case=next(x for x in self.report['run']['executions'] if x['item']['name']=='archives_other_owner')
        case['request']['header'][0]['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_wrong_route_query_or_method_rejected(self):
        original=copy.deepcopy(self.report)
        for field in ('method','path','query'):
            with self.subTest(field=field):
                self.report=copy.deepcopy(original);r=self.report['run']['executions'][0]['request']
                if field=='method':r['method']='POST'
                elif field=='path':r['url']['path']=['api','other']
                else:r['url']['query']=[{'key':'user_id','value':'2'}]
                with self.assertRaises(ValueError):self.check()

    def test_wrong_status_skipped_or_missing_business_assertion_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('status','skipped','missing'):
            with self.subTest(kind=kind):
                self.report=copy.deepcopy(original);case=self.report['run']['executions'][0]
                if kind=='status':case['response']['code']=500
                elif kind=='skipped':case['assertions'][0]['skipped']=True
                else:case['assertions'].pop()
                with self.assertRaises(ValueError):self.check()

    def test_scope_cannot_be_satisfied_by_dropping_a_case_category(self):
        for item in self.manifest['items']:
            if item['path']=='/api/vehicle/add':item['kind']='normal'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
