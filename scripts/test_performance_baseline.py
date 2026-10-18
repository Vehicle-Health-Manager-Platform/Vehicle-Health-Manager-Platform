"""Ensure the performance evidence cannot be satisfied by missing, edited or half-enforced numbers."""
import json
import tempfile
from pathlib import Path
import unittest
from run_performance_baseline import CONCURRENCY, ROOT, TABLES, THRESHOLDS, percentile, summarize, validate


def latency(p95=120.0, p99=200.0):
    return {'samples': CONCURRENCY, 'min_ms': 10.0, 'mean_ms': 80.0, 'p50_ms': 70.0, 'p90_ms': 110.0,
            'p95_ms': p95, 'p99_ms': p99, 'max_ms': p99 + 5.0}


def scenario(name, p95=120.0, p99=200.0, errors=0, gates='all'):
    threshold = THRESHOLDS[name]
    models = {'owner_home': 2, 'merchant_list': 1, 'order_create': 1}
    checks = [
        {'name': 'requests_completed', 'value': CONCURRENCY, 'expected': CONCURRENCY, 'passed': True},
        {'name': 'error_rate_below_0.1%', 'value': errors / CONCURRENCY, 'expected': 0.001,
         'passed': errors / CONCURRENCY < 0.001},
        {'name': 'p95_target', 'value': p95, 'expected': threshold['p95_ms'],
         'passed': p95 < threshold['p95_ms']},
    ]
    if threshold['p99_ms'] is not None:
        checks.append({'name': 'p99_target', 'value': p99, 'expected': threshold['p99_ms'],
                       'passed': p99 < threshold['p99_ms']})
    enforced = [c for c in checks if gates == 'all' or not c['name'].endswith('_target')]
    return {'id': name, 'title': name, 'scope': '读', 'concurrency': CONCURRENCY, 'rounds': 1,
            'warmup_requests': CONCURRENCY, 'http_requests_per_sample': models[name],
            'http_requests': CONCURRENCY * models[name], 'requests': CONCURRENCY, 'errors': errors,
            'error_kinds': {}, 'error_rate': errors / CONCURRENCY, 'duration_seconds': 10.0,
            'throughput_samples_per_second': 50.0, 'latency_ms': latency(p95, p99),
            'thresholds': threshold, 'checks': checks,
            'latency_target_met': all(c['passed'] for c in checks if c['name'].endswith('_target')),
            'enforced_checks': [c['name'] for c in enforced],
            'passed': all(c['passed'] for c in enforced)}


def preflight():
    return {'requests': 20, 'errors': 0, 'error_kinds': {},
            'latency_ms': {'samples': 20, 'min_ms': 5.0, 'mean_ms': 9.0, 'p50_ms': 9.0, 'p90_ms': 12.0,
                           'p95_ms': 13.0, 'p99_ms': 14.0, 'max_ms': 15.0}}


def report(gates='all', **overrides):
    scenarios = [scenario(name, gates=gates) for name in ('owner_home', 'merchant_list', 'order_create')]
    value = {'version': 1, 'kind': 'performance-baseline', 'gates': gates,
             'passed': all(s['passed'] for s in scenarios), 'scenarios': scenarios,
             'dataset': {'rows_total': 9876}, 'method': {'concurrency': CONCURRENCY},
             'tables': TABLES, 'boundaries': ['synthetic data only'],
             'preflight': {name: preflight() for name in THRESHOLDS},
             'cleanup': [{'role': 'mysql', 'passed': True}, {'role': 'network', 'passed': True}]}
    value.update(overrides)
    return value


class PerformanceEvidenceTest(unittest.TestCase):
    def setUp(self):
        cache = ROOT / '.cache'
        cache.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='performance-evidence-', dir=cache)
        self.addCleanup(self.cleanup)
        self.path = Path(self.temp.name) / 'report.json'

    def cleanup(self):
        path = Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT / '.cache').resolve()) and path.name.startswith('performance-evidence-')
        self.temp.cleanup()

    def write(self, value):
        self.path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
        return self.path

    def test_nearest_rank_percentiles(self):
        ordered = sorted(float(value) for value in range(1, 101))
        self.assertEqual(50.0, percentile(ordered, 50))
        self.assertEqual(95.0, percentile(ordered, 95))
        self.assertEqual(99.0, percentile(ordered, 99))
        self.assertEqual(100.0, percentile(ordered, 100))
        self.assertEqual(1.0, percentile(ordered, 1))

    def test_summary_is_ordered_and_keeps_all_samples(self):
        summary = summarize([5.0, 1.0, 3.0, 9.0, 7.0])
        self.assertEqual(5, summary['samples'])
        self.assertEqual({'min_ms': 1.0, 'mean_ms': 5.0, 'p50_ms': 5.0, 'p90_ms': 9.0,
                          'p95_ms': 9.0, 'p99_ms': 9.0, 'max_ms': 9.0},
                         {key: summary[key] for key in ('min_ms', 'mean_ms', 'p50_ms', 'p90_ms',
                                                        'p95_ms', 'p99_ms', 'max_ms')})

    def test_valid_report_passes(self):
        self.assertTrue(validate(self.write(report()))['passed'])

    def test_missing_scenario_or_extra_scenario(self):
        value = report()
        value['scenarios'] = value['scenarios'][:2]
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_sample_count_must_match_concurrency_times_rounds(self):
        value = report()
        value['scenarios'][0]['rounds'] = 2
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_threshold_drift_is_rejected(self):
        value = report()
        value['scenarios'][2]['thresholds'] = {'p95_ms': 5000.0, 'p99_ms': None}
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_unknown_gate_policy(self):
        with self.assertRaises(ValueError): validate(self.write(report(gates='lenient')))

    def test_error_budget_breach_is_visible_in_the_verdict(self):
        value = report()
        value['scenarios'][1] = scenario('merchant_list', errors=3)
        value['passed'] = False
        self.assertFalse(validate(self.write(value))['passed'])

    def test_failed_scenario_cannot_be_declared_passing(self):
        value = report()
        value['scenarios'][1] = scenario('merchant_list', errors=3)
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_latency_miss_fails_under_all_gates_but_is_recorded_under_error_gates(self):
        strict = report()
        strict['scenarios'][0] = scenario('owner_home', p95=450.0, p99=1200.0)
        strict['passed'] = False
        self.assertFalse(validate(self.write(strict))['passed'])
        lenient = report(gates='errors')
        lenient['scenarios'][0] = scenario('owner_home', p95=450.0, p99=1200.0, gates='errors')
        self.assertTrue(validate(self.write(lenient))['passed'])
        self.assertEqual(['requests_completed', 'error_rate_below_0.1%'],
                         lenient['scenarios'][0]['enforced_checks'])

    def test_inconsistent_verdict_is_rejected(self):
        value = report()
        value['scenarios'][0]['passed'] = False
        value['passed'] = False
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_cleanup_and_dataset_evidence_are_required(self):
        for field, replacement in (('cleanup', []), ('dataset', {'rows_total': 0}),
                                   ('tables', 0), ('boundaries', [])):
            with self.subTest(field=field):
                value = report()
                value[field] = replacement
                with self.assertRaises(ValueError): validate(self.write(value))

    def test_method_must_record_concurrency(self):
        value = report()
        value['method'] = {'concurrency': 100}
        with self.assertRaises(ValueError): validate(self.write(value))

    def test_preflight_failure_blocks_the_evidence(self):
        value = report()
        value['preflight']['order_create']['errors'] = 1
        with self.assertRaises(ValueError): validate(self.write(value))
        missing = report()
        del missing['preflight']['merchant_list']
        with self.assertRaises(ValueError): validate(self.write(missing))


if __name__ == '__main__':
    unittest.main()
