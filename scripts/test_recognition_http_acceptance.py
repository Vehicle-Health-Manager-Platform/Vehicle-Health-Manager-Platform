import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import run_recognition_http_acceptance as r

class RecognitionEvidenceTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
  self.junit=self.root/'junit.xml';self.evidence=self.root/'proof.json'
  self.junit.write_text('<testsuite name="com.autocare.platform.order.VinHttpJourneyTest" tests="1" failures="0" errors="0" skipped="0"><testcase classname="com.autocare.platform.order.VinHttpJourneyTest" name="vin_four_kind_http_evidence"/></testsuite>',encoding='utf-8')
  self.data=dict(version=1,passed=True,provider_fixture=True,source_sha256='a'*64,mysql_container_id='b'*64,checks={k:True for k in r.CHECKS},cases=[dict(v,request_sha256='c'*64,response_sha256='d'*64) for v in r.inventory('vin')])
 def audit(self):
  self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
  with patch.object(r,'source_digest',return_value='a'*64):return r.audit('vin',self.junit,self.evidence)
 def test_positive_requires_observed_cases_and_exact_current_source(self):
  p=self.audit();self.assertEqual(6,p['requests']);self.assertEqual(['boundary','error','normal','unauthorized'],next(iter(p['coverage'].values())))
 def test_missing_extra_duplicate_reordered_or_wrong_status_rejected(self):
  original=copy.deepcopy(self.data)
  mutations=[lambda d:d['cases'].pop(),lambda d:d['cases'].append(d['cases'][0]),lambda d:d['cases'].reverse(),lambda d:d['cases'][0].update(status=503),lambda d:d['cases'][0].update(role='merchant'),lambda d:d['cases'][0].update(path='/other'),lambda d:d['cases'][0].update(response_sha256='bad'),lambda d:d['cases'][0].update(secret_token='do-not-export')]
  for change in mutations:
   self.data=copy.deepcopy(original);change(self.data)
   with self.assertRaises(ValueError):self.audit()
 def test_source_failure_fixture_and_sql_tampering_rejected(self):
  original=copy.deepcopy(self.data)
  for change in (lambda d:d.update(source_sha256='e'*64),lambda d:d.update(passed=False),lambda d:d.update(provider_fixture=False),lambda d:d.update(checks={}),lambda d:d['checks'].update(no_vehicle_write=False),lambda d:d.update(mysql_container_id='bad')):
   self.data=copy.deepcopy(original);change(self.data)
   with self.assertRaises(ValueError):self.audit()
 def test_failed_skipped_or_missing_required_junit_is_rejected(self):
  original=self.junit.read_text()
  for old,new in (('failures="0"','failures="1"'),('skipped="0"','skipped="1"'),('vin_four_kind_http_evidence','unrelated'),('tests="1"','tests="2"')):
   self.junit.write_text(original.replace(old,new))
   with self.assertRaises(ValueError):self.audit()
 def test_only_reviewed_inventory_counts_as_four_kind(self):
  self.assertEqual(6,len(r.inventory('vin')));self.assertEqual(7,len(r.inventory('license')))
  self.assertEqual({'normal','boundary','error','unauthorized'},{v['kind'] for v in r.inventory('license')})

class JobEvidenceTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
 def make(self,name):
  cls,method,_=r.SPECS[name];junit=self.root/'job.xml';raw=self.root/'job.json'
  junit.write_text(f'<testsuite name="com.autocare.platform.order.{cls}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="com.autocare.platform.order.{cls}" name="{method}"/></testsuite>')
  d=dict(version=1,passed=True,provider_fixture=True,source_sha256='a'*64,mysql_container_id='b'*64,bindings={'job_id':7},checks={k:True for k in r.JOB_CHECKS},cases=[dict(v,request_sha256='c'*64,response_sha256='d'*64) for v in r.inventory(name,{'job_id':7})])
  return junit,raw,d
 def audit(self,name,junit,raw,d):
  raw.write_text(json.dumps(d),encoding='utf-8')
  with patch.object(r,'source_digest',return_value='a'*64):return r.audit(name,junit,raw)
 def test_six_operations_have_independent_four_kind_proof(self):
  for name in ('asr','document'):
   j,p,d=self.make(name);proof=self.audit(name,j,p,d)
   self.assertEqual(20,proof['requests']);self.assertEqual(3,len(proof['coverage']))
   self.assertTrue(all(set(v)=={'normal','boundary','error','unauthorized'} for v in proof['coverage'].values()))
 def test_invalid_and_mismatched_actual_job_bindings_are_rejected(self):
  j,p,original=self.make('asr')
  for binding in ({},{'job_id':0},{'job_id':-1},{'job_id':True},{'job_id':'7'},{'job_id':7.0},{'job_id':9007199254740992},{'job_id':8},{'job_id':7,'secret':'bad'}):
   d=copy.deepcopy(original);d['bindings']=binding
   with self.subTest(binding=binding),self.assertRaises(ValueError):self.audit('asr',j,p,d)
 def test_wrong_method_missing_operation_or_job_sql_checks_are_rejected(self):
  j,p,original=self.make('document')
  for change in (lambda d:d['cases'][7].update(method='POST'),lambda d:d.update(cases=d['cases'][:13]),lambda d:d['checks'].update(job_binding_verified=False),lambda d:d['checks'].update(cancelled_result_withheld=False)):
   d=copy.deepcopy(original);change(d)
   with self.assertRaises(ValueError):self.audit('document',j,p,d)
if __name__=='__main__':unittest.main()
