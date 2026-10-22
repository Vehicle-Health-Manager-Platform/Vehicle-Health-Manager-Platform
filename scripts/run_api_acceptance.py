"""Run generated anonymous Postman checks through Newman on a new owned stack only."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib
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

CI_INPUT = ROOT / '.cache/api-ci-build-input.json'


def backend_input_hash():
    value = hashlib.sha256()
    for path in sorted((ROOT / 'backend/src/main').rglob('*')) + [ROOT / 'backend/pom.xml']:
        if path.is_file(): value.update(path.relative_to(ROOT).as_posix().encode()); value.update(path.read_bytes())
    return value.hexdigest()


def record_ci_sources():
    if os.environ.get('CI') != 'true': raise ValueError('Build input recording is restricted to CI')
    if JAR.exists(): raise ValueError('Record build inputs before any jar exists on the fresh CI checkout')
    CI_INPUT.parent.mkdir(parents=True,exist_ok=True)
    CI_INPUT.write_text(json.dumps({'version':1,'jar_absent_before_build':True,'source_sha256':backend_input_hash()}),encoding='utf-8')


def verify_ci_jar():
    if os.environ.get('CI') != 'true' or not JAR.is_file(): raise ValueError('Existing jar is restricted to CI after clean verify')
    if not CI_INPUT.is_file(): raise ValueError('Missing pre-build CI source evidence')
    proof=json.loads(CI_INPUT.read_text(encoding='utf-8'))
    if proof.get('version')!=1 or proof.get('jar_absent_before_build') is not True or proof.get('source_sha256')!=backend_input_hash():
        raise ValueError('Backend source content changed after CI build input recording')
    return proof['source_sha256']


def source_hash():
    paths = sorted((ROOT / 'backend/src/main').rglob('*')) + [ROOT / 'backend/pom.xml', ROOT / 'docs/api/openapi.json']
    paths += sorted((ROOT / 'docs/sql/migrations').glob('*.sql'))
    paths += [ROOT / 'scripts' / n for n in ('run_api_acceptance.py', 'generate_api_acceptance.py', 'run_performance_baseline.py')]
    paths += [ROOT / 'scripts/owner_api_cases.py']
    paths += [ROOT / 'scripts/fulfillment_api_cases.py', ROOT / 'scripts/coupon_api_cases.py']
    paths += [ROOT / 'scripts/merchant_api_cases.py']
    paths += [ROOT / 'scripts/community_api_cases.py', ROOT / 'scripts/publication_api_cases.py', ROOT / 'scripts/experience_api_cases.py', ROOT / 'scripts/health_api_cases.py', ROOT / 'scripts/export_api_cases.py', ROOT / 'scripts/requirements-export-qa.txt']
    paths += [ROOT / 'scripts/points_api_cases.py', ROOT / 'scripts/exchange_api_cases.py']
    paths += [ROOT / 'scripts/dispute_api_cases.py', ROOT / 'scripts/workflow_api_cases.py', ROOT / 'scripts/finance_api_cases.py', ROOT / 'scripts/FinanceTestSupplier.java']
    paths += [ROOT / 'scripts/invitation_api_cases.py', ROOT / 'scripts/diagnosis_api_cases.py', ROOT / 'scripts/discovery_api_cases.py', ROOT / 'scripts/quotes_api_cases.py', ROOT / 'scripts/reviews_api_cases.py']
    paths += [ROOT / 'scripts/assessment_api_cases.py']
    paths += [ROOT / 'scripts/governance_api_cases.py', ROOT / 'scripts/fulfillment_reads_api_cases.py', ROOT / 'scripts/plan_evidence_api_cases.py']
    paths += [ROOT / 'scripts/relations_price_api_cases.py', ROOT / 'scripts/coupon_catalog_api_cases.py', ROOT / 'scripts/auth_sessions_api_cases.py']
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
        if request['method'] != case['method'] or '/'+'/'.join(url['path']) != case['path'] or actual_query != sorted(parse_qsl(planned.query,keep_blank_values=True)):
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
    parser.add_argument('--record-ci-sources', action='store_true', help='Record backend input content before clean verify on a fresh CI checkout.')
    parser.add_argument('--owner-cases', action='store_true', help='Also run synthetic owner vehicle/archive business scenarios.')
    parser.add_argument('--fulfillment-cases', action='store_true', help='Run synthetic reservation to archive scenarios with LOCAL_TEST payments.')
    parser.add_argument('--coupon-cases', action='store_true', help='Run cash coupon lifecycle and independent service redemption with LOCAL_TEST notices.')
    parser.add_argument('--merchant-cases', action='store_true', help='Run isolated merchant onboarding, staff and operator permission cases.')
    parser.add_argument('--community-cases', action='store_true', help='Run private Q&A and synthetic licensed KEYWORD retrieval.')
    parser.add_argument('--publication-cases', action='store_true', help='Run synthetic public Q&A consent review withdrawal cases.')
    parser.add_argument('--experience-cases', action='store_true', help='Run synthetic experience consent review and curation cases.')
    parser.add_argument('--health-cases', action='store_true', help='Run synthetic owner structured archive health and reminder cases.')
    parser.add_argument('--export-cases', action='store_true', help='Run isolated private PDF and archive share cases.')
    parser.add_argument('--points-cases', action='store_true', help='Run isolated point policies, check-in and verified ledger with synthetic identity flags.')
    parser.add_argument('--exchange-cases', action='store_true', help='Run earned points and atomic cash/service coupon exchange cases.')
    parser.add_argument('--invitation-cases', action='store_true', help='Run invitation code, attribution views and synthetic monthly qualification policy cases.')
    parser.add_argument('--diagnosis-cases', action='store_true', help='Run human diagnosis, assignment and local notification cases.')
    parser.add_argument('--discovery-cases', action='store_true', help='Run local service discovery search and merchant location scenarios.')
    parser.add_argument('--quotes-cases', action='store_true', help='Run merchant selection quote version and shelving scenarios.')
    parser.add_argument('--reviews-cases', action='store_true', help='Run owner review, public star, public text and summary consent scenarios.')
    parser.add_argument('--dispute-cases', action='store_true', help='Run isolated pickup dispute and resumed fulfillment cases.')
    parser.add_argument('--workflow-cases', action='store_true', help='Run isolated fulfillment reads and three-role notifications.')
    parser.add_argument('--finance-cases', action='store_true', help='Run isolated signed supplier financial recovery and commission cases.')
    parser.add_argument('--assessment-cases', action='store_true', help='Run isolated assessment policy, monthly settlement and business dashboard reads.')
    parser.add_argument('--plan-evidence-cases', action='store_true', help='Run model applicability, conservative evidence and manual plan context cases.')
    parser.add_argument('--fulfillment-reads-cases', action='store_true', help='Run remaining reservation and fulfillment views and slot closure.')
    parser.add_argument('--governance-cases', action='store_true', help='Run isolated catalog governance and price monitor evidence cases.')
    parser.add_argument('--relations-price-cases', action='store_true', help='Run isolated store customer relations and price comparisons read-only evidence.')
    parser.add_argument('--coupon-catalog-cases', action='store_true', help='Run coupon catalog, frozen draft edits and offers eligibility.')
    parser.add_argument('--auth-sessions-cases', action='store_true', help='Run synthetic session refresh, logout, invalidation and concurrent rotation.')
    parser.add_argument('--rating-quotes-cases', action='store_true', help='Run isolated public star service project rating quotes read-only evidence.')
    args = parser.parse_args()
    if args.record_ci_sources:
        if any((args.ci_built_jar,args.owner_cases,args.fulfillment_cases,args.coupon_cases,args.merchant_cases,args.community_cases,args.publication_cases,args.experience_cases,args.health_cases,args.export_cases,args.points_cases,args.exchange_cases,args.invitation_cases,args.diagnosis_cases,args.discovery_cases,args.quotes_cases,args.reviews_cases,args.finance_cases,args.workflow_cases,args.dispute_cases,args.assessment_cases,args.governance_cases,args.fulfillment_reads_cases,args.plan_evidence_cases,args.relations_price_cases,args.coupon_catalog_cases,args.auth_sessions_cases,args.rating_quotes_cases)):
            parser.error('Record CI inputs separately before the build')
        record_ci_sources(); print('API acceptance: recorded fresh CI build inputs',flush=True); return 0
    if sum((args.owner_cases, args.fulfillment_cases, args.coupon_cases, args.merchant_cases, args.community_cases,args.publication_cases,args.experience_cases,args.health_cases,args.export_cases,args.points_cases,args.exchange_cases,args.invitation_cases,args.diagnosis_cases,args.discovery_cases,args.quotes_cases,args.reviews_cases,args.finance_cases,args.workflow_cases,args.dispute_cases,args.assessment_cases,args.governance_cases,args.fulfillment_reads_cases,args.plan_evidence_cases,args.relations_price_cases,args.coupon_catalog_cases,args.auth_sessions_cases,args.rating_quotes_cases)) > 1:
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
        suite = 'owner' if args.owner_cases else 'fulfillment' if args.fulfillment_cases else 'coupon' if args.coupon_cases else 'merchant' if args.merchant_cases else 'community' if args.community_cases else 'publication' if args.publication_cases else 'experience' if args.experience_cases else 'health' if args.health_cases else 'export' if args.export_cases else 'points' if args.points_cases else 'exchange' if args.exchange_cases else 'invitation' if args.invitation_cases else 'diagnosis' if args.diagnosis_cases else 'discovery' if args.discovery_cases else 'quotes' if args.quotes_cases else 'reviews' if args.reviews_cases else 'finance' if args.finance_cases else 'workflow' if args.workflow_cases else 'dispute' if args.dispute_cases else 'assessment' if args.assessment_cases else 'governance' if args.governance_cases else 'fulfillment_reads' if args.fulfillment_reads_cases else 'plan_evidence' if args.plan_evidence_cases else 'relations_price' if args.relations_price_cases else 'coupon_catalog' if args.coupon_catalog_cases else 'auth_sessions' if args.auth_sessions_cases else 'rating_quotes' if args.rating_quotes_cases else None
        business_module = importlib.import_module(suite+'_api_cases') if suite else None
        if business_module:
            business_collection, business_manifest = business_module.assets()
            for path, data in [(business_module.OUTPUT,business_collection),(business_module.MANIFEST,business_manifest)]:
                if json.loads(path.read_text(encoding='utf-8')) != data: raise ValueError('Business API assets are stale')
            report[suite+'_collection_sha256'] = hashlib.sha256(business_module.OUTPUT.read_bytes()).hexdigest()
        if args.ci_built_jar:
            report['ci_build_source_sha256'] = verify_ci_jar()
        else:
            print('API acceptance: build current backend', flush=True); build_jar()
        report['jar_sha256'] = hashlib.sha256(JAR.read_bytes()).hexdigest()
        print('API acceptance: start new owned stack', flush=True)
        network.id = Docker.run(['network', 'create', '--label', LABEL+'='+run, network.name]).stdout.decode().strip()
        network.guard(); mysql.start(); mysql.migrate(migration_files())
        secret = secrets.token_hex(24)
        business_tokens = business_module.seed(mysql,secret) if business_module else None
        extra_env = business_module.environment(business_tokens) if business_module and hasattr(business_module,'environment') else ()
        before = counts(mysql)
        port = free_port(); backend = Backend(run, network, port, secret, 10)
        backend.start(mysql, extra_env=extra_env); backend.wait_healthy()
        if business_module and hasattr(business_module,'prepare_backend'):
            business_module.prepare_backend(backend)
        if business_module and hasattr(business_module,'prepare'):
            business_module.prepare(mysql)
            before = counts(mysql)
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
        if business_module:
            private_env = work / (suite+'-environment.private.json')
            private_env.write_text(json.dumps({'name':'isolated '+suite+' identities','values':[
                {'key':k,'value':v,'enabled':True} for k,v in business_tokens.items()]}),encoding='utf-8')
            business_raw = work / (suite+'-newman.private.json')
            business_command = [str(business_module.OUTPUT) if v==str(OUTPUT / 'anonymous.postman_collection.json')
                                else str(business_raw) if v==str(raw) else v for v in command]
            business_command += ['--environment',str(private_env),'--delay-request','100']
            print('API acceptance: '+suite+' business cases / isolated identities only',flush=True)
            with (work / (suite+'-newman.private.log')).open('wb') as log:
                business = subprocess.run(business_command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=600,shell=False)
            report[suite+'_newman_exit_code'] = business.returncode
            if business.returncode: raise ValueError('Business Newman scenarios failed; inspect local private report')
            check = getattr(business_module,'audit',audit_owner_cases)
            report[suite+'_evidence'] = check(business_raw,business_collection,business_manifest,business_tokens)
            report[suite+'_database'] = business_module.verify_database(mysql)
            if hasattr(business_module,'verify_supplier'):
                report[suite+'_supplier'] = business_module.verify_supplier(backend)
            report['scope'] = 'anonymous authentication plus '+suite+' business operations (synthetic identities; LOCAL_TEST funds only)'
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
