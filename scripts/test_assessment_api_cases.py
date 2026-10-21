"""Fixed month literals, actual identities, idempotency scopes and evidence completeness are required."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from assessment_api_cases import (ROOT, OUTPUT, MANIFEST, OPERATION_COUNT, CLOSED, FUTURE, ABSENT,
                                  POLICY, assets, audit, environment)

ROLES = ('owner', 'other', 'merchant', 'other_merchant', 'technician', 'staff', 'assessor', 'analyst')
CHECKS = ('policy_states', 'one_published_per_shop', 'seeded_policy_covers_closed_month', 'month_heads',
          'only_closed_month_settled', 'snapshot_revisions', 'unknown_summary_never_scored',
          'policy_less_settlement_kept_null_policy', 'facts_are_minimal', 'facts_never_audited',
          'audit_actions', 'no_foreign_settlement', 'failed_settlements_left_no_head')


class AssessmentEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection, self.manifest = assets(); (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='assessment-evidence-', dir=ROOT/'.cache')
        self.addCleanup(self.temp.cleanup); self.path = Path(self.temp.name)/'report.json'
        self.tokens = {role+'_token': 'synthetic-'+role for role in ROLES}
        executions = []
        requests = {item['name']: item['request'] for item in self.collection['item']}
        for case in self.manifest['items']:
            url = urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
            headers = [] if case['role'] == 'anonymous' else [{'key': 'Authorization', 'value': 'Bearer '+self.tokens[case['role']+'_token']}]
            headers += copy.deepcopy(requests[case['name']].get('header', []))
            executions.append({'item': {'name': case['name']}, 'request': {'method': case['method'],
                'url': {'path': url.path.strip('/').split('/'), 'query': [{'key': k, 'value': v} for k, v in parse_qsl(url.query, keep_blank_values=True)]},
                'header': headers, **({'body': copy.deepcopy(requests[case['name']]['body'])} if 'body' in requests[case['name']] else {})},
                'response': {'code': case['status']}, 'assertions': [{'assertion': str(i)} for i in range(case['assertions'])]})
        self.report = {'run': {'executions': executions, 'failures': []}}

    def case(self, name):
        return next(x for x in self.report['run']['executions'] if x['item']['name'] == name)

    def check(self):
        self.path.write_text(json.dumps(self.report), encoding='utf-8')
        return audit(self.path, self.collection, self.manifest, self.tokens)

    def test_current_assets_default_disabled(self):
        self.assertEqual(json.loads(OUTPUT.read_text(encoding='utf-8')), self.collection)
        self.assertEqual(json.loads(MANIFEST.read_text(encoding='utf-8')), self.manifest)
        result = self.check()
        self.assertEqual((153, 530, OPERATION_COUNT), (result['requests'], result['assertions'], len(result['coverage'])))
        self.assertEqual((7, 2), (result['idempotent_replay_scopes_verified'], result['idempotency_changed_body_verified']))
        self.assertEqual('false', self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_every_operation_keeps_all_four_categories(self):
        coverage = {}
        for case in self.manifest['items']:
            coverage.setdefault(case['operation_id'], set()).add(case['kind'])
        self.assertEqual(OPERATION_COUNT, len(coverage))
        for operation, kinds in coverage.items():
            self.assertEqual({'normal', 'boundary', 'error', 'unauthorized'}, kinds, operation)

    def test_months_stay_literal_so_the_recorded_query_can_be_compared(self):
        self.assertEqual(('2024-01', '2999-01', '3000-01'), (CLOSED, FUTURE, ABSENT))
        for item in self.collection['item']:
            url = item['request']['url'].removeprefix('{{base_url}}')
            self.assertNotIn('{{', url, item['name'])
        self.assertIn('period_month=2024-01', json.dumps(self.collection, ensure_ascii=False))

    def test_role_tokens_cannot_be_replaced(self):
        original = copy.deepcopy(self.report)
        for name, role in [('policies_seeded', 'analyst'), ('admin_dashboard', 'assessor'),
                           ('merchant_month', 'owner'), ('merchant_staff_frozen', 'staff'),
                           ('merchant_policy', 'other_merchant'), ('business_merchants', 'assessor')]:
            self.report = copy.deepcopy(original)
            self.case(name)['request']['header'][0]['value'] = 'Bearer '+self.tokens[role+'_token']
            with self.assertRaises(ValueError):
                self.check()

    def test_strict_query_is_checked_on_actual_request(self):
        original = copy.deepcopy(self.report)
        for name in ['admin_month_bad_month', 'merchant_staff_merchant_param', 'admin_dashboard_missing_merchant',
                     'admin_snapshots_error', 'merchant_history_error']:
            self.report = copy.deepcopy(original)
            self.case(name)['request']['url']['query'].pop()
            with self.assertRaises(ValueError):
                self.check()

    def test_idempotency_scopes_cannot_be_collapsed(self):
        original = copy.deepcopy(self.report)
        for name in ['policy_create_replay', 'policy_create_changed_body', 'settle_replay',
                     'settle_changed_body', 'policy_publish_replay', 'policy_stop_replay']:
            self.report = copy.deepcopy(original)
            header = next(h for h in self.case(name)['request']['header'] if h['key'] == 'Idempotency-Key')
            header['value'] = '00000000-0000-4000-8000-000000000000'
            with self.assertRaises(ValueError):
                self.check()
        self.report = copy.deepcopy(original)
        header = next(h for h in self.case('settle')['request']['header'] if h['key'] == 'Idempotency-Key')
        header['value'] = 'not-a-uuid'
        with self.assertRaises(ValueError):
            self.check()

    def test_settlement_never_asserts_a_fabricated_score(self):
        def script(name):
            item = next(x for x in self.collection['item'] if x['name'] == name)
            return '\n'.join(line for event in item['event'] for line in event['script']['exec'])
        settle = script('settle')
        self.assertIn('s.total_score).to.eql(null)', settle)
        self.assertIn('s.grade).to.eql(null)', settle)
        self.assertIn('denominator:1,state:"UNKNOWN",score:null,reason:"SOURCE_UNKNOWN"', settle)
        self.assertIn('denominator:10,state:"KNOWN",score:"0.00",reason:"VERIFIED"', settle)
        self.assertIn('denominator:0,state:"UNKNOWN",score:null,reason:"POLICY_UNKNOWN"', script('settle_other_shop'))
        self.assertIn('d.policy).to.eql(null)', script('settle_other_shop'))

    def test_privilege_status_cannot_be_forged(self):
        original = copy.deepcopy(self.report)
        for name, code in [('policies_analyst_denied', 200), ('policies_seeded', 403),
                           ('admin_dashboard_assessor_denied', 200), ('merchant_month_staff_denied', 200),
                           ('merchant_policy_staff_denied', 200), ('merchant_snapshot_staff_denied', 200),
                           ('admin_month_analyst_denied', 200)]:
            self.report = copy.deepcopy(original)
            self.case(name)['response']['code'] = code
            with self.assertRaises(ValueError):
                self.check()

    def test_missing_duplicate_failed_skipped_partial_evidence_rejected(self):
        original = copy.deepcopy(self.report)
        for kind in ['missing', 'duplicate', 'failed', 'skipped', 'partial']:
            self.report = copy.deepcopy(original); run = self.report['run']
            if kind == 'missing':
                run['executions'].pop()
            elif kind == 'duplicate':
                run['executions'][-1] = copy.deepcopy(run['executions'][0])
            elif kind == 'failed':
                run['failures'] = [{'error': 'synthetic'}]
            elif kind == 'skipped':
                self.case('merchant_staff_self')['assertions'][-1]['skipped'] = True
            else:
                run['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):
                self.check()

    def test_database_invariants_stay_declared(self):
        source = (ROOT/'scripts/assessment_api_cases.py').read_text(encoding='utf-8')
        for name in CHECKS:
            self.assertIn("'"+name+"'", source)
        # 政策创建要求窗口晚于当前时间，所以已结束月份的政策只能是 SQL 种子；接口只能写未来窗口。
        self.assertEqual('2998-12-31T16:00:00Z', POLICY['valid_from'])
        self.assertEqual('2999-01-31T16:00:00Z', POLICY['valid_to'])
        self.assertIn("'2023-12-31 16:00:00','2998-12-31 16:00:00',1,'PUBLISHED'", source)

    def test_suite_cannot_share_database_with_any_other_mode(self):
        for flag in ['--owner-cases', '--fulfillment-cases', '--coupon-cases', '--merchant-cases', '--community-cases',
                     '--publication-cases', '--experience-cases', '--health-cases', '--export-cases', '--points-cases',
                     '--exchange-cases', '--invitation-cases', '--diagnosis-cases', '--discovery-cases', '--quotes-cases',
                     '--reviews-cases', '--finance-cases', '--workflow-cases', '--dispute-cases']:
            result = subprocess.run([sys.executable, str(ROOT/'scripts/run_api_acceptance.py'), '--assessment-cases', flag],
                                    capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertIn(b'separate fresh databases', result.stderr)

    def test_no_production_switch_is_enabled_for_this_suite(self):
        self.assertEqual({'WECHAT_APP_ID': 'fulfillment-cases'}, dict(environment(self.tokens)))


if __name__ == '__main__':
    unittest.main()
