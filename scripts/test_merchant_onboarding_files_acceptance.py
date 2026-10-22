"""Merchant onboarding attachment evidence must preserve source, permission, route and owned cleanup."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from run_merchant_onboarding_files_acceptance import ROOT,CLASS,CHECKS,audit,inventory

TEST_NAME='realMerchantOnboardingQualificationAttachmentPermissionsAndSignatures'
OPERATION='/api/admin/merchant-applications/{id}/files/{file}/access'


class MerchantOnboardingFilesAcceptanceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='onboarding-files-evidence-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="{TEST_NAME}"/></testsuite>'
        self.data={'passed':True,'source_sha256':'synthetic-source','cases':inventory(),
            'checks':{k:True for k in CHECKS},
            'cleanup':{k:True for k in ('initializer','clamav','minio','mysql','network')}}

    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_merchant_onboarding_files_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)

    def test_one_qualification_route_has_four_categories(self):
        r=self.check();self.assertEqual((12,1),(r['requests'],r['operations']))
        self.assertEqual({'GET '+OPERATION:{'normal','boundary','error','unauthorized'}},{k:set(v) for k,v in r['coverage'].items()})

    def test_other_source_content_cannot_reuse_old_execution(self):
        self.data['source_sha256']='old-source'
        with self.assertRaises(ValueError):self.check()

    def test_cross_application_file_cannot_become_readable(self):
        next(c for c in self.data['cases'] if c['name']=='onboarding_cross_application_file')['status']=200
        with self.assertRaises(ValueError):self.check()

    def test_query_rejection_case_must_keep_its_query(self):
        next(c for c in self.data['cases'] if c['name']=='onboarding_query')['path']='/api/admin/merchant-applications/1/files/1/access'
        with self.assertRaises(ValueError):self.check()

    def test_permission_case_cannot_borrow_the_onboarding_operator(self):
        next(c for c in self.data['cases'] if c['name']=='onboarding_review_permission_only')['role']='operator'
        next(c for c in self.data['cases'] if c['name']=='onboarding_review_permission_only')['status']=200
        with self.assertRaises(ValueError):self.check()

    def test_owner_and_merchant_denials_stay_forbidden(self):
        for name in ('onboarding_owner_denied','onboarding_merchant_denied'):
            self.data=copy.deepcopy(self.data)
            next(c for c in self.data['cases'] if c['name']==name)['status']=404
            with self.assertRaises(ValueError):self.check()

    def test_missing_duplicate_or_unexpected_case_rejected(self):
        original=copy.deepcopy(self.data)
        for kind in ('missing','duplicate','unexpected'):
            self.data=copy.deepcopy(original)
            if kind=='missing':self.data['cases'].pop()
            elif kind=='duplicate':self.data['cases'][-1]=copy.deepcopy(self.data['cases'][0])
            else:self.data['cases'].append(copy.deepcopy(self.data['cases'][0]))
            with self.assertRaises(ValueError):self.check()

    def test_failed_missing_or_skipped_junit_is_not_execution_proof(self):
        original=self.xml
        for old,new in [('failures="0"','failures="1"'),('skipped="0"','skipped="1"'),('tests="1"','tests="0"')]:
            self.xml=original.replace(old,new)
            with self.assertRaises(ValueError):self.check()

    def test_snapshot_permission_signature_and_cleanup_proof_are_required(self):
        original=copy.deepcopy(self.data)
        for section,key in [('checks','qualification_snapshots_exact'),('checks','no_merchant_activation'),
                            ('checks','cross_application_files_never_linked'),('checks','unsigned_object_denied'),
                            ('checks','tampered_signature_denied'),('checks','expired_signature_denied'),
                            ('cleanup','minio'),('cleanup','clamav')]:
            self.data=copy.deepcopy(original);self.data[section][key]=False
            with self.assertRaises(ValueError):self.check()

    def test_dropping_a_declared_check_is_rejected(self):
        self.data['checks'].pop('three_clean_uploads')
        with self.assertRaises(ValueError):self.check()

    def test_private_url_cannot_be_smuggled_into_case_summary(self):
        self.data['cases'][0]['url']='https://synthetic.invalid/private'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
