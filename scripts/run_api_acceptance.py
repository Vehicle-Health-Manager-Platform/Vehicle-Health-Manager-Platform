"""Run generated anonymous Postman checks through Newman on a new owned stack only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import uuid
from generate_api_acceptance import ROOT, OUTPUT, generate
from run_performance_baseline import Docker, Owned, MySQL, Backend, LABEL, JAR, build_jar, free_port, migration_files


def source_hash():
    paths = sorted((ROOT / 'backend/src/main').rglob('*')) + [ROOT / 'backend/pom.xml', ROOT / 'docs/api/openapi.json']
    paths += sorted((ROOT / 'docs/sql/migrations').glob('*.sql'))
    paths += [ROOT / 'scripts' / n for n in ('run_api_acceptance.py', 'generate_api_acceptance.py', 'run_performance_baseline.py')]
    value = hashlib.sha256()
    for path in paths:
        if path.is_file(): value.update(path.relative_to(ROOT).as_posix().encode()); value.update(path.read_bytes())
    return value.hexdigest()


def audit_newman(path, expected):
    report = json.loads(path.read_text(encoding='utf-8'))
    run = report['run']
    if run.get('failures'):
        raise ValueError('Newman assertion/request failure')
    executions = run['executions']
    if len(executions) != len(expected) or {x['item']['name'] for x in executions} != set(expected):
        raise ValueError('Missing, extra or duplicate anonymous probe')
    for execution in executions:
        name = execution['item']['name']
        request = execution.get('request', {})
        url = request.get('url', {})
        actual_path = '/' + '/'.join(url.get('path', []))
        if (request.get('method'), actual_path) != expected[name]:
            raise ValueError('Probe method/path differs from the declared operation')
        assertions = execution.get('assertions', [])
        if execution.get('requestError') or execution.get('response', {}).get('code') != 401:
            raise ValueError('Anonymous request was not rejected')
        if len(assertions) != 3 or any(a.get('error') or a.get('skipped') for a in assertions):
            raise ValueError('Missing, failed or skipped assertions')
    return {'requests': len(executions), 'assertions': len(executions)*3, 'failures': 0,
            'operations': sorted(expected)}


def counts(mysql):
    tables = mysql.text('SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name;').splitlines()
    if len(tables) != 131 or any(not name.replace('_', '').isalnum() for name in tables):
        raise ValueError('Unexpected isolated schema')
    return mysql.text(' UNION ALL '.join("SELECT '" + t + "',COUNT(*) FROM `" + t + '`' for t in tables) + ';')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci-built-jar', action='store_true')
    args = parser.parse_args()
    run = uuid.uuid4().hex
    work = ROOT / '.cache' / ('api-acceptance-' + run[:12]); work.mkdir(parents=True)
    network = Owned(run, 'network', 'autocare-perf-' + run[:12], {LABEL: run}, kind='network')
    mysql, backend = MySQL(run, network), None
    report = {'version': 1, 'passed': False, 'full_api_acceptance_passed': False,
              'started_at': datetime.now(timezone.utc).isoformat(), 'scope': 'anonymous authentication only',
              'source_sha256': source_hash(), 'cleanup': []}
    started = time.monotonic()
    try:
        assets = generate(json.loads((ROOT / 'docs/api/openapi.json').read_text(encoding='utf-8')))
        for name, data in assets.items():
            if json.loads((OUTPUT / name).read_text(encoding='utf-8')) != data:
                raise ValueError('Generated Postman assets are stale')
        probe = assets['anonymous.postman_collection.json']
        expected = {item['name']: (item['request']['method'], item['request']['url'].removeprefix('{{base_url}}')) for item in probe['item']}
        if len(expected) != 338: raise ValueError('Review changed anonymous acceptance scope')
        report['contract_operations'] = assets['acceptance_inventory.json']['operations']
        report['collection_sha256'] = hashlib.sha256((OUTPUT / 'anonymous.postman_collection.json').read_bytes()).hexdigest()
        if args.ci_built_jar:
            if os.environ.get('CI') != 'true' or not JAR.is_file(): raise ValueError('Existing jar is restricted to CI after clean verify')
            sources = [p for p in (ROOT / 'backend/src/main').rglob('*') if p.is_file()] + [ROOT / 'backend/pom.xml']
            if JAR.stat().st_mtime < max(p.stat().st_mtime for p in sources): raise ValueError('CI jar predates source inputs')
        else:
            print('API acceptance: build current backend', flush=True); build_jar()
        report['jar_sha256'] = hashlib.sha256(JAR.read_bytes()).hexdigest()
        print('API acceptance: start new owned stack', flush=True)
        network.id = Docker.run(['network', 'create', '--label', LABEL+'='+run, network.name]).stdout.decode().strip()
        network.guard(); mysql.start(); mysql.migrate(migration_files())
        before = counts(mysql)
        port = free_port(); backend = Backend(run, network, port, secrets.token_hex(24), 10)
        backend.start(mysql); backend.wait_healthy()
        npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
        if not npm: raise ValueError('Node/npm required for Newman')
        raw = work / 'newman.json'
        command = [npm, '--cache', str(ROOT / '.cache/npm-newman'), 'exec', '--yes', '--package=newman@6.2.1', '--',
                   'newman', 'run', str(OUTPUT / 'anonymous.postman_collection.json'),
                   '--env-var', f'base_url=http://127.0.0.1:{port}', '--reporters', 'json', '--reporter-json-export', str(raw),
                   '--timeout-request', '10000']
        print('API acceptance: Newman 6.2.1 / 338 anonymous requests', flush=True)
        with (work / 'newman.log').open('wb') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600, shell=False)
        report['newman_exit_code'] = result.returncode
        if result.returncode: raise ValueError('Newman failed; inspect this run newman.log/json')
        report['evidence'] = audit_newman(raw, expected)
        prepared_raw = work / 'prepared-disabled.json'
        prepared_command = [str(OUTPUT / 'prepared.postman_collection.json') if value == str(OUTPUT / 'anonymous.postman_collection.json')
                            else str(prepared_raw) if value == str(raw) else value for value in command]
        with (work / 'prepared-disabled.log').open('wb') as log:
            disabled = subprocess.run(prepared_command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=600, shell=False)
        disabled_run = json.loads(prepared_raw.read_text(encoding='utf-8'))['run']
        if disabled.returncode or disabled_run.get('failures') or disabled_run['stats']['requests']['total'] != 0:
            raise ValueError('Prepared collection must send zero requests by default')
        report['prepared_default_disabled_verified'] = True
        report['business_table_counts_unchanged'] = before == counts(mysql)
        if not report['business_table_counts_unchanged']: raise ValueError('Anonymous requests changed table row counts')
        report['sources_unchanged'] = report['source_sha256'] == source_hash()
        if not report['sources_unchanged']: raise ValueError('Acceptance sources changed during execution')
        report['passed'] = True
    except Exception as exc:
        report['error'] = str(exc) if isinstance(exc, ValueError) else type(exc).__name__
    finally:
        for obj in (backend, mysql, network):
            if obj is None: continue
            try: obj.close(); report['cleanup'].append({'role': obj.role, 'passed': True})
            except Exception: report['cleanup'].append({'role': obj.role, 'passed': False}); report['passed'] = False
    report['finished_at'] = datetime.now(timezone.utc).isoformat()
    report['duration_seconds'] = round(time.monotonic()-started, 3)
    (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('API acceptance: ' + ('PASS' if report['passed'] else 'FAIL') + '; ' + str(work), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
