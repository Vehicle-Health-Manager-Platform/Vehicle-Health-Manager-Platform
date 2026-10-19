"""Actual role, query, source and replay executions are required for health evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from health_api_cases import ROOT, OUTPUT, MANIFEST, assets, audit, environment


class HealthEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='health-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ('owner','other','merchant','catalog','reviewer')}
        executions=[]
        requests={item['name']:item['request'] for item in self.collection['item']}
        for case in self.manifest['items']:
            url=urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},'header':headers},
                'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_all_four_kinds_and_disabled_default(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check()
        self.assertEqual((199,668,14),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertEqual('false',self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_catalog_permission_cannot_use_reviewer_token(self):
        self.case('policy_set')['request']['header'][0]['value']='Bearer '+self.tokens['reviewer_token']
        with self.assertRaises(ValueError):self.check()

    def test_foreign_vehicle_proof_requires_other_owner_token(self):
        self.case('foreign_vehicle_unchanged')['request']['header'][0]['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_history_cursor_cannot_be_silently_dropped(self):
        request=self.case('history_older_page')['request']
        request['url']['query']=[x for x in request['url']['query'] if x['key']!='before_revision']
        with self.assertRaises(ValueError):self.check()

    def test_source_replacement_must_target_actual_source(self):
        self.case('replace_source')['request']['url']['path'][2]='1'
        with self.assertRaises(ValueError):self.check()

    def test_write_method_and_expected_conflict_cannot_be_substituted(self):
        original=copy.deepcopy(self.report)
        self.case('confirm')['request']['method']='GET'
        with self.assertRaises(ValueError):self.check()
        self.report=original;self.case('double_clear_rejected')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_skipped_failed_or_partial_assertions_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','duplicate','skipped','failed','partial'):
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='duplicate':run['executions'][-1]=copy.deepcopy(run['executions'][0])
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            elif kind=='partial':run['executions'][0]['assertions'].pop()
            else:run['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_suite_requires_fresh_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases']:
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--health-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode);self.assertIn(b'separate fresh databases',result.stderr)

    def test_external_services_stay_disabled(self):
        config=dict(environment(self.tokens))
        self.assertEqual('false',config['EXPERIENCE_PUBLICATION_ENABLED'])
        self.assertEqual('false',config['KNOWLEDGE_AI_ENABLED'])
        paths={x['path'] for x in self.manifest['items']}
        self.assertNotIn('/api/vehicle/vin-decode',paths)


if __name__=='__main__':unittest.main()
