"""Contract verdicts stay fail-closed and never hide real evidence gaps."""
import copy
import json
import unittest
from unittest import mock
from reconcile_contract import (OUTPUT, TOTAL_CONTRACT, MIN_EVIDENCED, evidenced,
                                load_verdicts, operations, reconcile, render, validate)

import reconcile_contract as module


def load():
    contract = json.loads(module.CONTRACT.read_text(encoding='utf-8'))
    coverage = json.loads(module.COVERAGE.read_text(encoding='utf-8'))
    rows = operations(contract)
    return rows, evidenced(coverage), load_verdicts()


class ContractReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.operations, self.complete, self.rows = load()

    def reject(self, rows):
        return validate(self.operations, self.complete, rows)

    def test_every_contract_operation_is_evidenced_or_explained(self):
        contract, coverage, operations_, complete, rows, kinds = reconcile()
        self.assertEqual(TOTAL_CONTRACT, len(operations_))
        self.assertEqual(len(operations_), len(complete) + len(rows))
        self.assertGreaterEqual(len(complete), MIN_EVIDENCED)
        self.assertEqual(37, len(rows))
        self.assertEqual({'implemented-local': 2, 'superseded': 15, 'internal-only': 1, 'blocked-external': 11,
                          'not-implemented': 4, 'no-success-path': 1, 'dev-only': 3}, dict(kinds))
        self.assertFalse(coverage['full_api_acceptance_passed'])

    def test_committed_report_matches_generator(self):
        contract, coverage, operations_, complete, rows, kinds = reconcile()
        self.assertEqual(render(contract, coverage, operations_, complete, rows, kinds),
                         OUTPUT.read_text(encoding='utf-8'))

    def test_missing_verdict_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows.pop()
        with self.assertRaises(ValueError) as error:
            self.reject(rows)
        self.assertIn('Unresolved contract operations', str(error.exception))

    def test_unknown_or_duplicate_operation_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows.append({'operation': 'GET /api/not-a-path', 'verdict': 'not-implemented',
                     'reason': 'synthetic verdict for a path that does not exist'})
        with self.assertRaises(ValueError):
            self.reject(rows)
        duplicate = copy.deepcopy(self.rows)
        duplicate.append(copy.deepcopy(duplicate[0]))
        with self.assertRaises(ValueError) as error:
            self.reject(duplicate)
        self.assertIn('Duplicate verdict', str(error.exception))

    def test_superseded_needs_an_existing_classified_replacement(self):
        base = next(row for row in self.rows if row['verdict'] == 'superseded')
        for removal in ('missing', 'outside', 'unclassified', 'itself'):
            rows = copy.deepcopy(self.rows)
            row = next(item for item in rows if item['operation'] == base['operation'])
            if removal == 'missing':
                row.pop('replacement')
            elif removal == 'outside':
                row['replacement'] = 'GET /api/not-a-path'
            elif removal == 'unclassified':
                row['replacement'] = 'GET /api/notifications'
            else:
                row['replacement'] = row['operation']
            with self.subTest(removal=removal), self.assertRaises(ValueError):
                self.reject(rows)

    def test_only_superseded_may_name_a_replacement(self):
        rows = copy.deepcopy(self.rows)
        row = next(item for item in rows if item['verdict'] == 'not-implemented')
        row['replacement'] = 'GET /api/vehicle/reminders'
        with self.assertRaises(ValueError):
            self.reject(rows)

    def test_verdict_cannot_hide_an_evidenced_operation(self):
        rows = copy.deepcopy(self.rows)
        rows.append({'operation': 'GET /api/vehicle/reminders', 'verdict': 'dev-only',
                     'reason': 'a verdict must never stand in for real evidence'})
        with self.assertRaises(ValueError) as error:
            self.reject(rows)
        self.assertIn('already evidenced', str(error.exception))

    def test_unknown_kind_or_short_reason_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows[0]['verdict'] = 'looks-fine'
        with self.assertRaises(ValueError):
            self.reject(rows)
        rows = copy.deepcopy(self.rows)
        rows[0]['reason'] = 'ok'
        with self.assertRaises(ValueError):
            self.reject(rows)

    def test_lowercase_method_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows[0]['operation'] = rows[0]['operation'].replace('GET', 'get', 1)
        with self.assertRaises(ValueError):
            self.reject(rows)

    def test_contract_count_and_evidence_floor_are_guarded(self):
        with mock.patch.object(module, 'TOTAL_CONTRACT', TOTAL_CONTRACT + 1), self.assertRaises(ValueError):
            reconcile()
        with mock.patch.object(module, 'MIN_EVIDENCED', MIN_EVIDENCED + 1), self.assertRaises(ValueError):
            reconcile()

    def test_check_mode_detects_a_stale_report(self):
        with mock.patch.object(module, 'OUTPUT', module.ROOT/'docs/api/.synthetic-reconciliation.md'):
            with self.assertRaises(SystemExit):
                module.main(['--check'])


if __name__ == '__main__':
    unittest.main()
