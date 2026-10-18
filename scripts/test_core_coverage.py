"""Meaningful failure-path tests for the quality gate; no JaCoCo or Docker needed."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import xml.etree.ElementTree as ET
from check_core_coverage import PREFIX, audit_tests, evaluate, load_scope
import run_core_coverage

class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.cache = Path(__file__).resolve().parents[1] / '.cache'
        self.cache.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=self.cache, prefix='core-gate-test-')
        self.addCleanup(self.safe_cleanup)
        self.root = Path(self.temp.name)
        self.scope = {'version': 1, 'metric': 'LINE', 'minimum_percent': 70, 'minimum_method_percent':70,
                      'groups': {'one': ['order/First'], 'two': ['vehicle/Second']}, 'local_tests': ['CoreTest']}
        for name in ['order/First', 'vehicle/Second']:
            source = self.root / (PREFIX + name + '.java'); source.parent.mkdir(parents=True, exist_ok=True); source.write_text('// fixture')
    def safe_cleanup(self):
        self.assertTrue(self.root.resolve().is_relative_to(self.cache.resolve()))
        self.assertTrue(self.root.name.startswith('core-gate-test-'))
        self.temp.cleanup()
    def xml(self, first=(3,7), second=(3,7), extra=None):
        report = ET.Element('report')
        package = ET.SubElement(report, 'package', name='fixture')
        for name, count in [('order/First', first), ('vehicle/Second', second), *(extra or [])]:
            clazz = ET.SubElement(package, 'class', name=PREFIX+name)
            ET.SubElement(clazz, 'counter', type='LINE', missed=str(count[0]), covered=str(count[1]))
            ET.SubElement(clazz, 'counter', type='METHOD', missed='0', covered='1')
        path = self.root/'jacoco.xml'; ET.ElementTree(report).write(path); return path
    def load(self, scope=None):
        path = self.root/'scope.json'; path.write_text(json.dumps(scope or self.scope)); return load_scope(path, self.root)
    def junit(self, tests='1', skipped='0', failures='0', name='fixture.CoreTest'):
        path = self.root/'TEST-core.xml'
        ET.ElementTree(ET.Element('testsuite', name=name, tests=tests, skipped=skipped, failures=failures, errors='0')).write(path)
        return path
    def test_exact_threshold_passes_and_group_failure_cannot_hide_behind_total(self):
        self.assertTrue(evaluate(self.xml(), self.load())['passed'])
        report = evaluate(self.xml(first=(4,6),second=(0,100)),self.load())
        self.assertGreater(report['overall']['percent'],70); self.assertFalse(report['passed']); self.assertEqual(['one: LINE below 70%'],report['failures'])
    def test_rounding_never_turns_below_threshold_into_success(self):
        report=evaluate(self.xml(first=(30001,69999)),self.load());self.assertEqual(70.0,report['groups']['one']['line']['percent']);self.assertFalse(report['passed'])
    def test_high_line_coverage_cannot_hide_unexecuted_core_methods(self):
        path=self.xml(first=(0,100),second=(0,100));tree=ET.parse(path)
        for counter in tree.findall('.//class/counter'):
            if counter.get('type')=='METHOD':counter.set('missed','9');counter.set('covered','1')
        tree.write(path);report=evaluate(path,self.load());self.assertFalse(report['passed']);self.assertTrue(report['overall']['passed']);self.assertEqual(10.0,report['overall_method']['percent'])
    def test_all_nested_classes_count_but_similar_unrelated_prefix_does_not(self):
        report=evaluate(self.xml(extra=[('order/First$Inner',(100,0)),('order/FirstFake',(0,9999))]),self.load())
        self.assertEqual(2,report['groups']['one']['classes']['order/First']['member_count']);self.assertEqual(110,report['groups']['one']['line']['total']);self.assertFalse(report['passed'])
    def test_missing_classes_and_zero_executable_lines_fail_closed(self):
        scope=self.load();scope['groups']['one']=['order/Missing']
        with self.assertRaises(ValueError):evaluate(self.xml(),scope)
        with self.assertRaises(ValueError):evaluate(self.xml(first=(0,0)),self.load())
    def test_duplicate_classes_negative_missing_and_noninteger_counters_rejected(self):
        with self.assertRaises(ValueError):evaluate(self.xml(extra=[('order/First',(0,10))]),self.load())
        for value in ['-1','abc','1.5','99999999999999999999999']:
            path=self.xml();tree=ET.parse(path);tree.find('.//counter').set('missed',value);tree.write(path)
            with self.assertRaises(ValueError):evaluate(path,self.load())
    def test_duplicate_counter_and_wrong_document_are_rejected(self):
        path=self.xml();tree=ET.parse(path);ET.SubElement(tree.find('.//class'),'counter',type='LINE',missed='0',covered='10');tree.write(path)
        with self.assertRaises(ValueError):evaluate(path,self.load())
        path.write_text('<not-report/>')
        with self.assertRaises(ValueError):evaluate(path,self.load())
        path=self.xml();tree=ET.parse(path);ET.SubElement(tree.find('.//class'),'counter',type='UNKNOWN',missed='0',covered='1');tree.write(path)
        with self.assertRaises(ValueError):evaluate(path,self.load())
    def test_scope_cannot_duplicate_classes_use_traversal_or_reference_absent_sources(self):
        for name in ['../outside','order/Unknown','order/First']:
            scope=json.loads(json.dumps(self.scope));scope['groups']['two']=[name]
            with self.assertRaises(ValueError):self.load(scope)
        for value in [0,1,69,101,True,69.5]:
            for field in ['minimum_percent','minimum_method_percent']:
                scope={**self.scope,field:value}
                with self.assertRaises(ValueError):self.load(scope)
    def test_missing_corrupt_and_empty_xml_fail(self):
        with self.assertRaises(OSError):evaluate(self.root/'missing.xml',self.load())
        path=self.root/'broken.xml';path.write_text('<report>')
        with self.assertRaises(ET.ParseError):evaluate(path,self.load())
    def test_junit_must_be_real_successful_nonempty_and_contain_required_suites(self):
        with self.assertRaises(ValueError):audit_tests(self.root,['CoreTest'])
        self.junit();self.assertEqual(1,audit_tests(self.root,['CoreTest'])['tests'])
        for options in [{'tests':'0'},{'skipped':'1'},{'failures':'1'},{'tests':'unknown'},{'name':'fixture.OtherTest'}]:
            self.junit(**options)
            with self.assertRaises(ValueError):audit_tests(self.root,['CoreTest'])

class CoverageRunnerTest(unittest.TestCase):
    def setUp(self):
        self.cache=Path(__file__).resolve().parents[1]/'.cache';self.cache.mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=self.cache,prefix='core-runner-test-');self.root=Path(self.temp.name)
        self.addCleanup(self.cleanup)
        source=self.root/'backend/src/main/java'/ (PREFIX+'order/First.java');source.parent.mkdir(parents=True);source.write_text('// fixture')
        scope={'version':1,'metric':'LINE','minimum_percent':70,'minimum_method_percent':70,'groups':{'one':['order/First']},'local_tests':['CoreTest']}
        (self.root/'scripts').mkdir();(self.root/'scripts/core_coverage_scope.json').write_text(json.dumps(scope))
        self.target=self.root/'backend/target';self.target.mkdir()
        (self.target/'core-coverage-summary.json').write_text('{"passed":true}')
    def cleanup(self):
        self.assertTrue(self.root.resolve().is_relative_to(self.cache.resolve()));self.assertTrue(self.root.name.startswith('core-runner-test-'));self.temp.cleanup()
    def junit(self,skipped='0'):
        folder=self.target/'surefire-reports';folder.mkdir(exist_ok=True)
        ET.ElementTree(ET.Element('testsuite',name='fixture.CoreTest',tests='1',failures='0',errors='0',skipped=skipped)).write(folder/'TEST-core.xml')
    def run_with(self,action,digests=None):
        with patch.object(run_core_coverage,'ROOT',self.root),patch('sys.argv',['run_core_coverage.py']),patch.object(run_core_coverage.subprocess,'check_output',return_value='synthetic-source-head'),patch.object(run_core_coverage.subprocess,'run',side_effect=action) as command:
            if digests:
                with patch.object(run_core_coverage,'digest_sources',side_effect=digests):code=run_core_coverage.main()
            else:code=run_core_coverage.main()
        return code,command
    def test_missing_maven_removes_old_green_and_never_runs_checker(self):
        code,command=self.run_with(OSError('tool unavailable'));self.assertEqual(1,code);self.assertEqual(1,command.call_count)
        self.assertFalse((self.target/'core-coverage-summary.json').exists());record=json.loads((self.target/'core-coverage-run.json').read_text());self.assertEqual(127,record['maven_exit_code'])
    def test_successful_measurement_runs_clean_and_gates_exactly_once(self):
        def execute(command,**kwargs):
            if command[0]=='mvn':self.assertIn('clean',command);self.assertIn('verify',command);self.assertIn('-Dtest=CoreTest',command);self.junit()
            return SimpleNamespace(returncode=0)
        code,command=self.run_with(execute);self.assertEqual(0,code);self.assertEqual(2,command.call_count)
        self.assertTrue(json.loads((self.target/'core-coverage-run.json').read_text())['sources_unchanged'])
    def test_failed_skipped_or_changing_tests_do_not_run_coverage_checker(self):
        for failure,skipped,digests in [(1,'0',None),(0,'1',None),(0,'0',['before','after'])]:
            with self.subTest(failure=failure,skipped=skipped):
                def execute(command,**kwargs):self.junit(skipped);return SimpleNamespace(returncode=failure)
                code,command=self.run_with(execute,digests);self.assertEqual(1,code);self.assertEqual(1,command.call_count)
    def test_invalid_scope_cannot_leave_a_previous_pass(self):
        (self.root/'scripts/core_coverage_scope.json').write_text('[]')
        with self.assertRaises(ValueError):self.run_with(None)
        self.assertFalse((self.target/'core-coverage-summary.json').exists())

if __name__ == '__main__': unittest.main()
