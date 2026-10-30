import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import reaudit_current_api_acceptance as audit


class CurrentApiRawReauditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.child = self.root / '.cache/api-acceptance-fixture/report.json'
        self.child.parent.mkdir(parents=True)
        self.child.write_text('{}', encoding='utf-8')
        self.row = dict(kind='business', name='owner', passed=True, origin='EXECUTED',
                        report_sha256=audit.r.digest(self.child), requests=4, anonymous_requests=2)
        self.prior = dict(sources_unchanged=True, api_source_sha256='implementation', suites=[copy.deepcopy(self.row)])

    def run_audit(self):
        with patch.object(audit.r, 'ROOT', self.root), patch.object(audit.r, 'SUITES', ('owner',)), \
                patch.object(audit.r, 'source_hash', return_value='implementation'), \
                patch.object(audit.r, 'audit_business', return_value=self.row) as raw:
            result = audit.audit_business_rows(self.prior, {})
            raw.assert_called_once_with('owner', self.child, 'implementation', {})
            return result

    def test_raw_reaudit_preserves_counts_and_is_never_new_execution(self):
        result = self.run_audit()
        self.assertEqual('AUDIT_EXISTING', result[0]['origin'])
        self.assertEqual(4, result[0]['requests'])
        self.assertEqual('EXECUTED', self.prior['suites'][0]['origin'])

    def test_changed_source_failed_missing_or_duplicate_execution_is_rejected(self):
        original = copy.deepcopy(self.prior)
        for mutate in (lambda d: d.update(api_source_sha256='old'),
                       lambda d: d.update(sources_unchanged=False),
                       lambda d: d['suites'][0].update(passed=False),
                       lambda d: d.update(suites=[]),
                       lambda d: d['suites'].append(copy.deepcopy(d['suites'][0]))):
            self.prior = copy.deepcopy(original)
            mutate(self.prior)
            with self.assertRaises(ValueError):
                self.run_audit()

    def test_modified_child_report_or_changed_raw_audit_is_rejected(self):
        self.child.write_text('{"tampered":true}', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.run_audit()
        self.child.write_text('{}', encoding='utf-8')
        self.prior['suites'][0]['requests'] = 9000
        with self.assertRaises(ValueError):
            self.run_audit()


if __name__ == '__main__':
    unittest.main()
