"""Ensure missing/skipped/renamed journey evidence cannot satisfy the CI gate."""
import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from run_isolated_journeys import CASES, CLASS, ROOT, check_report


class JourneyEvidenceTest(unittest.TestCase):
    def setUp(self):
        cache = ROOT / '.cache'
        cache.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='journey-evidence-', dir=cache)
        self.addCleanup(self.cleanup)
        self.path = Path(self.temp.name) / 'report.xml'
        self.suite = ET.Element('testsuite', name=CLASS, tests='3', failures='0', errors='0', skipped='0')
        for name in sorted(CASES):
            ET.SubElement(self.suite, 'testcase', classname=CLASS, name=name)

    def cleanup(self):
        path = Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT / '.cache').resolve()) and path.name.startswith('journey-evidence-')
        self.temp.cleanup()

    def write(self):
        ET.ElementTree(self.suite).write(self.path, encoding='utf-8')
        return self.path

    def test_three_complete_journeys(self):
        self.assertEqual(3, check_report(self.write())['counts']['tests'])

    def test_failures_errors_skips_and_missing_counts(self):
        for field in ('failures', 'errors', 'skipped', 'tests'):
            with self.subTest(field=field):
                old = self.suite.get(field)
                self.suite.set(field, '1')
                with self.assertRaises(ValueError): check_report(self.write())
                self.suite.set(field, old)

    def test_failed_testcase_despite_green_totals(self):
        ET.SubElement(self.suite.find('testcase'), 'failure')
        with self.assertRaises(ValueError): check_report(self.write())

    def test_wrong_class_or_missing_journey(self):
        self.suite.find('testcase').set('name', 'unrelated')
        with self.assertRaises(ValueError): check_report(self.write())

    def test_duplicate_case_cannot_replace_missing_case(self):
        cases = self.suite.findall('testcase')
        cases[1].set('name', cases[0].get('name'))
        with self.assertRaises(ValueError): check_report(self.write())

    def test_missing_or_malformed_file(self):
        with self.assertRaises(FileNotFoundError): check_report(self.path)
        self.path.write_text('<bad>', encoding='utf-8')
        with self.assertRaises(ET.ParseError): check_report(self.path)


if __name__ == '__main__':
    unittest.main()
