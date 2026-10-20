"""Service consent execution proof must bind actual scopes, source and cleanup."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from run_service_archive_acceptance import ROOT,CLASS,CHECKS,audit,inventory

class ServiceArchiveEvidenceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True);self.temp=tempfile.TemporaryDirectory(prefix='service-archive-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="realServiceConsentContextAndSignedAttachments"/></testsuite>'
        self.data={'passed':True,'source_sha256':'synthetic-source','cases':inventory(),'checks':{k:True for k in CHECKS},'cleanup':{k:True for k in ['initializer','clamav','minio','mysql','network']}}
    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_service_archive_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)
    def case(self,name):return next(x for x in self.data['cases'] if x['name']==name)
    def test_ten_operations_have_all_four_categories(self):
        r=self.check();self.assertEqual((105,10),(r['requests'],r['operations']));self.assertEqual(18,len(r['checks']))
        self.assertTrue(all(set(v)=={'normal','boundary','error','unauthorized'} for v in r['coverage'].values()))
    def test_other_source_rejected(self):
        self.data['source_sha256']='old-source'
        with self.assertRaises(ValueError):self.check()
    def test_peer_identity_cannot_be_replaced_with_former_technician(self):
        self.case('peer_file')['role']='technician'
        with self.assertRaises(ValueError):self.check()
    def test_query_and_route_are_actual(self):
        original=copy.deepcopy(self.data)
        for name,field,value in [('candidates_query','path','/api/order/1/archive-grants'),('unselected_archive_file','path','/api/merchant/orders/1/service-context/archives/1/files/1/access'),('grant_activate','method','POST')]:
            self.data=copy.deepcopy(original);self.case(name)[field]=value
            with self.assertRaises(ValueError):self.check()
    def test_old_receipt_and_revocation_statuses_cannot_be_forged(self):
        original=copy.deepcopy(self.data)
        for name in ['grant_stale_revision','closed_old_grant_receipt','revoked_tech_file','grant_audit_failure']:
            self.data=copy.deepcopy(original);self.case(name)['status']=200
            with self.assertRaises(ValueError):self.check()
    def test_missing_duplicate_and_extra_execution_rejected(self):
        original=copy.deepcopy(self.data)
        for kind in ['missing','duplicate','extra']:
            self.data=copy.deepcopy(original)
            if kind=='missing':self.data['cases'].pop()
            elif kind=='duplicate':self.data['cases'][-1]=copy.deepcopy(self.data['cases'][0])
            else:self.data['cases'].append(copy.deepcopy(self.data['cases'][0]))
            with self.assertRaises(ValueError):self.check()
    def test_every_scope_source_and_lease_invariant_required(self):
        original=copy.deepcopy(self.data)
        for name in CHECKS:
            for missing in [True,False]:
                self.data=copy.deepcopy(original)
                if missing:self.data['checks'].pop(name)
                else:self.data['checks'][name]=False
                with self.assertRaises(ValueError):self.check()
    def test_every_owned_resource_must_be_removed(self):
        original=copy.deepcopy(self.data)
        for name in original['cleanup']:
            self.data=copy.deepcopy(original);self.data['cleanup'][name]=False
            with self.assertRaises(ValueError):self.check()
    def test_junit_skipped_missing_or_failed_rejected(self):
        original=self.xml
        for value in [original.replace('tests="1"','tests="0"'),original.replace('skipped="0"','skipped="1"'),original.replace('failures="0"','failures="1"'),original.replace('realServiceConsentContextAndSignedAttachments','other')]:
            self.xml=value
            with self.assertRaises(ValueError):self.check()
    def test_unexpected_check_and_cleanup_scope_rejected(self):
        self.data['checks']['synthetic_extra']=True
        with self.assertRaises(ValueError):self.check()
        self.data['checks'].pop('synthetic_extra');self.data['cleanup']['unowned_resource']=True
        with self.assertRaises(ValueError):self.check()

if __name__=='__main__':unittest.main()
