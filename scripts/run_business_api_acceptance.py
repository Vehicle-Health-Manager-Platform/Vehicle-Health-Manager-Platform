"""Run isolated business API suites and audit current four-kind evidence.

Only local synthetic identities / LOCAL_TEST funds. Never certifies all APIs,
real providers, native devices, full backend coverage or production.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import time
import traceback
import uuid

from generate_api_acceptance import ROOT, OUTPUT, operations
from run_api_acceptance import source_hash, audit_newman, audit_owner_cases
from run_local_delivery import source_digest
from summarize_api_coverage import (POSTMAN, JOURNEYS, KINDS, newman_evidence,
                                    journey_evidence, collection_digests)

SUITES = tuple(POSTMAN)
MAX_REPORT_BYTES = 8 * 1024 * 1024


def load(path):
    if path.stat().st_size > MAX_REPORT_BYTES:
        raise ValueError('Oversized evidence')
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_digest():
    value = hashlib.sha256()
    value.update(source_digest(ROOT).encode())
    value.update(source_hash().encode())
    paths = [ROOT / 'docs/api/CONTRACT_VERDICTS.json']
    paths += sorted(OUTPUT.glob('*.json'))
    for path in paths:
        value.update(path.relative_to(ROOT).as_posix().encode())
        value.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return value.hexdigest()


def catalog():
    return {op['operationId']: dict(method=method, path=path, protected=protected)
            for method, path, op, protected in operations(load(ROOT / 'docs/api/openapi.json'))}


def child_environment():
    result = os.environ.copy()
    for name in ('AUTOCARE_FINANCE_NATIVE_QA', 'AUTOCARE_DELIVERY_NATIVE_QA',
                 'AUTOCARE_PICKUP_SCAN_NATIVE_QA'):
        result[name] = '0'
    result['AUTOCARE_NATIVE_MATERIAL_RUN_ID'] = ''
    result['AUTOCARE_NATIVE_UPLOAD_KIND'] = 'pdf'
    return result


class ChildStillRunning(Exception):
    def __init__(self, pid):
        self.pid = pid


class RunLock:
    def __init__(self, path):
        self.path = path
        self.token = uuid.uuid4().hex

    def acquire(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open('x', encoding='utf-8') as file:
            json.dump(dict(pid=os.getpid(), token=self.token,
                           started_at=datetime.now(timezone.utc).isoformat()), file)

    def release(self):
        try:
            if load(self.path).get('token') != self.token:
                return False
            self.path.unlink()
            return True
        except (OSError, ValueError):
            return False


def run_child(arguments, log, env):
    # The child owns stack cleanup and its inner bounded transport/build calls.
    # No outer hard timeout/kill that could strand its finally cleanup.
    with log.open('wb') as output:
        child = subprocess.Popen([sys.executable, *arguments], cwd=ROOT, env=env,
                                 stdout=output, stderr=subprocess.STDOUT)
        try:
            return child.wait()
        except KeyboardInterrupt:
            try:
                child.wait(timeout=30)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                raise ChildStillRunning(child.pid) from None
            raise



def checks_passed(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key.endswith('checks'):
                if not isinstance(item, dict) or not item or any(v is not True for v in item.values()):
                    return False
            elif isinstance(item, (dict, list)) and not checks_passed(item):
                return False
    elif isinstance(value, list):
        return all(checks_passed(v) for v in value)
    return True


def audit_business(suite, path, expected_source, cat):
    report = load(path)
    if (report.get('version') != 1 or report.get('passed') is not True
            or report.get('source_sha256') != expected_source
            or report.get('sources_unchanged') is not True
            or report.get('newman_exit_code') != 0
            or report.get('prepared_default_disabled_verified') is not True
            or report.get(suite + '_default_disabled_verified') is not True
            or report.get('business_table_counts_unchanged') is not True):
        raise ValueError('Business execution or source binding incomplete')
    module = importlib.import_module(suite + '_api_cases')
    collection, manifest = module.assets()
    if load(module.OUTPUT) != collection or load(module.MANIFEST) != manifest:
        raise ValueError('Stale business definitions')
    definitions, accepted = newman_evidence(suite, manifest, collection,
                                           collection_digests(module.OUTPUT), report, cat)
    anonymous = load(OUTPUT / 'anonymous.postman_collection.json')
    if report.get('collection_sha256') not in collection_digests(OUTPUT / 'anonymous.postman_collection.json'):
        raise ValueError('Stale anonymous collection')
    expected = {item['name']: (item['request']['method'],
                item['request']['url'].removeprefix('{{base_url}}')) for item in anonymous['item']}
    observed = audit_newman(path.parent / 'newman.json', expected)
    if report.get('evidence') != observed:
        raise ValueError('Anonymous observed evidence differs')
    environment = load(path.parent / (suite + '-environment.private.json'))
    values = environment.get('values', [])
    if not isinstance(values, list) or len({v['key'] for v in values}) != len(values):
        raise ValueError('Invalid private execution context')
    tokens = {v['key']: v['value'] for v in values if v.get('enabled') is True}
    auditor = getattr(module, 'audit', audit_owner_cases)
    actual = auditor(path.parent / (suite + '-newman.private.json'), collection, manifest, tokens)
    if actual != report.get(suite + '_evidence') or accepted != definitions:
        raise ValueError('Business raw evidence differs')
    database = report.get(suite + '_database')
    if not isinstance(database, dict) or not database or not checks_passed(database):
        raise ValueError('Missing database proof')
    if report.get(suite + '_ui', {}).get('executed') is True:
        raise ValueError('Unexpected native execution')
    # Only safe summaries leave the private run directory; no tokens/body/error text.
    return dict(kind='business', name=suite, passed=True, origin='EXECUTED',
                source_sha256=expected_source, report_sha256=digest(path),
                requests=actual['requests'], assertions=actual['assertions'],
                anonymous_requests=observed['requests'],
                anonymous_assertions=observed['assertions'],
                anonymous_operations=observed['operations'], coverage=accepted,
                cleanup=report['cleanup'], database_checks_verified=True,
                private_raw_evidence_reaudited=True, native_ui_executed=False)


def execute_business(suite, work, expected_source, cat, env):
    before = set((ROOT / '.cache').glob('api-acceptance-*'))
    code = run_child([str(ROOT / 'scripts/run_api_acceptance.py'),
                      '--' + suite.replace('_', '-') + '-cases'],
                     work / (suite + '.private.log'), env)
    added = set((ROOT / '.cache').glob('api-acceptance-*')) - before
    if code != 0 or len(added) != 1:
        raise ValueError('Child failed or report is missing/ambiguous')
    directory = added.pop()
    if not directory.is_dir() or not directory.resolve().is_relative_to((ROOT / '.cache').resolve()):
        raise ValueError('Report outside owned workspace')
    return audit_business(suite, directory / 'report.json', expected_source, cat)


def clear_journey_outputs(module, work, name):
    target = (ROOT / 'backend/target').resolve()
    paths = [module.OUTPUT, module.JUNIT, module.EVIDENCE]
    if any(p.is_symlink() or not p.resolve().is_relative_to(target) for p in paths):
        raise ValueError('Unexpected journey output location')
    backup = work / ('previous-' + name)
    backup.mkdir()
    for path in paths:
        if path.exists():
            if not path.is_file():
                raise ValueError('Unexpected journey output type')
            shutil.copy2(path, backup / path.name)
            path.unlink()


def execute_journey(name, mode, work, cat, env):
    module = importlib.import_module(JOURNEYS[name][0])
    if mode == 'run':
        clear_journey_outputs(module, work, name)
        code = run_child([str(Path(module.__file__)), '--docker'],
                         work / (name + '.private.log'), env)
        report = load(module.OUTPUT)
        if (code != 0 or report.get('passed') is not True
                or report.get('maven_exit_code') != 0
                or report.get('sources_unchanged') is not True
                or report.get('source_sha256') != module.source_digest()):
            raise ValueError('Journey runner or source binding failed')
    proof = module.audit(module.JUNIT, module.EVIDENCE)
    definitions, _ = journey_evidence(module.inventory(), None, cat)
    if proof['requests'] != len(module.inventory()):
        raise ValueError('Journey counts differ')
    return dict(kind='journey', name=name, passed=True,
                origin='EXECUTED' if mode == 'run' else 'AUDIT_EXISTING',
                source_sha256=module.source_digest(),
                raw_sha256=digest(module.EVIDENCE), junit_sha256=digest(module.JUNIT),
                requests=proof['requests'], coverage=definitions,
                checks=proof['checks'], cleanup=proof['cleanup'])


def verdicts(cat):
    by_route = {v['method'] + ' ' + v['path']: key for key, v in cat.items()}
    result = {}
    allowed = {'superseded', 'internal-only', 'implemented-local', 'blocked-external',
               'not-implemented', 'no-success-path', 'dev-only'}
    for row in load(ROOT / 'docs/api/CONTRACT_VERDICTS.json')['verdicts']:
        operation = by_route.get(row.get('operation'))
        if operation is None or operation in result or row.get('verdict') not in allowed:
            raise ValueError('Invalid contract conclusion')
        result[operation] = row['verdict']
    return result


def summarize(rows, cat, conclusions):
    coverage = {}
    anonymous = set()
    executed_requests = audited_requests = anonymous_requests = assertions = 0
    for row in rows:
        if row.get('passed') is not True:
            continue
        if row.get('origin') not in ('EXECUTED', 'AUDIT_EXISTING'):
            raise ValueError('Unknown evidence origin')
        for operation, kinds in row['coverage'].items():
            if operation not in cat or not isinstance(kinds, list) or not kinds or not set(kinds) <= set(KINDS):
                raise ValueError('Unknown operation or evidence category')
            coverage.setdefault(operation, set()).update(kinds)
        for operation in row.get('anonymous_operations', []):
            if operation not in cat or not cat[operation]['protected']:
                raise ValueError('Unknown anonymous probe')
            anonymous.add(operation)
        if row['origin'] == 'EXECUTED':
            executed_requests += row['requests']
        else:
            audited_requests += row['requests']
        anonymous_requests += row.get('anonymous_requests', 0)
        assertions += row.get('assertions', 0) + row.get('anonymous_assertions', 0)
    complete = sorted(op for op, kinds in coverage.items() if set(KINDS) <= kinds)
    missing = [dict(operation_id=key, method=value['method'], path=value['path'],
                    missing_kinds=sorted(set(KINDS) - coverage.get(key, set())),
                    conclusion=conclusions.get(key, 'not-covered-in-this-run'))
               for key, value in sorted(cat.items()) if key not in complete]
    return dict(contract_operations=len(cat), unique_four_kind_operations=complete,
                unique_four_kind_operations_count=len(complete), missing=missing,
                executed_business_requests=executed_requests,
                audited_existing_business_requests=audited_requests,
                anonymous_request_executions=anonymous_requests,
                unique_anonymous_operations=len(anonymous), newman_assertions=assertions,
                full_api_acceptance_passed=False)


def plan(suites, mode):
    if not suites or len(set(suites)) != len(suites) or any(s not in SUITES for s in suites):
        raise ValueError('Empty, duplicate or unknown business suites')
    return [('business', name) for name in suites] + ([('journey', name) for name in JOURNEYS]
                                                        if mode != 'none' else [])


def run_plan(items, execute, accept):
    for kind, name in items:
        try:
            row = execute(kind, name)
        except ChildStillRunning:
            raise
        except Exception:
            # Raw diagnostics may contain secrets; keep them only in private child logs.
            row = dict(kind=kind, name=name, passed=False, reason='EXECUTION_OR_EVIDENCE_FAILED')
        accept(row)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suites', nargs='+', choices=SUITES, default=list(SUITES))
    parser.add_argument('--journeys', choices=('run', 'audit', 'none'), default='run',
                        help='run seven new Docker journeys; audit existing source-bound evidence; or omit')
    args = parser.parse_args(argv)
    try:
        items = plan(args.suites, args.journeys)
    except ValueError:
        parser.error('Business suites must be nonempty and unique')
    lock = RunLock(ROOT / '.cache/business-api-acceptance.lock')
    try:
        lock.acquire()
    except FileExistsError:
        print('Acceptance lock exists; verify its owner before clearing it.', flush=True)
        return 2
    except OSError:
        print('Cannot acquire acceptance lock.', flush=True)
        return 1
    started = time.monotonic()
    work = ROOT / '.cache' / ('business-api-acceptance-' + uuid.uuid4().hex[:12])
    try:
        work.mkdir()
    except OSError:
        lock.release()
        print('Cannot create acceptance output directory.', flush=True)
        return 1
    report = dict(version=1, passed=False, in_progress=True,
                  started_at=datetime.now(timezone.utc).isoformat(),
                  requested=[dict(kind=kind, name=name) for kind, name in items],
                  journey_mode=args.journeys, suites=[],
                  production_acceptance_passed=False, full_api_acceptance_passed=False,
                  native_ui_verified=False, real_upstream_verified=False,
                  full_backend_revalidated=False)
    def save():
        (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    def accept(row):
        report['suites'].append(row)
        print(row['kind'] + '/' + row['name'] + ': ' + ('PASS' if row['passed'] else 'FAIL'), flush=True)
        save()
    code = 1
    keep_lock = False
    try:
        report['input_sha256'] = input_digest()
        report['api_source_sha256'] = source_hash()
        cat = catalog()
        conclusions = verdicts(cat)
        env = child_environment()
        save()
        def execute(kind, name):
            print('Starting ' + kind + '/' + name, flush=True)
            try:
                return (execute_business(name, work, report['api_source_sha256'], cat, env)
                        if kind == 'business' else execute_journey(name, args.journeys, work, cat, env))
            except Exception:
                with (work / (kind + '-' + name + '.audit.private.log')).open('w', encoding='utf-8') as log:
                    traceback.print_exc(file=log)
                raise
        run_plan(items, execute, accept)
        report['sources_unchanged'] = report['input_sha256'] == input_digest()
        report['summary'] = summarize(report['suites'], cat, conclusions)
        report['passed'] = (report['sources_unchanged'] and len(report['suites']) == len(items)
                            and all(row['passed'] is True for row in report['suites']))
        code = 0 if report['passed'] else 1
    except ChildStillRunning as pending:
        keep_lock = True
        report['reason'] = 'CHILD_MAY_STILL_RUN_LOCK_RETAINED'
        report['child_pid'] = pending.pid
        code = 130
    except KeyboardInterrupt:
        report['reason'] = 'INTERRUPTED_NO_COMPLETION_CLAIM'
        code = 130
    except Exception:
        report['reason'] = 'INPUT_OR_SUMMARY_FAILED'
    finally:
        report['lock_released'] = False if keep_lock else lock.release()
        if not report['lock_released'] and not keep_lock:
            report['passed'] = False
            code = 1
        report.update(in_progress=False, finished_at=datetime.now(timezone.utc).isoformat(),
                      duration_seconds=round(time.monotonic()-started, 3))
        save()
    print('Current API acceptance: ' + ('PASS' if report['passed'] else 'FAIL') + '; ' + str(work), flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
