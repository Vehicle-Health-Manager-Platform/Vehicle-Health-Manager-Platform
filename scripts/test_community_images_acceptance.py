"""Reject altered routes, consent bodies, response bindings and incomplete execution."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from run_community_images_acceptance import ROOT,CLASS,CHECKS,audit,inventory,resolve

class CommunityImagesAcceptanceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='community-image-evidence-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="realUploadConsentModerationAndWithdrawal"/></testsuite>'
        b=dict(experience_id='00000000-0000-4000-8000-000000000001',public_id='00000000-0000-4000-8000-000000000002',next_public_id='00000000-0000-4000-8000-000000000003',archive_id=2,preview_hash='a'*64)
        self.data=dict(passed=True,source_sha256='synthetic-source',bindings=b,cases=resolve(inventory(),b),checks={k:True for k in CHECKS},cleanup={k:True for k in ('initializer','clamav','minio','mysql','network')})
    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_community_images_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)
    def case(self,name):return next(c for c in self.data['cases'] if c['name']==name)
    def test_complete_bound_inventory(self):
        r=self.check();self.assertEqual(11,r['operations']);self.assertEqual(len(inventory()),r['requests']);self.assertTrue(r['actual_requests_bound_to_responses']);self.assertEqual(12,len(r['checks']))
    def test_consent_and_hash_must_match_sent_body(self):
        original=copy.deepcopy(self.data)
        for key,value in [('consent',False),('consent',1),('preview_hash','0'*64),('archive_id',999999),('source_file_id',2)]:
            self.data=copy.deepcopy(original);self.case('submit')['body'][key]=value
            with self.assertRaises(ValueError):self.check()
    def test_public_bytes_route_and_format_must_match(self):
        self.case('public_png')['path']+='&format=base64'
        with self.assertRaises(ValueError):self.check()
    def test_foreign_identity_cannot_be_replaced(self):
        self.case('public_foreign_vehicle')['role']='viewer'
        with self.assertRaises(ValueError):self.check()
    def test_missing_duplicate_or_extra_cases(self):
        original=copy.deepcopy(self.data)
        for change in ('missing','duplicate','extra'):
            self.data=copy.deepcopy(original)
            if change=='missing':self.data['cases'].pop()
            elif change=='duplicate':self.data['cases'][-1]=copy.deepcopy(self.data['cases'][0])
            else:self.data['cases'].append(copy.deepcopy(self.data['cases'][0]))
            with self.assertRaises(ValueError):self.check()
    def test_invalid_or_changed_response_bindings(self):
        original=copy.deepcopy(self.data)
        for key,value in [('archive_id',True),('archive_id',0),('preview_hash','A'*64),('public_id',original['bindings']['next_public_id']),('experience_id','00000000-0000-4000-8000-000000000004')]:
            self.data=copy.deepcopy(original);self.data['bindings'][key]=value
            with self.assertRaises(ValueError):self.check()
        self.data=copy.deepcopy(original);self.data['bindings'].pop('archive_id')
        with self.assertRaises(ValueError):self.check()
    def test_all_privacy_and_cleanup_checks_required(self):
        original=copy.deepcopy(self.data)
        for section in ('checks','cleanup'):
            for key in original[section]:
                for value in (False,1):
                    self.data=copy.deepcopy(original);self.data[section][key]=value
                    with self.assertRaises(ValueError):self.check()
                self.data=copy.deepcopy(original);self.data[section].pop(key)
                with self.assertRaises(ValueError):self.check()
        self.data=copy.deepcopy(original);self.data['cleanup']['unexpected']=True
        with self.assertRaises(ValueError):self.check()
    def test_failed_skipped_or_missing_junit(self):
        original=self.xml
        for old,new in [('failures="0"','failures="1"'),('skipped="0"','skipped="1"'),('tests="1"','tests="0"'),('realUploadConsentModerationAndWithdrawal','anotherTest')]:
            self.xml=original.replace(old,new)
            with self.assertRaises(ValueError):self.check()
    def test_source_mismatch_rejected(self):
        self.data['source_sha256']='old'
        with self.assertRaises(ValueError):self.check()
    def test_extra_private_url_rejected(self):
        self.case('public_png')['url']='https://synthetic.invalid/private'
        with self.assertRaises(ValueError):self.check()

if __name__=='__main__':unittest.main()
