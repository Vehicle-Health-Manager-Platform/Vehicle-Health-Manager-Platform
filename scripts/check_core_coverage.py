"""Fail-closed JaCoCo LINE/METHOD gate for the declared core business scope."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'com/autocare/platform/'

def load_scope(path, source_root):
    scope = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(scope, dict):
        raise ValueError('Coverage scope must be an object')
    if scope.get('version') != 1 or scope.get('metric') != 'LINE':
        raise ValueError('Unsupported coverage scope version or metric')
    minimum = scope.get('minimum_percent')
    if type(minimum) is not int or not 70 <= minimum <= 100:
        raise ValueError('Coverage threshold must be an integer from 70 to 100')
    method_minimum=scope.get('minimum_method_percent')
    if type(method_minimum) is not int or not 70 <= method_minimum <= 100:
        raise ValueError('Method coverage threshold must be an integer from 70 to 100')
    groups = scope.get('groups')
    if not isinstance(groups, dict) or not groups:
        raise ValueError('Coverage groups must be nonempty')
    tests = scope.get('local_tests')
    if not isinstance(tests, list) or not tests or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*Test', name) for name in tests) or len(set(tests)) != len(tests):
        raise ValueError('Required core suites must be explicit, nonempty and unique')
    declared = set()
    for group, names in groups.items():
        if not isinstance(group, str) or not re.fullmatch(r'[a-z][a-z0-9_]*', group) or not isinstance(names, list) or not names:
            raise ValueError('Invalid or empty coverage group')
        for name in names:
            if not isinstance(name, str) or not re.fullmatch(r'(?:[A-Za-z_][A-Za-z0-9_]*/)+[A-Za-z_][A-Za-z0-9_]*', name):
                raise ValueError('Coverage classes must be explicit Java root paths')
            if name in declared:
                raise ValueError('Duplicate class across coverage groups: ' + name)
            declared.add(name)
            if not (source_root / (PREFIX + name + '.java')).is_file():
                raise ValueError('Declared source class missing: ' + name)
    return scope

def counters(element):
    result = {}
    for counter in element.findall('counter'):
        kind = counter.get('type')
        if kind not in {'INSTRUCTION', 'BRANCH', 'LINE', 'COMPLEXITY', 'METHOD', 'CLASS'}:
            raise ValueError('Unknown coverage counter type')
        if kind in result:
            raise ValueError('Duplicate coverage counter')
        values = []
        for attribute in ('missed', 'covered'):
            value = counter.get(attribute, '')
            if not re.fullmatch(r'[0-9]+', value) or len(value) > 10 or int(value) > 2147483647:
                raise ValueError('Invalid coverage counter')
            values.append(int(value))
        result[kind] = {'missed': values[0], 'covered': values[1]}
    return result

def total(rows, kind):
    return {key: sum(row.get(kind, {}).get(key, 0) for row in rows) for key in ('missed', 'covered')}

def metric(count, minimum):
    denominator = count['covered'] + count['missed']
    return {**count, 'total': denominator,
            'percent': round(100 * count['covered'] / denominator, 2) if denominator else None,
            'passed': denominator > 0 and count['covered'] * 100 >= minimum * denominator}

def branch_metric(count):
    # Branch data is disclosed, not assigned a fictional pass against a zero threshold.
    return {key: value for key, value in metric(count, 0).items() if key != 'passed'}

def evaluate(xml, scope):
    document = ET.parse(xml).getroot()
    if document.tag != 'report':
        raise ValueError('Expected a JaCoCo report root')
    records = {}
    for package in document.findall('package'):
        for clazz in package.findall('class'):
            name = clazz.get('name')
            if not name or name in records:
                raise ValueError('Missing or duplicate JaCoCo class')
            records[name] = counters(clazz)
    minimum = scope['minimum_percent']
    method_minimum = scope['minimum_method_percent']
    groups, failures, all_rows = {}, [], []
    for group, names in scope['groups'].items():
        classes, rows = {}, []
        for short in names:
            name = PREFIX + short
            if name not in records:
                raise ValueError('Declared class absent from report: ' + short)
            members = [counts for key, counts in records.items() if key.split('$', 1)[0] == name]
            line = metric(total(members, 'LINE'), minimum)
            if line['total'] == 0:
                raise ValueError('Declared class has no measured executable lines: ' + short)
            method = metric(total(members, 'METHOD'), method_minimum)
            if method['total']==0:
                raise ValueError('Declared class has no measured methods: ' + short)
            classes[short] = {'line': line, 'method':method, 'branch': branch_metric(total(members, 'BRANCH')),
                              'member_count': len(members)}
            rows.extend(members)
        group_line = metric(total(rows, 'LINE'), minimum)
        group_method = metric(total(rows, 'METHOD'),method_minimum)
        groups[group] = {'line': group_line, 'method':group_method, 'branch': branch_metric(total(rows, 'BRANCH')), 'classes': classes}
        if not group_line['passed']:
            failures.append(group + ': LINE below ' + str(minimum) + '%')
        if not group_method['passed']:
            failures.append(group + ': METHOD below ' + str(method_minimum) + '%')
        all_rows.extend(rows)
    overall = metric(total(all_rows, 'LINE'), minimum)
    if not overall['passed']:
        failures.append('overall: LINE below ' + str(minimum) + '%')
    overall_method=metric(total(all_rows,'METHOD'),method_minimum)
    if not overall_method['passed']:
        failures.append('overall: METHOD below ' + str(method_minimum) + '%')
    return {'version': 1, 'metric': 'LINE', 'minimum_percent': minimum, 'passed': not failures,
            'minimum_method_percent':method_minimum,'overall': overall,'overall_method':overall_method,
            'overall_branch':branch_metric(total(all_rows,'BRANCH')), 'groups': groups, 'failures': failures,
            'scope_note': 'Declared business roots and all their nested classes only; not whole-platform coverage.'}

def audit_tests(directory, required):
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    names = set()
    for path in sorted(Path(directory).glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        name = suite.get('name')
        if suite.tag != 'testsuite' or not name or name in names:
            raise ValueError('Invalid or duplicate JUnit suite')
        names.add(name)
        for key in totals:
            value = suite.get(key, '')
            if not re.fullmatch(r'[0-9]+', value):
                raise ValueError('Invalid JUnit count')
            totals[key] += int(value)
    if totals['tests'] == 0 or any(totals[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError('Missing, failed or skipped tests cannot establish coverage')
    missing = sorted(set(required) - {name.rsplit('.', 1)[-1] for name in names})
    if missing:
        raise ValueError('Required core suites absent: ' + ', '.join(missing))
    return {**totals, 'suites': len(names), 'required_core_suites': len(required)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--xml', type=Path, default=ROOT / 'backend/target/site/core-coverage/jacoco.xml')
    parser.add_argument('--scope', type=Path, default=ROOT / 'scripts/core_coverage_scope.json')
    parser.add_argument('--out', type=Path, default=ROOT / 'backend/target/core-coverage-summary.json')
    parser.add_argument('--tests-dir', type=Path, default=ROOT / 'backend/target/surefire-reports')
    args = parser.parse_args()
    try:
        scope = load_scope(args.scope, ROOT / 'backend/src/main/java')
        report = evaluate(args.xml, scope)
        report['tests'] = audit_tests(args.tests_dir, scope['local_tests'])
        report['scope_sha256'] = hashlib.sha256(args.scope.read_bytes()).hexdigest()
        report['xml_sha256'] = hashlib.sha256(args.xml.read_bytes()).hexdigest()
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError) as exc:
        report = {'version': 1, 'passed': False, 'failures': [str(exc)]}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if 'groups' in report:
        for name, result in report['groups'].items():
            print(f"{name}: LINE {result['line']['percent']}% ({result['line']['covered']}/{result['line']['total']}), METHOD {result['method']['percent']}%")
    for failure in report['failures']:
        print(failure, file=sys.stderr)
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    sys.exit(main())
