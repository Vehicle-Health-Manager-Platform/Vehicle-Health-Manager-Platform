"""Auth evidence must bind generated credentials without exporting any secret."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from auth_sessions_api_cases import ROOT,OUTPUT,MANIFEST,OPERATION_COUNT,CHECKS,assets,audit,environment,render,signed,claims
from unittest.mock import patch
import auth_sessions_api_cases as module

SECRET='synthetic-unit-secret-at-least-32-characters'
ROLES=('owner','other','merchant','other_merchant','technician','operator','bad')

class AuthSessionsEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection,self.manifest=assets();(ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='auth-session-evidence-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)/'report.json'
        self.tokens={r+'_token':'synthetic-'+r for r in ROLES}
        for label in ('owner','merchant','technician','expired','revoked','wrong_app','disabled_owner','disabled_tech','operator'):self.tokens[label+'_refresh']='old-'+label+'x'*(43-len(label)-4)
        self.responses={};self.bound=dict(self.tokens)
        for i,(label,role) in enumerate([('owner','OWNER'),('merchant','MERCHANT'),('technician','TECHNICIAN')],1):
            p=dict(iss='vehicle-health-manager',sub=str(3 if label=='technician' else 1),iat=100,exp=3700,jti='synthetic-session-'+label,subject_type='user' if label=='owner' else 'staff_account',role=role,app_id='merchant-account' if label=='merchant' else 'fulfillment-cases')
            if label!='owner':p['merchant_id']=1
            if label=='technician':p['binding_id']=1
            self.tokens[label+'_token']=signed(p,SECRET);self.bound[label+'_token']=self.tokens[label+'_token']
            user=dict(id=int(p['sub']),role=label)
            if label=='owner':user['phone_bound']=False
            else:user['merchant_id']=1
            data=dict(access_token=signed(dict(p,iat=101,exp=1001),SECRET),refresh_token=chr(97+i)*43,token_type='Bearer',expires_in=900,user=user)
            self.responses['refresh_'+label]=dict(code=0,data=data)
            self.bound[label+'_new_token']=data['access_token'];self.bound[label+'_next_refresh']=data['refresh_token']
        requests={i['name']:i['request'] for i in self.collection['item']};executions=[]
        for case in self.manifest['items']:
            expected=requests[case['name']];url=urlsplit(expected['url'].removeprefix('{{base_url}}'));headers=copy.deepcopy(expected['header'])
            if case['role']!='anonymous':headers.append(dict(key='Authorization',value='Bearer '+self.bound[case['role']+'_token']))
            req=dict(method=case['method'],url=dict(path=url.path.strip('/').split('/'),query=[dict(key=k,value=v) for k,v in parse_qsl(url.query,keep_blank_values=True)]),header=headers)
            if 'body' in expected:req['body']=dict(expected['body'],raw=render(expected['body']['raw'],self.bound))
            response=dict(code=case['status'])
            if case['name'] in self.responses:response['stream']=dict(type='Buffer',data=list(json.dumps(self.responses[case['name']]).encode()))
            executions.append(dict(item=dict(name=case['name']),request=req,response=response,assertions=[dict(assertion=str(i)) for i in range(case['assertions'])]))
        self.report=dict(run=dict(executions=executions,failures=[]))
    def case(self,name):return next(x for x in self.report['run']['executions'] if x['item']['name']==name)
    def replace_response(self,name,data):self.case(name)['response']['stream']['data']=list(json.dumps(dict(code=0,data=data)).encode())
    def check(self):
        self.path.write_text(json.dumps(self.report),encoding='utf-8')
        with patch.object(module,'SECRET',SECRET):return audit(self.path,self.collection,self.manifest,self.tokens)
    def test_generated_credentials_bound_to_all_followup_requests(self):
        r=self.check();self.assertEqual((45,149,4),(r['requests'],r['assertions'],len(r['coverage'])))
        self.assertTrue(r['auth_request_bindings_verified']);self.assertEqual(self.collection,json.loads(OUTPUT.read_text(encoding='utf-8')));self.assertEqual(self.manifest,json.loads(MANIFEST.read_text(encoding='utf-8')))
        self.assertEqual('false',self.collection['variable'][1]['value']);self.assertFalse(self.manifest['full_api_acceptance_passed'])
        for value in self.bound.values():self.assertNotIn(value,json.dumps(r))
    def test_rotated_signature_subject_session_and_lifetime_required(self):
        original=copy.deepcopy(self.report);data=self.responses['refresh_owner']['data']
        for change in ('signature','subject','session','lifetime','user','same_refresh','response_lifetime','algorithm'):
            self.report=copy.deepcopy(original);altered=copy.deepcopy(data);p=claims(data['access_token'])
            if change=='signature':altered['access_token']=altered['access_token'].rsplit('.',1)[0]+'.'+'0'*43
            elif change=='subject':altered['access_token']=signed(dict(p,sub='2'),SECRET)
            elif change=='session':altered['access_token']=signed(dict(p,jti='other-session'),SECRET)
            elif change=='lifetime':altered['access_token']=signed(dict(p,exp=p['iat']+3600),SECRET)
            elif change=='user':altered['user']['id']=2
            elif change=='same_refresh':altered['refresh_token']=self.tokens['owner_refresh']
            elif change=='response_lifetime':altered['expires_in']=True
            else:altered['access_token']='eyJhbGciOiJub25lIn0.'+altered['access_token'].split('.')[1]+'.'+'0'*43
            self.replace_response('refresh_owner',altered)
            with self.subTest(change=change),self.assertRaises(ValueError):self.check()
    def test_followup_refresh_cannot_reuse_original_or_another_credential(self):
        original=copy.deepcopy(self.report)
        for name in ('refresh_owner_after_logout','refresh_merchant_old_rejected','refresh_disabled_owner'):
            self.report=copy.deepcopy(original);self.case(name)['request']['body']['raw']=json.dumps(dict(refresh_token=self.tokens['other_refresh'] if 'other_refresh' in self.tokens else 'z'*43))
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_new_access_and_cross_role_requests_use_actual_identity(self):
        original=copy.deepcopy(self.report)
        for name in ('new_owner_access','logout_owner','operator_logout_owner_denied','logout_operator_denied','other_owner_survives'):
            self.report=copy.deepcopy(original)
            h=next(h for h in self.case(name)['request']['header'] if h['key']=='Authorization');h['value']='Bearer substituted'
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_logout_body_query_and_key_must_match(self):
        original=copy.deepcopy(self.report)
        self.case('operator_logout_bad_query')['request']['url']['query']=[]
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('logout_owner')['request']['body']=dict(mode='raw',raw='{}')
        with self.assertRaises(ValueError):self.check()
        self.report=copy.deepcopy(original);self.case('refresh_owner')['request']['header'].append(dict(key='Idempotency-Key',value='unexpected'))
        with self.assertRaises(ValueError):self.check()
    def test_missing_duplicate_failed_skipped_partial_rejected(self):
        original=copy.deepcopy(self.report)
        for mode in ('missing','duplicate','failed','skipped','partial'):
            self.report=copy.deepcopy(original)
            if mode=='missing':self.report['run']['executions'].pop()
            elif mode=='duplicate':self.report['run']['executions'].append(copy.deepcopy(self.case('refresh_owner')))
            elif mode=='failed':self.report['run']['failures']=[dict(error='synthetic')]
            elif mode=='skipped':self.case('refresh_owner')['assertions'][0]['skipped']=True
            else:self.case('operator_logout')['assertions'].pop()
            with self.subTest(mode=mode),self.assertRaises(ValueError):self.check()
    def test_old_refresh_and_logged_out_access_cannot_be_forged_as_success(self):
        original=copy.deepcopy(self.report)
        for name in ('refresh_owner_old_rejected','refresh_owner_after_logout','old_owner_after_logout','operator_logout_repeat'):
            self.report=copy.deepcopy(original);self.case(name)['response']['code']=200
            with self.subTest(name=name),self.assertRaises(ValueError):self.check()
    def test_no_external_identity_or_supplier_switches_enabled(self):self.assertEqual({'WECHAT_APP_ID':'fulfillment-cases'},dict(environment(self.tokens)))
    def test_separate_database_and_build_record_required(self):
        for flag in ('--coupon-catalog-cases','--coupon-cases','--relations-price-cases','--record-ci-sources'):
            r=subprocess.run([sys.executable,str(ROOT/'scripts/run_api_acceptance.py'),'--auth-sessions-cases',flag],capture_output=True)
            self.assertEqual(2,r.returncode)

if __name__=='__main__':unittest.main()
