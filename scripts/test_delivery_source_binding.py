"""Regression: runtime configuration changes must invalidate delivery evidence."""
from pathlib import Path
import tempfile
import unittest

from run_local_delivery import source_digest, steps
from run_core_coverage import digest_sources
from unittest.mock import patch
import run_core_coverage


class DeliverySourceBindingTest(unittest.TestCase):
    def test_backend_entry_uses_strict_full_gate(self):
        root = Path(__file__).resolve().parents[1]
        command = next(command for name, command, _ in steps(root, True)
                       if name == 'backend-mysql-http')
        self.assertTrue(command[1].endswith('run_core_coverage.py'))
        self.assertIn('--full', command)
        self.assertFalse(any(value.startswith('-Dtest=') for value in command))

    def test_runtime_properties_invalidate_both_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'apps').mkdir()
            for name in ('package.json', 'package-lock.json', 'backend/pom.xml',
                         'backend/src/main/resources/application.properties'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('before')
            with patch.object(run_core_coverage, 'ROOT', root):
                before = (source_digest(root), digest_sources())
                (root / 'backend/src/main/resources/application.properties').write_text('after')
                after = (source_digest(root), digest_sources())
            self.assertNotEqual(before[0], after[0])
            self.assertNotEqual(before[1], after[1])

    def test_existing_yaml_and_migrations_invalidate_core_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / 'backend/src/main/resources/application.yml',
                     root / 'backend/pom.xml', root / 'docs/sql/migrations/V001__baseline.sql']
            for path in paths:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('before')
            with patch.object(run_core_coverage, 'ROOT', root):
                for path in paths:
                    with self.subTest(path=path.name):
                        before = digest_sources()
                        path.write_text('after')
                        self.assertNotEqual(before, digest_sources())


if __name__ == '__main__':
    unittest.main()
