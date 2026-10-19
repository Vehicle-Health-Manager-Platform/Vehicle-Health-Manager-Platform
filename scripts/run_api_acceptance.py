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
from urllib.parse import urlsplit, parse_qsl
from generate_api_acceptance import ROOT, OUTPUT, generate
from run_performance_baseline import Docker, Owned, MySQL, Backend, LABEL, JAR, build_jar, free_port, migration_files


def source_hash():
    paths = sorted((ROOT / 'backend/src/main').rglob('*')) + [ROOT / 'backend/pom.xml', ROOT / 'docs/api/openapi.json']
    paths += sorted((ROOT / 'docs/sql/migrations').glob('*.sql'))
    paths += [ROOT / 'scripts' / n for n in ('run_api_acceptance.py', 'generate_api_acceptance.py', 'run_performance_baseline.py')]
    paths += [ROOT / 'scripts/owner_api_cases.py']
    paths += [ROOT / 'scripts/fulfillment_api_cases.py']
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


def audit_owner_cases(path, collection, manifest, tokens, expected_operations=8):
    run = json.loads(path.read_text(encoding='utf-8'))['run']
    expected = {item['name']: item for item in manifest['items']}
    requests = {item['name']: item['request'] for item in collection['item']}
    executions = run['executions']
    if run.get('failures') or len(executions) != len(expected) or {x['item']['name'] for x in executions} != set(expected):
        raise ValueError('Missing, duplicate or failed owner API cases')
    coverage, results = {}, []
    for execution in executions:
        case = expected[execution['item']['name']]
        request = execution['request']; url = request['url']
        planned = urlsplit(requests[case['name']]['url'].removeprefix('{{base_url}}'))
        actual_query = sorted((str(x['key']),str(x.get('value',''))) for x in url.get('query',[]) if not x.get('disabled'))
        if request['method'] != case['method'] or '/'+'/'.join(url['path']) != case['path'] or actual_query != sorted(parse_qsl(planned.query)):
            raise ValueError('Owner API case targeted a different route/query')
        auth = [h['value'] for h in request.get('header',[]) if h['key'].lower()=='authorization' and not h.get('disabled')]
        wanted = [] if case['role']=='anonymous' else ['Bearer '+tokens[case['role']+'_token']]
        if auth != wanted: raise ValueError('Owner API case used the wrong identity')
        assertions = execution.get('assertions',[])
        if execution.get('requestError') or (execution.get('response') or {}).get('code') != case['status']:
            raise ValueError('Owner API case status differs from expected')
        if len(assertions) != case['assertions'] or any(a.get('error') or a.get('skipped') for a in assertions):
            raise ValueError('Missing, skipped or failed owner business assertions')
        coverage.setdefault(case['operation_id'],set()).add(case['kind'])
        results.append({'name':case['name'],'operation_id':case['operation_id'],'kind':case['kind'],'status':case['status'],'passed':True})
    if len(coverage)!=expected_operations or any(kinds!={'normal','boundary','error','unauthorized'} for kinds in coverage.values()):
        raise ValueError('Business cases do not cover all four categories of the scoped operations')
    return {'requests':len(results),'assertions':sum(x['assertions'] for x in manifest['items']),'failures':0,
            'coverage':{key:sorted(value) for key,value in coverage.items()},'cases':results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ci-built-jar', action='store_true')
    parser.add_argument('--owner-cases', action='store_true', help='Also run synthetic owner vehicle/archive business scenarios.')
    parser.add_argument('--fulfillment-cases', action='store_true', help='Run synthetic reservation to archive scenarios with LOCAL_TEST payments.')
    args = parser.parse_args()
    if args.owner_cases and args.fulfillment_cases:
        parser.error('Business suites require separate fresh databases; select one per run')
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
        owner_collection = owner_manifest = None
        if args.owner_cases:
            from owner_api_cases import assets as owner_assets, OUTPUT as OWNER_OUTPUT, MANIFEST as OWNER_MANIFEST
            owner_collection, owner_manifest = owner_assets()
            for path,data in [(OWNER_OUTPUT,owner_collection),(OWNER_MANIFEST,owner_manifest)]:
                if json.loads(path.read_text(encoding='utf-8')) != data: raise ValueError('Owner API assets are stale')
            report['owner_collection_sha256'] = hashlib.sha256(OWNER_OUTPUT.read_bytes()).hexdigest()
        if args.fulfillment_cases:
            from fulfillment_api_cases import assets as fulfillment_assets, OUTPUT as FULFILLMENT_OUTPUT, MANIFEST as FULFILLMENT_MANIFEST
            fulfillment_collection, fulfillment_manifest = fulfillment_assets()
            for path, data in [(FULFILLMENT_OUTPUT, fulfillment_collection), (FULFILLMENT_MANIFEST, fulfillment_manifest)]:
                if json.loads(path.read_text(encoding='utf-8')) != data: raise ValueError('Fulfillment API assets are stale')
            report['fulfillment_collection_sha256'] = hashlib.sha256(FULFILLMENT_OUTPUT.read_bytes()).hexdigest()
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
        secret = secrets.token_hex(24)
        owner_tokens = None
        if args.owner_cases:
            from owner_api_cases import seed
            owner_tokens = seed(mysql,secret)
        extra_env = ()
        if args.fulfillment_cases:
            from fulfillment_api_cases import seed, environment
            fulfillment_tokens = seed(mysql, secret)
            extra_env = environment(fulfillment_tokens)
        before = counts(mysql)
        port = free_port(); backend = Backend(run, network, port, secret, 10)
        backend.start(mysql, extra_env=extra_env); backend.wait_healthy()
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
        if args.owner_cases:
            from owner_api_cases import verify_database
            environment = work / 'owner-environment.private.json'
            environment.write_text(json.dumps({'name':'isolated owner case identities','values':[
                {'key':k,'value':v,'enabled':True} for k,v in owner_tokens.items()]}),encoding='utf-8')
            owner_raw = work / 'owner-newman.private.json'
            owner_command = [str(OWNER_OUTPUT) if v==str(OUTPUT / 'anonymous.postman_collection.json')
                             else str(owner_raw) if v==str(raw) else v for v in command]
            owner_command += ['--environment',str(environment)]
            print('API acceptance: owner vehicle/archive business cases',flush=True)
            with (work / 'owner-newman.private.log').open('wb') as log:
                owner_run = subprocess.run(owner_command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=600,shell=False)
            report['owner_newman_exit_code'] = owner_run.returncode
            if owner_run.returncode: raise ValueError('Owner Newman scenarios failed; inspect local private report')
            report['owner_evidence'] = audit_owner_cases(owner_raw,owner_collection,owner_manifest,owner_tokens)
            report['owner_database'] = verify_database(mysql)
            report['scope'] = 'anonymous authentication plus eight owner vehicle/archive business operations'
        if args.fulfillment_cases:
            from fulfillment_api_cases import audit, verify_database
            private_env = work / 'fulfillment-environment.private.json'
            private_env.write_text(json.dumps({'name':'isolated fulfillment identities','values':[
                {'key':k,'value':v,'enabled':True} for k,v in fulfillment_tokens.items()]}),encoding='utf-8')
            fulfillment_raw = work / 'fulfillment-newman.private.json'
            business_command = [str(FULFILLMENT_OUTPUT) if v==str(OUTPUT / 'anonymous.postman_collection.json')
                                else str(fulfillment_raw) if v==str(raw) else v for v in command]
            business_command += ['--environment',str(private_env),'--delay-request','100']
            print('API acceptance: reservation to archive / LOCAL_TEST only',flush=True)
            with (work / 'fulfillment-newman.private.log').open('wb') as log:
                business = subprocess.run(business_command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=600,shell=False)
            report['fulfillment_newman_exit_code'] = business.returncode
            if business.returncode: raise ValueError('Fulfillment Newman scenarios failed; inspect local private report')
            report['fulfillment_evidence'] = audit(fulfillment_raw,fulfillment_collection,fulfillment_manifest,fulfillment_tokens)
            report['fulfillment_database'] = verify_database(mysql)
            report['scope'] = 'anonymous authentication plus reservation to archive LOCAL_TEST business operations'
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
