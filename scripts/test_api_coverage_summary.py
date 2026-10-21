import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import summarize_api_coverage as coverage_module

from summarize_api_coverage import (ROOT, OUTPUT, SUMMARY, KINDS, build, case_coverage,
                                    journey_evidence, matches, newman_evidence, render, summarize, FINANCE_CHECKS)


class CoverageSummaryTest(unittest.TestCase):
    def setUp(self):
        self.spec = {'paths': {'/api/private/{id}': {'get': {'operationId': 'read', 'security': [{'bearerAuth': []}]}},
                               '/api/public': {'post': {'operationId': 'public', 'security': []}}}}
        self.catalog = {'read': {'method': 'GET', 'path': '/api/private/{id}'},
                        'public': {'method': 'POST', 'path': '/api/public'}}
        self.cases = [dict(name='c' + str(i), operation_id='read', method='GET', path='/api/private/1',
                           kind=kind, role='owner' if kind != 'unauthorized' else 'other',
                           status=404 if kind == 'unauthorized' else 200, assertions=3)
                      for i, kind in enumerate(KINDS)]
        self.manifest = {'version': 1, 'cases': 4, 'operations': 1, 'items': self.cases}
        self.collection = {'item': [dict(name=c['name'], request={'method': c['method'],
                                    'url': '{{base_url}}' + c['path'] + '?page=1'}) for c in self.cases]}
        self.coverage = {'read': sorted(KINDS)}
        self.report = {'passed': True, 'sources_unchanged': True, 'source_sha256': 'a' * 64,
                       'tiny_collection_sha256': 'b' * 64, 'tiny_newman_exit_code': 0,
                       'cleanup': [{'role': r, 'passed': True} for r in ('backend', 'mysql', 'network')],
                       'tiny_evidence': {'requests': 4, 'assertions': 12, 'failures': 0,
                                         'coverage': self.coverage, 'cases': [
                                             {k: v for k, v in c.items() if k in ('name', 'operation_id', 'kind', 'status')}
                                             | {'passed': True} for c in self.cases]}}

    def verify(self):
        return newman_evidence('tiny', self.manifest, self.collection, 'b' * 64, self.report, self.catalog)

    def test_full_recorded_evidence_is_historical_not_current_acceptance(self):
        defined, historical = self.verify()
        result = summarize(self.spec, [{'name': 'tiny', 'defined': defined, 'historical': historical}])
        self.assertEqual(1, result['counts']['historical_four_kind_operations'])
        self.assertEqual(0, result['counts']['current_run_operations'])
        self.assertFalse(result['full_api_acceptance_passed'])
        self.assertFalse(result['current_backend_revalidated'])
        self.assertTrue(all(r['current_acceptance'] == 'NOT_RUN' for r in result['operations']))

    def test_repeated_operations_across_suites_are_counted_once(self):
        suites = [{'name': str(i), 'defined': self.coverage, 'historical': self.coverage} for i in range(3)]
        result = summarize(self.spec, suites)
        self.assertEqual(1, result['counts']['historical_four_kind_operations'])
        row = next(r for r in result['operations'] if r['operation_id'] == 'read')
        self.assertEqual(3, len(row['sources']))

    def test_anonymous_only_never_creates_four_kind_coverage(self):
        result = summarize(self.spec, [{'name': 'auth', 'defined': {'read': ['unauthorized']},
                                       'historical': {'read': ['unauthorized']}}])
        self.assertEqual(0, result['counts']['historical_four_kind_operations'])
        self.assertEqual(2, result['counts']['without_complete_historical_evidence'])

    def test_absent_report_keeps_design_and_no_execution_credit(self):
        self.report = None
        defined, historical = self.verify()
        self.assertEqual(self.coverage, defined)
        self.assertEqual({}, historical)

    def test_failed_unstable_or_missing_source_run_is_rejected(self):
        original = copy.deepcopy(self.report)
        for change in ({'passed': False}, {'sources_unchanged': False}, {'source_sha256': ''}, {'tiny_newman_exit_code': 1}):
            self.report = original | change
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify()

    def test_collection_hash_or_request_drift_rejected(self):
        self.report['tiny_collection_sha256'] = 'c' * 64
        with self.assertRaises(ValueError): self.verify()
        self.report['tiny_collection_sha256'] = 'b' * 64
        for key, value in [('method', 'POST'), ('url', '{{base_url}}/api/public'), ('url', 'https://external.invalid/api/private/1')]:
            old = self.collection['item'][0]['request'][key]
            self.collection['item'][0]['request'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.verify()
            self.collection['item'][0]['request'][key] = old

    def test_missing_duplicate_failed_or_changed_recorded_case_rejected(self):
        original = copy.deepcopy(self.report)
        for mode in ('missing', 'duplicate', 'failed', 'skipped', 'category', 'status', 'operation'):
            self.report = copy.deepcopy(original)
            evidence = self.report['tiny_evidence']
            if mode == 'missing': evidence['cases'].pop()
            elif mode == 'duplicate': evidence['cases'].append(copy.deepcopy(evidence['cases'][0]))
            elif mode == 'failed': evidence['cases'][0]['passed'] = False
            elif mode == 'skipped': evidence['cases'][0]['skipped'] = True
            elif mode == 'category': evidence['cases'][0]['kind'] = 'boundary'
            elif mode == 'status': evidence['cases'][0]['status'] = 503
            else: evidence['cases'][0]['operation_id'] = 'public'
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.verify()

    def test_counts_summaries_and_cleanup_must_match(self):
        original = copy.deepcopy(self.report)
        for mode in ('requests', 'assertions', 'failures', 'coverage', 'cleanup', 'missing_role'):
            self.report = copy.deepcopy(original)
            evidence = self.report['tiny_evidence']
            if mode in ('requests', 'assertions', 'failures'): evidence[mode] += 1
            elif mode == 'coverage': evidence['coverage'] = {'read': ['normal']}
            elif mode == 'cleanup': self.report['cleanup'][0]['passed'] = False
            else: self.report['cleanup'].pop()
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.verify()

    def test_unknown_operation_route_category_or_duplicate_definition_rejected(self):
        original = copy.deepcopy(self.cases)
        for mode in ('operation', 'method', 'path', 'kind', 'duplicate', 'status', 'role'):
            rows = copy.deepcopy(original)
            if mode == 'operation': rows[0]['operation_id'] = 'not_in_contract'
            elif mode == 'method': rows[0]['method'] = 'POST'
            elif mode == 'path': rows[0]['path'] = '/api/private/1/unrelated'
            elif mode == 'kind': rows[0]['kind'] = 'invented'
            elif mode == 'status': rows[0]['status'] = True
            elif mode == 'role': rows[0]['role'] = ''
            else: rows.append(copy.deepcopy(rows[0]))
            with self.subTest(mode=mode), self.assertRaises(ValueError): case_coverage(rows, self.catalog)

    def test_invalid_manifest_counts_or_assertions_rejected(self):
        self.manifest['cases'] = 5
        with self.assertRaises(ValueError): self.verify()
        self.manifest['cases'] = 4
        self.manifest['items'][0]['assertions'] = -1
        with self.assertRaises(ValueError): self.verify()

    def test_path_matching_is_anchored_escapes_literals_and_allows_queries(self):
        self.assertTrue(matches('/api/private/{id}', '/api/private/1?owner_id=2'))
        self.assertTrue(matches('/api/private/{id}', '/api/private/{{dynamic_id}}'))
        self.assertFalse(matches('/api/private/{id}', '/api/private/1/extra'))
        self.assertFalse(matches('/api/file.json', '/api/fileXjson'))

    def test_output_never_copies_raw_execution_fields(self):
        self.report['private_token'] = 'private-test-value'
        defined, historical = self.verify()
        result = summarize(self.spec, [{'name': 'tiny', 'defined': defined, 'historical': historical}])
        self.assertNotIn('private-test-value', json.dumps(result))

    def finance_report(self):
        report=copy.deepcopy(self.report)
        for suffix in ['collection_sha256','newman_exit_code','evidence']:
            report['finance_'+suffix]=report.pop('tiny_'+suffix)
        report['finance_supplier']={'passed':True,'counts':{'payment_1_queries':1,'payment_4_queries':1,'fresh_refund_queries':1,'fresh_refund_posts':1,'failed_refund_queries':1,'failed_refund_posts':0,'invalid_submissions':0}}
        report['finance_database']={'checks':{k:True for k in FINANCE_CHECKS}}
        return report

    def test_finance_counts_and_exact_database_checks_required(self):
        report=self.finance_report()
        verify=lambda:newman_evidence('finance',self.manifest,self.collection,'b'*64,report,self.catalog)
        self.assertEqual(self.coverage,verify()[1])
        report['finance_supplier']['counts']['failed_refund_posts']=1
        with self.assertRaises(ValueError):verify()
        report=self.finance_report();report['finance_database']['checks'].pop('commission_reversed_once')
        report['finance_database']['checks']['unrelated']=True
        with self.assertRaises(ValueError):verify()

    def test_finance_top_level_pass_cannot_replace_missing_supplier_proof(self):
        report=self.finance_report();del report['finance_supplier']
        with self.assertRaises(ValueError):
            newman_evidence('finance',self.manifest,self.collection,'b'*64,report,self.catalog)


class JourneySummaryTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {'read': {'method': 'GET', 'path': '/api/file/{id}/access'}}
        self.expected = [dict(name=str(i), method='GET', path='/api/file/1/access',
                              operation='/api/file/{id}/access', role='owner', status=200,
                              kind=kind, passed=True) for i, kind in enumerate(KINDS)]
        self.report = {'passed': True, 'sources_unchanged': True, 'source_sha256': 'a' * 64,
                       'maven_exit_code': 0, 'proof': {'requests': 4, 'operations': 1, 'cases': copy.deepcopy(self.expected),
                       'coverage': {'/api/file/{id}/access': sorted(KINDS)}, 'checks': {'signature': True},
                       'cleanup': {k: True for k in ('initializer', 'clamav', 'minio', 'mysql', 'network')}}}

    def verify(self):
        return journey_evidence(self.expected, self.report, self.catalog, {'signature'})

    def test_path_only_and_method_path_summaries_resolve_to_contract_operation(self):
        self.assertEqual({'read': sorted(KINDS)}, self.verify()[1])
        self.report['proof']['coverage'] = {'GET /api/file/{id}/access': sorted(KINDS)}
        self.assertEqual({'read': sorted(KINDS)}, self.verify()[1])

    def test_inventory_is_not_evidence_without_report(self):
        self.report = None
        self.assertEqual({}, self.verify()[1])

    def test_mismatched_role_or_missing_check_and_cleanup_rejected(self):
        original = copy.deepcopy(self.report)
        for mode in ('role', 'check', 'missing_check', 'cleanup', 'count', 'coverage'):
            self.report = copy.deepcopy(original)
            proof = self.report['proof']
            if mode == 'role': proof['cases'][0]['role'] = 'other'
            elif mode == 'check': proof['checks']['signature'] = False
            elif mode == 'missing_check': proof['checks'] = {'different': True}
            elif mode == 'cleanup': del proof['cleanup']['mysql']
            elif mode == 'count': proof['requests'] = 1
            else: proof['coverage'] = {'/api/file/{id}/access': ['normal']}
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.verify()

    def test_ambiguous_methodless_summary_and_unknown_inventory_fail(self):
        self.catalog['write'] = {'method': 'POST', 'path': '/api/file/{id}/access'}
        # Both methods for the same template cannot share a methodless summary key.
        extra = [dict(c, name='post' + c['name'], method='POST') for c in self.expected]
        self.expected += extra
        self.report['proof']['cases'] = copy.deepcopy(self.expected)
        self.report['proof']['requests'] = 8
        self.report['proof']['operations'] = 2
        with self.assertRaises(ValueError): self.verify()
        self.expected[0]['operation'] = '/api/unknown'
        with self.assertRaises(ValueError): self.verify()


class TrackedCoverageTest(unittest.TestCase):
    def test_linux_lf_checkout_and_windows_crlf_produce_identical_summary(self):
        expected = build()
        files = {'docs/api/openapi.json'}
        for suite in expected['suites']:
            files.update(suite['inputs'])
        cache = ROOT / '.cache'
        cache.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='coverage-lf-', dir=cache)
        destination = Path(temporary.name).resolve()
        self.assertTrue(destination.is_relative_to(cache.resolve()))
        self.assertTrue(destination.name.startswith('coverage-lf-'))
        try:
            for name in files:
                target = destination / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / name).read_bytes().replace(b'\r\n', b'\n'))
            self.assertEqual(expected, build(destination))
            for name in files:
                target = destination / name
                target.write_bytes(target.read_bytes().replace(b'\n', b'\r\n'))
            self.assertEqual(expected, build(destination))
            changed = destination / 'docs/testing/postman/owner-cases.postman_collection.json'
            document = json.loads(changed.read_text(encoding='utf-8'))
            document['info']['description'] = 'changed content, not just line endings'
            changed.write_text(json.dumps(document), encoding='utf-8')
            with self.assertRaises(ValueError):
                build(destination)
        finally:
            temporary.cleanup()

    def test_check_mode_rejects_stale_outputs_without_writing(self):
        output, summary = mock.Mock(), mock.Mock()
        output.name = 'api-coverage.json'
        output.is_file.return_value = True
        output.read_text.return_value = 'stale'
        with mock.patch.object(coverage_module, 'OUTPUT', output), mock.patch.object(coverage_module, 'SUMMARY', summary), \
                mock.patch.object(coverage_module, 'build', return_value={'counts': {}}), \
                mock.patch.object(coverage_module, 'render', return_value='summary'), \
                mock.patch('sys.argv', ['summarize_api_coverage.py', '--check']), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                coverage_module.main()
            self.assertEqual(1, error.exception.code)
        output.write_text.assert_not_called()
        summary.write_text.assert_not_called()

    def test_all_selected_repository_sources_reconcile_without_current_pass_claim(self):
        result = build()
        self.assertEqual(352, result['counts']['contract_operations'])
        self.assertEqual(20, result['counts']['suites'])
        self.assertEqual(222, result['counts']['historical_four_kind_operations'])
        self.assertEqual(130, result['counts']['without_complete_historical_evidence'])
        self.assertFalse(result['full_api_acceptance_passed'])
        self.assertEqual(result, json.loads(OUTPUT.read_text(encoding='utf-8')))
        self.assertEqual(render(result), SUMMARY.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
