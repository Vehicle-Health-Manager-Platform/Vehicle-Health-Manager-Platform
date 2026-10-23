"""Finance evidence must bind exact selection, permissions and unchanged source content."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit,parse_qsl
from local_finance_api_cases import ROOT,OUTPUT,MANIFEST,CHECKS,TABLES,BASELINE,assets,audit,environment,verify_database,backend_port,verify_ui

ROLES=('owner','merchant','technician','operator','review_operator','revoked_operator','disabled_operator','expired_operator')


class LocalFinanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets()
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'report.json'
        self.tokens={role+'_token':'synthetic-'+role for role in ROLES}
        requests={item['name']:item['request'] for item in self.collection['item']}
        executions=[]
        for case in self.manifest['items']:
            url=urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            headers+=copy.deepcopy(requests[case['name']].get('header',[]))
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],
                'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]},
                'header':headers},'response':{'code':case['status']},'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_reproducible_one_read_route_four_categories_default_disabled(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        result=self.check();self.assertEqual((24,110,1),(result['requests'],result['assertions'],len(result['coverage'])))
        self.assertEqual({'normal','boundary','error','unauthorized'},set(next(iter(result['coverage'].values()))))
        self.assertTrue(result['read_only_intents_verified'])
        self.assertEqual('false',self.collection['variable'][1]['value'])

    def test_identity_status_and_permission_cases_cannot_swap_token(self):
        original=copy.deepcopy(self.report)
        for role in ROLES:
            self.report=copy.deepcopy(original)
            case=self.case('local_daily_exact' if role=='operator' else 'finance_denied_'+role)
            wrong='review_operator' if role=='operator' else 'operator'
            case['request']['header']=[{'key':'Authorization','value':'Bearer '+self.tokens[wrong+'_token']}]
            with self.subTest(role=role),self.assertRaises(ValueError):self.check()

    def test_exact_date_channel_and_query_multiplicity_required(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','changed','extra','duplicate'):
            self.report=copy.deepcopy(original);query=self.case('local_daily_exact')['request']['url']['query']
            if mode=='missing':query.pop()
            elif mode=='changed':query[0]['value']='2026-10-21'
            elif mode=='extra':query.append({'key':'page','value':'1'})
            else:query.append(copy.deepcopy(query[0]))
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_collection_and_executed_write_intents_rejected(self):
        original=copy.deepcopy(self.report);collection=copy.deepcopy(self.collection)
        for target in ('collection','execution'):
            for mode in ('method','body','idempotency'):
                self.report=copy.deepcopy(original);self.collection=copy.deepcopy(collection)
                request=self.collection['item'][0]['request'] if target=='collection' else self.case('local_daily_exact')['request']
                if mode=='method':request['method']='POST'
                elif mode=='body':request['body']={'mode':'raw','raw':'{}'}
                else:request.setdefault('header',[]).append({'key':'Idempotency-Key','value':'synthetic'})
                with self.subTest(target=target,mode=mode),self.assertRaises(ValueError):self.check()

    def test_partial_failed_skipped_duplicate_and_wrong_status_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial','status','request_error'):
            self.report=copy.deepcopy(original);case=self.case('local_daily_exact')
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(case))
            elif mode=='failed':self.report['run']['failures']=[{'error':'synthetic'}]
            elif mode=='skipped':case['assertions'][0]['skipped']=True
            elif mode=='partial':case['assertions'].pop()
            elif mode=='status':case['response']['code']=403
            else:case['requestError']={'message':'synthetic'}
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()

    def test_every_source_table_requires_full_content_snapshot(self):
        original=dict(BASELINE);self.addCleanup(lambda:(BASELINE.clear(),BASELINE.update(original)))
        BASELINE.clear();BASELINE.update({table:'synthetic-hash' for table in TABLES})
        with patch('local_finance_api_cases.snapshot',return_value=dict(BASELINE)):
            self.assertEqual(CHECKS,set(verify_database(None)['checks']))
        for table in TABLES:
            changed=dict(BASELINE);changed[table]='same-row-count-different-content'
            with self.subTest(table=table),patch('local_finance_api_cases.snapshot',return_value=changed),self.assertRaises(ValueError):verify_database(None)
        BASELINE.clear()
        with patch('local_finance_api_cases.snapshot',return_value={t:'x' for t in TABLES}),self.assertRaises(ValueError):verify_database(None)

    def test_no_provider_switch_or_native_claim_without_explicit_opt_in(self):
        self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))
        with patch.dict('os.environ',{'AUTOCARE_FINANCE_NATIVE_QA':'0'}),patch('run_performance_baseline.free_port',return_value=12345):
            self.assertEqual(12345,backend_port())
            self.assertEqual(False,verify_ui(None,None,None)['executed'])

    def test_separate_database_and_build_proof_required(self):
        for flag in ('--finance-cases','--delivery-compare-cases','--record-ci-sources'):
            result=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--local-finance-cases',flag],capture_output=True)
            self.assertEqual(2,result.returncode)


if __name__=='__main__':unittest.main()
