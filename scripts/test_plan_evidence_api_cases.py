"""Bind actual plan evidence requests to consent, versions, identities and date."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit,parse_qsl
from plan_evidence_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,environment,render


class PlanEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(prefix='plan-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ('owner','other','merchant','other_merchant','technician','catalog','plain')}
        self.tokens.update(slot_date='2026-10-23',slot_start='2026-10-23T10:00:00+08:00',slot_end='2026-10-23T11:00:00+08:00')
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=urlsplit(expected['url'].removeprefix('{{base_url}}').replace('{{slot_date}}',self.tokens['slot_date']))
            headers=copy.deepcopy(expected['header'])
            if case['role']!='anonymous':headers.append({'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']})
            request={'method':case['method'],'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query)]},'header':headers}
            if 'body' in expected:
                request['body']=copy.deepcopy(expected['body'])
                request['body']['raw']=render(request['body']['raw'],self.tokens)
            executions.append({'item':{'name':case['name']},'request':request,'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(e for e in self.report['run']['executions'] if e['item']['name']==name)
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8');return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_assets_scope_and_default_disabled(self):
        self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        result=self.check();self.assertEqual((70,237,7),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertTrue(result['plan_request_intents_verified']);self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_missing_duplicate_failed_skipped_and_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('rule_applicable')))
            elif mode=='failed':self.report['run']['failures']=[{'error':'failed'}]
            elif mode=='skipped':self.case('manual_plan')['assertions'][0]['skipped']=True
            else:self.case('booking_context')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_versions_source_consent_and_date_are_bound(self):
        original=copy.deepcopy(self.report)
        for name in ('rule_applicable','rule_not_applicable','rule_withdraw','manual_plan','vehicle_unknown','booking_context','plan_slot'):
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']='{}'
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_original_write_keys_and_keyless_reads_are_required(self):
        original=copy.deepcopy(self.report)
        next(h for h in self.case('rule_applicable')['request']['header'] if h['key']=='Idempotency-Key')['value']='changed'
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('booking_context')['request']['header'].append({'key':'Idempotency-Key','value':'unexpected'})
        with self.assertRaises(ValueError):self.check()

    def test_foreign_owner_and_operator_permission_identity_are_actual(self):
        original=copy.deepcopy(self.report)
        for name,role in [('vehicle_foreign','owner'),('rule_write_no_permission','catalog'),('rule_read_no_permission','catalog')]:
            self.report=copy.deepcopy(original);request=self.case(name)['request']
            next(h for h in request['header'] if h['key']=='Authorization')['value']='Bearer '+self.tokens[role+'_token']
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()

    def test_stale_version_rejection_and_query_path_cannot_be_forged(self):
        original=copy.deepcopy(self.report)
        self.case('rule_reuse_old_version')['response']['code']=200
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('booking_query')['request']['url']['query']=[]
        with self.assertRaises(ValueError):self.check()

    def test_model_is_explicitly_disabled_and_unconfigured(self):
        config=dict(environment(self.tokens));self.assertEqual('false',config['AI_SERVICE_PLAN_ENABLED']);self.assertEqual('',config['DEEPSEEK_API_KEY']);self.assertEqual('',config['DEEPSEEK_BASE_URL'])

    def test_each_suite_requires_separate_database(self):
        for flag in ('--fulfillment-reads-cases','--governance-cases','--record-ci-sources'):
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--plan-evidence-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
