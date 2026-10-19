"""Verify export evidence identities and PDF envelope integrity; actual PDF parsing runs in HTTP acceptance."""
import base64
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit,parse_qsl
from export_api_cases import ROOT,OUTPUT,MANIFEST,assets,audit,pdf_evidence


class ExportEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='export-evidence-',dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ('owner','other','merchant')}
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        self.raw=b'%PDF-synthetic-unit-evidence'
        self.capabilities={'share_token':'a'*64,'new_share_token':'b'*64}
        self.envelope={'version':1,'content_type':'application/pdf','file_name':'vehicle-archive-report.pdf','content_length':len(self.raw),'content_base64':base64.b64encode(self.raw).decode()}
        for case in self.manifest['items']:
            url=urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers=[] if case['role']=='anonymous' else [{'key':'Authorization','value':'Bearer '+self.tokens[case['role']+'_token']}]
            headers += [h for h in requests[case['name']].get('header',[]) if h['key']=='Accept']
            response={'code':case['status']}
            if case['name'] in ('link','new_link'):
                key='share_token' if case['name']=='link' else 'new_share_token'
                response['stream']={'data':list(json.dumps({'code':0,'data':{'path':'/pages/archive/shared?token='+self.capabilities[key]}}).encode())}
            if case['name'] in ('pdf','pdf_boundary','pdf_base64','pdf_filtered'):
                raw=json.dumps({'data':self.envelope}).encode() if case['name'] in ('pdf_base64','pdf_filtered') else self.raw
                response['stream']={'data':list(raw),'type':'Buffer'}
            executions.append({'item':{'name':case['name']},'request':{'method':case['method'],'header':headers,'body':copy.deepcopy(requests[case['name']].get('body',{})),'url':{'path':url.path.strip('/').split('/'),'query':[{'key':k,'value':v} for k,v in parse_qsl(url.query,keep_blank_values=True)]}},'response':response,'assertions':[{'assertion':str(i)} for i in range(case['assertions'])]})
        for execution in executions:
            body=execution['request']['body']
            if 'raw' in body:
                for key,value in self.capabilities.items():body['raw']=body['raw'].replace('{{'+key+'}}',value)
        self.report={'run':{'executions':executions,'failures':[]}}

    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)

    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        with patch('export_api_cases.pdf_evidence',return_value=({'pages':1},'合成保养记录 SYNTHETIC_PRIVATE_NOTES SYNTHETIC_PRIVATE_TITLE')):
            return audit(self.path,self.collection,self.manifest,self.tokens)

    def test_fresh_assets_complete_scope_and_disabled_default(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')),self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')),self.manifest)
        r=self.check();self.assertEqual((66,228,7),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertEqual(4,len(r['pdf_evidence']))
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_recipient_evidence_cannot_substitute_original_owner(self):
        self.case('recipient_view')['request']['header'][0]['value']='Bearer '+self.tokens['owner_token']
        with self.assertRaises(ValueError):self.check()

    def test_revoked_share_cannot_return_success(self):
        self.case('revoked_view')['response']['code']=200
        with self.assertRaises(ValueError):self.check()

    def test_base64_declared_length_must_match_actual_bytes(self):
        body=copy.deepcopy(self.envelope);body['content_length']+=1
        self.case('pdf_base64')['response']['stream']['data']=list(json.dumps({'data':body}).encode())
        with self.assertRaises(ValueError):self.check()

    def test_invalid_base64_rejected(self):
        body=copy.deepcopy(self.envelope);body['content_base64']='%%%'
        self.case('pdf_base64')['response']['stream']['data']=list(json.dumps({'data':body}).encode())
        with self.assertRaises(ValueError):self.check()

    def test_duplicate_vehicle_query_proof_cannot_drop_second_value(self):
        query=self.case('share_duplicate_vehicle')['request']['url']['query'];query.pop()
        with self.assertRaises(ValueError):self.check()

    def test_missing_skipped_or_failed_execution_rejected(self):
        original=copy.deepcopy(self.report)
        for kind in ('missing','skipped','failed'):
            self.report=copy.deepcopy(original);run=self.report['run']
            if kind=='missing':run['executions'].pop()
            elif kind=='skipped':run['executions'][0]['assertions'][0]['skipped']=True
            else:run['failures']=[{'error':'synthetic'}]
            with self.assertRaises(ValueError):self.check()

    def test_readonly_share_view_does_not_use_write_receipt_key(self):
        for item in self.collection['item']:
            if '/api/archive/share/view' in item['request']['url']:
                self.assertNotIn('Idempotency-Key',{h['key'] for h in item['request']['header']})

    def test_suite_requires_fresh_database(self):
        for flag in ['--owner-cases','--fulfillment-cases','--coupon-cases','--merchant-cases','--community-cases','--publication-cases','--experience-cases','--health-cases']:
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--export-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode);self.assertIn(b'separate fresh databases',r.stderr)

    def test_share_view_must_use_capability_from_actual_link(self):
        self.case('recipient_view')['request']['body']['raw']=json.dumps({'share_token':'b'*64})
        with self.assertRaises(ValueError):self.check()

    def test_pdf_envelope_requires_actual_accept_header(self):
        self.case('pdf_base64')['request']['header']=[h for h in self.case('pdf_base64')['request']['header'] if h['key']!='Accept']
        with self.assertRaises(ValueError):self.check()

    def test_pdf_magic_and_size_limits_reject_invalid_bytes(self):
        for raw in (b'not-pdf',b'%PDF-'+b'x'*(3*1024*1024)):
            with self.assertRaises(ValueError):pdf_evidence(raw)


if __name__=='__main__':unittest.main()
