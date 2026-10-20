"""Upload execution evidence must preserve source, identity, protocol and owned cleanup."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from run_upload_acceptance import ROOT,CLASS,audit,inventory


class UploadAcceptanceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='upload-evidence-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="realMultipartStorageScannerSigningAndArchivePermissions"/></testsuite>'
        self.data={'passed':True,'source_sha256':'synthetic-source','cases':inventory(),
            'checks':{k:True for k in ('anonymous_object_denied','tampered_signature_denied','expired_signature_denied','only_three_clean_files','three_successful_uploads','three_scan_rejections','one_owned_archive_attachment')},
            'cleanup':{k:True for k in ('initializer','clamav','minio','mysql','network')}}

    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_upload_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)

    def test_all_eight_operations_have_four_categories(self):
        r=self.check();self.assertEqual((53,8),(r['requests'],r['operations']))
        self.assertTrue(all(set(v)=={'normal','boundary','error','unauthorized'} for v in r['coverage'].values()))

    def test_other_source_content_cannot_reuse_old_execution(self):
        self.data['source_sha256']='old-source'
        with self.assertRaises(ValueError):self.check()

    def test_foreign_owner_role_cannot_be_substituted(self):
        next(c for c in self.data['cases'] if c['name']=='foreign_owner_file')['role']='owner'
        with self.assertRaises(ValueError):self.check()

    def test_query_validation_case_must_include_query(self):
        next(c for c in self.data['cases'] if c['name']=='owner_access_query')['path']='/api/file/1/access'
        with self.assertRaises(ValueError):self.check()

    def test_infected_file_cannot_be_recorded_as_clean_upload(self):
        next(c for c in self.data['cases'] if c['name']=='owner_infected')['status']=200
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

    def test_missing_expiry_scanner_or_cleanup_proof_rejected(self):
        original=copy.deepcopy(self.data)
        for section,key in [('checks','expired_signature_denied'),('checks','three_scan_rejections'),('cleanup','minio')]:
            self.data=copy.deepcopy(original);self.data[section][key]=False
            with self.assertRaises(ValueError):self.check()

    def test_private_fields_cannot_be_smuggled_into_case_summary(self):
        self.data['cases'][0]['signed_url']='https://synthetic.invalid/private'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
