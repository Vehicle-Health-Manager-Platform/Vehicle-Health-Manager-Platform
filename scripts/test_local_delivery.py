import subprocess
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from run_local_delivery import report, run_steps, source_digest, steps, ROOT


class LocalDeliveryTest(unittest.TestCase):
    def test_partial_run_never_claims_backend_or_production(self):
        record = report([{'name': 'miniapp-unit', 'status': 'passed'}], 'a', 'a', False)
        self.assertTrue(record['selected_checks_passed'])
        self.assertFalse(record['backend_runtime_verified'])
        self.assertFalse(record['production_acceptance_passed'])

    def test_backend_needs_both_execution_and_journey_evidence(self):
        rows = [{'name': 'backend-mysql-http', 'status': 'passed'}]
        self.assertFalse(report(rows, 'a', 'a', True)['backend_runtime_verified'])
        rows.append({'name': 'core-journeys-evidence', 'status': 'passed'})
        self.assertTrue(report(rows, 'a', 'a', True)['backend_runtime_verified'])
        rows[1]['status'] = 'failed'
        self.assertFalse(report(rows, 'a', 'a', True)['backend_runtime_verified'])

    def test_source_change_or_empty_run_fails(self):
        self.assertFalse(report([], 'a', 'a', False)['selected_checks_passed'])
        self.assertFalse(report([{'status': 'passed'}], 'a', 'b', False)['selected_checks_passed'])

    def test_digest_covers_ocr_worker_outside_src(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'apps').mkdir()
            for name in ('package.json', 'package-lock.json', 'backend/pom.xml', 'services/ocr/worker.py'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('first')
            before = source_digest(root)
            (root / 'services/ocr/worker.py').write_text('changed')
            self.assertNotEqual(before, source_digest(root))

    def test_asr_is_a_default_check_and_its_sources_are_bound(self):
        base = steps(ROOT)
        self.assertEqual(10, len(base))
        command = next(row[1] for row in base if row[0] == 'asr-regression')
        self.assertIn('services/asr', command)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'apps').mkdir()
            for name in ('package.json', 'package-lock.json', 'backend/pom.xml', 'services/asr/src/asr_process.py'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('first')
            before = source_digest(root)
            (root / 'services/asr/src/asr_process.py').write_text('changed')
            self.assertNotEqual(before, source_digest(root))
            requirements = root / 'services/asr/requirements.in'
            requirements.write_text('faster-whisper==1.2.1')
            before = source_digest(root)
            requirements.write_text('changed dependency')
            self.assertNotEqual(before, source_digest(root))

    def test_missing_command_does_not_hide_later_results(self):
        def execute(command, **kwargs):
            if command == ['missing']:
                raise FileNotFoundError('private diagnostic must stay out of report')
            return SimpleNamespace(returncode=0)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            rows = run_steps([('one', ['missing'], ROOT), ('two', ['ok'], ROOT)], output, {}, execute)
            self.assertEqual([r['status'] for r in rows], ['failed', 'passed'])
            self.assertNotIn('private diagnostic', (output / 'one.log').read_text())

    def test_timeout_and_nonzero_exit_fail(self):
        def execute(command, **kwargs):
            if command == ['timeout']:
                raise subprocess.TimeoutExpired(command, 1)
            return SimpleNamespace(returncode=3)
        with tempfile.TemporaryDirectory() as directory:
            rows = run_steps([('one', ['timeout'], ROOT), ('two', ['exit'], ROOT)], Path(directory), {}, execute)
            self.assertEqual([r['status'] for r in rows], ['failed', 'failed'])
            self.assertEqual(rows[1]['exit_code'], 3)

    def test_backend_invalidates_stale_junit_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / 'target/surefire-reports'
            reports.mkdir(parents=True)
            stale = reports / 'TEST-old.xml'
            stale.write_text('<testsuite/>')
            def execute(*args, **kwargs):
                self.assertFalse(stale.exists())
                return SimpleNamespace(returncode=1)
            run_steps([('backend-mysql-http', ['mvn'], root)], root, {}, execute)

    def test_locked_junit_fails_without_running_maven_and_keeps_other_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / 'target/surefire-reports'
            reports.mkdir(parents=True)
            (reports / 'TEST-old.xml').write_text('<testsuite/>')
            calls = []
            def execute(command, **kwargs):
                calls.append(command)
                return SimpleNamespace(returncode=0)
            with patch.object(Path, 'unlink', side_effect=PermissionError):
                rows = run_steps([('backend-mysql-http', ['mvn'], root), ('independent', ['ok'], root)], root, {}, execute)
            self.assertEqual([r['status'] for r in rows], ['failed', 'passed'])
            self.assertEqual(calls, [['ok']])

    def test_default_scope_excludes_backend_and_real_services(self):
        base = steps(ROOT)
        full = steps(ROOT, True)
        self.assertEqual(len(full), len(base) + 2)
        self.assertNotIn('backend-mysql-http', [row[0] for row in base])
        self.assertEqual(full[-1][1][-1], '--check-only')


if __name__ == '__main__':
    unittest.main()
