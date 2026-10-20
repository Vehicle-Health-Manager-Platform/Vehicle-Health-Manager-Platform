"""Scheduled reward execution proof cannot omit atomicity, identity, source or owned cleanup."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from run_reward_acceptance import ROOT,CLASS,CHECKS,inventory,audit


class RewardAcceptanceTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.cache').mkdir(exist_ok=True);self.temp=tempfile.TemporaryDirectory(prefix='reward-proof-',dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.junit=Path(self.temp.name)/'junit.xml';self.evidence=Path(self.temp.name)/'evidence.json'
        self.xml=f'<testsuite name="{CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase classname="{CLASS}" name="realHttpQualificationFreezeAndScheduledAtomicRewardDelivery"/></testsuite>'
        self.data={'passed':True,'source_sha256':'synthetic-source','cases':inventory(),'checks':{k:True for k in CHECKS},'cleanup':{'mysql':True}}
    def check(self):
        self.junit.write_text(self.xml,encoding='utf-8');self.evidence.write_text(json.dumps(self.data),encoding='utf-8')
        with patch('run_reward_acceptance.source_digest',return_value='synthetic-source'):return audit(self.junit,self.evidence)

    def test_actual_method_and_path_define_four_category_coverage(self):
        r=self.check();self.assertEqual((144,14),(r['requests'],r['operations']));self.assertTrue(all(set(v)=={'normal','boundary','error','unauthorized'} for v in r['coverage'].values()))
        self.assertIn('GET /api/admin/invite/reward-policies',r['coverage']);self.assertIn('POST /api/admin/invite/reward-policies',r['coverage'])
    def test_changed_source_cannot_reuse_execution(self):
        self.data['source_sha256']='old-source'
        with self.assertRaises(ValueError):self.check()
    def test_foreign_shop_and_coupon_permission_cannot_change_role(self):
        original=copy.deepcopy(self.data)
        for name,role in [('foreign_shop_rewards','merchant'),('coupon_cannot_manage','invite')]:
            self.data=copy.deepcopy(original);next(c for c in self.data['cases'] if c['name']==name)['role']=role
            with self.assertRaises(ValueError):self.check()
    def test_method_query_status_and_snapshot_route_are_actual(self):
        original=copy.deepcopy(self.data)
        for name,key,value in [('policy_edit','method','POST'),('duplicate_scope','path','/api/invite/reward-policy?scope=OWNER'),('unique_active_policy','status',200),('old_publish_receipt','path','/api/admin/invite/reward-policies/2/status')]:
            self.data=copy.deepcopy(original);next(c for c in self.data['cases'] if c['name']==name)[key]=value
            with self.assertRaises(ValueError):self.check()
    def test_failed_missing_skipped_junit_rejected(self):
        original=self.xml
        for old,new in [('tests="1"','tests="0"'),('failures="0"','failures="1"'),('errors="0"','errors="1"'),('skipped="0"','skipped="1"')]:
            self.xml=original.replace(old,new)
            with self.assertRaises(ValueError):self.check()
    def test_missing_duplicate_extra_case_rejected(self):
        original=copy.deepcopy(self.data)
        for kind in ['missing','duplicate','extra']:
            self.data=copy.deepcopy(original)
            if kind=='missing':self.data['cases'].pop()
            elif kind=='duplicate':self.data['cases'][-1]=copy.deepcopy(self.data['cases'][0])
            else:self.data['cases'].append(copy.deepcopy(self.data['cases'][0]))
            with self.assertRaises(ValueError):self.check()
    def test_rollbacks_freeze_retry_and_staff_privacy_checks_required(self):
        original=copy.deepcopy(self.data)
        for key in ['audit_failure_rolls_back_entire_pair','retry_uses_old_snapshot_once','insufficient_stock_never_partially_issues','staff_owner_id_overlap_does_not_leak_coupon','unknown_policy_not_backfilled']:
            self.data=copy.deepcopy(original);self.data['checks'][key]=False
            with self.assertRaises(ValueError):self.check()
    def test_cleanup_failure_or_missing_proof_rejected(self):
        for value in [{'mysql':False},{}]:
            self.data['cleanup']=value
            with self.assertRaises(ValueError):self.check()
    def test_private_fields_cannot_be_added_to_public_cases(self):
        self.data['cases'][0]['jwt']='synthetic-private-value'
        with self.assertRaises(ValueError):self.check()


if __name__=='__main__':unittest.main()
