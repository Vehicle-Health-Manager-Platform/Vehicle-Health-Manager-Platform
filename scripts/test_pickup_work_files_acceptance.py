"""Pickup/work attachment evidence must preserve source, role, route and owned cleanup."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from run_pickup_work_files_acceptance import ROOT,CLASS,CHECKS,audit,inventory

TEST_NAME='realPickupAndWorkAttachmentPermissionsSignaturesAndOrderIsolation'


class PickupWorkFilesAcceptanceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='pickup-files-evidence-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="{TEST_NAME}"/></testsuite>'
        self.data={'passed':True,'source_sha256':'synthetic-source','cases':inventory(),
            'checks':{k:True for k in CHECKS},
            'cleanup':{k:True for k in ('initializer','clamav','minio','mysql','network')}}

    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_pickup_work_files_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)

    def test_four_attachment_routes_have_four_categories(self):
        r=self.check();self.assertEqual((39,4),(r['requests'],r['operations']))
        self.assertTrue(all(set(v)=={'normal','boundary','error','unauthorized'} for v in r['coverage'].values()))

    def test_other_source_content_cannot_reuse_old_execution(self):
        self.data['source_sha256']='old-source'
        with self.assertRaises(ValueError):self.check()

    def test_cross_order_case_cannot_point_at_own_file(self):
        next(c for c in self.data['cases'] if c['name']=='pickup_cross_order')['status']=200
        with self.assertRaises(ValueError):self.check()

    def test_query_rejection_case_must_keep_query(self):
        next(c for c in self.data['cases'] if c['name']=='owner_work_query')['path']='/api/order/1/work/files/8/access'
        with self.assertRaises(ValueError):self.check()

    def test_peer_technician_cannot_be_swapped_for_assigned_one(self):
        next(c for c in self.data['cases'] if c['name']=='tech_work_peer')['role']='technician'
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

    def test_signature_or_cleanup_proof_is_required(self):
        original=copy.deepcopy(self.data)
        for section,key in [('checks','expired_signature_denied'),('checks','tampered_signature_denied'),('checks','pickup_and_work_links_exact'),('cleanup','minio')]:
            self.data=copy.deepcopy(original);self.data[section][key]=False
            with self.assertRaises(ValueError):self.check()

    def test_private_url_cannot_be_smuggled_into_case_summary(self):
        self.data['cases'][0]['url']='https://synthetic.invalid/private'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
