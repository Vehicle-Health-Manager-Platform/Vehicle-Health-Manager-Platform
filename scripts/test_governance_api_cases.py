"""Catalog governance and price evidence: fixed query literals, real identities, draft-only writes."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import urlsplit, parse_qsl
from governance_api_cases import (ROOT, OUTPUT, MANIFEST, OPERATION_COUNT, BRAND, SERIES, PROJECT, POLICY,
                                 assets, audit, environment)

ROLES = ('owner', 'other', 'merchant', 'other_merchant', 'technician', 'catalog', 'monitor', 'plain')
CHECKS = ('created_catalog_item_stays_draft', 'project_source_note_published', 'duplicate_series_never_created',
          'policy_final_state', 'policy_source_bound_to_current_quote', 'policy_hash_is_hex64',
          'policy_range_and_window', 'policy_publish_and_stop_stamped', 'one_published_policy_per_quote',
          'idempotent_receipts_kept', 'single_current_quote_evidence', 'catalog_draft_never_audited_as_published',
          'audit_actions', 'audit_request_ids_present')


class GovernanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.collection, self.manifest = assets(); (ROOT/'.cache').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='governance-evidence-', dir=ROOT/'.cache')
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
        self.assertEqual((56, 218, OPERATION_COUNT), (result['requests'], result['assertions'], len(result['coverage'])))
        self.assertEqual((6, 2), (result['idempotent_replay_scopes_verified'], result['idempotency_changed_body_verified']))
        self.assertEqual('false', self.collection['variable'][1]['value'])
        self.assertFalse(self.manifest['full_api_acceptance_passed'])

    def test_every_operation_keeps_all_four_categories(self):
        coverage = {}
        for case in self.manifest['items']:
            coverage.setdefault(case['operation_id'], set()).add(case['kind'])
        self.assertEqual(OPERATION_COUNT, len(coverage))
        for operation, kinds in coverage.items():
            self.assertEqual({'normal', 'boundary', 'error', 'unauthorized'}, kinds, operation)

    def test_queries_stay_literal_so_the_recorded_query_can_be_compared(self):
        for item in self.collection['item']:
            url = item['request']['url'].removeprefix('{{base_url}}')
            self.assertNotIn('{{', url, item['name'])
        self.assertIn('page_size=20', json.dumps(self.collection, ensure_ascii=False))
        self.assertIn('page=999999', json.dumps(self.collection, ensure_ascii=False))

    def test_price_hash_only_comes_from_the_quote_read(self):
        # 政策正文里的来源哈希必须是读取报价得到的集合变量，不能由用例自己编造。
        bodies = {item['name']: (item['request'].get('body') or {}).get('raw', '') for item in self.collection['item']}
        for name in ['policy_create', 'policy_create_replay', 'policy_update',
                     'policy_update_stale_revision', 'policy_update_missing_revision', 'policy_update_anonymous',
                     'policy_update_catalog_denied']:
            self.assertIn('{{quote_hash}}', bodies[name], name)
        script = '\n'.join(line for event in next(x for x in self.collection['item'] if x['name'] == 'quotes_ready_source')['event']
                           for line in event['script']['exec'])
        self.assertIn('pm.collectionVariables.set("quote_hash"', script)
        self.assertIn('pm.expect(q.state).to.eql("READY")', script)
        self.assertIn('pm.expect(q.state).to.eql("UNKNOWN")',
                      '\n'.join(line for event in next(x for x in self.collection['item'] if x['name'] == 'quotes_unknown_source')['event']
                                for line in event['script']['exec']))

    def test_catalog_writes_stay_draft_only(self):
        for body in (BRAND, SERIES):
            self.assertNotIn('status', body)
            self.assertNotIn('revision', body)
        self.assertEqual({'status': 1, 'revision': 1}, {k: PROJECT[k] for k in ('status', 'revision')})
        bodies = {item['name']: (item['request'].get('body') or {}).get('raw', '') for item in self.collection['item']}
        self.assertNotIn('status', json.loads(bodies['brand_create']))

    def test_policy_window_must_start_in_the_future(self):
        self.assertEqual(('2998-12-31T16:00:00Z', '2999-01-31T16:00:00Z'), (POLICY['valid_from'], POLICY['valid_to']))
        self.assertIs(True, POLICY['scope_confirmed'])
        bodies = {item['name']: (item['request'].get('body') or {}).get('raw', '') for item in self.collection['item']}
        self.assertIn('2023-12-31T16:00:00Z', bodies['policy_create_past_window'])
        self.assertIn('0'*64, bodies['policy_create_stale_hash'])

    def test_role_tokens_cannot_be_replaced(self):
        original = copy.deepcopy(self.report)
        for name, role in [('projects_list', 'monitor'), ('projects_list_monitor_denied', 'catalog'),
                           ('quotes_ready_source', 'catalog'), ('policy_create', 'catalog'),
                           ('policy_create_catalog_denied', 'monitor'), ('policies_list', 'plain'),
                           ('policy_publish', 'plain'), ('project_update_merchant_denied', 'catalog')]:
            self.report = copy.deepcopy(original)
            self.case(name)['request']['header'][0]['value'] = 'Bearer '+self.tokens[role+'_token']
            with self.assertRaises(ValueError):
                self.check()

    def test_strict_query_is_checked_on_actual_request(self):
        original = copy.deepcopy(self.report)
        for name in ['projects_list', 'quotes_page_size', 'policies_list_page_size', 'projects_list_last_page']:
            self.report = copy.deepcopy(original)
            self.case(name)['request']['url']['query'].pop()
            with self.assertRaises(ValueError):
                self.check()

    def test_idempotency_scopes_cannot_be_collapsed(self):
        original = copy.deepcopy(self.report)
        for name in ['brand_create_replay', 'brand_create_changed_body', 'project_update_replay', 'policy_create_replay',
                     'policy_create_changed_body', 'policy_update_replay', 'policy_publish_replay', 'policy_stop_replay']:
            self.report = copy.deepcopy(original)
            header = next(h for h in self.case(name)['request']['header'] if h['key'] == 'Idempotency-Key')
            header['value'] = '00000000-0000-4000-8000-000000000000'
            with self.assertRaises(ValueError):
                self.check()
        self.report = copy.deepcopy(original)
        header = next(h for h in self.case('policy_create')['request']['header'] if h['key'] == 'Idempotency-Key')
        header['value'] = 'not-a-uuid'
        with self.assertRaises(ValueError):
            self.check()

    def test_privilege_status_cannot_be_forged(self):
        original = copy.deepcopy(self.report)
        for name, code in [('projects_list_monitor_denied', 200), ('projects_list', 403), ('policy_create_catalog_denied', 200),
                           ('policies_list_plain_denied', 200), ('project_update_merchant_denied', 200),
                           ('policy_status_catalog_denied', 200), ('quotes_catalog_denied', 200)]:
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
                self.case('policy_publish')['assertions'][-1]['skipped'] = True
            else:
                run['executions'][0]['assertions'].pop()
            with self.assertRaises(ValueError):
                self.check()

    def test_database_invariants_stay_declared(self):
        source = (ROOT/'scripts/governance_api_cases.py').read_text(encoding='utf-8')
        for name in CHECKS:
            self.assertIn("'"+name+"'", source)
        self.assertIn("'1:STOPPED:4'", source)
        self.assertIn('R8B_PRICE_SOURCE_V1', source)

    def test_suite_cannot_share_database_with_any_other_mode(self):
        for flag in ['--owner-cases', '--fulfillment-cases', '--coupon-cases', '--merchant-cases', '--community-cases',
                     '--publication-cases', '--experience-cases', '--health-cases', '--export-cases', '--points-cases',
                     '--exchange-cases', '--invitation-cases', '--diagnosis-cases', '--discovery-cases', '--quotes-cases',
                     '--reviews-cases', '--finance-cases', '--workflow-cases', '--dispute-cases', '--assessment-cases']:
            result = subprocess.run([sys.executable, str(ROOT/'scripts/run_api_acceptance.py'), '--governance-cases', flag],
                                    capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertIn(b'separate fresh databases', result.stderr)

    def test_only_the_catalog_switch_is_enabled(self):
        self.assertEqual({'WECHAT_APP_ID': 'fulfillment-cases', 'CATALOG_GOVERNANCE_ENABLED': 'true'},
                         dict(environment(self.tokens)))


if __name__ == '__main__':
    unittest.main()
