"""Reconcile declared cases and recorded evidence; never certify a current API run."""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import re

from generate_api_acceptance import ROOT, operations

KINDS = ('normal', 'boundary', 'error', 'unauthorized')
POSTMAN = {
    'owner': 'R9_A4D2A_OWNER_API_CASES_2026_10_19.json',
    'fulfillment': 'R9_A4D2B1_FULFILLMENT_API_CASES_2026_10_19.json',
    'coupon': 'R9_A4D2B2_COUPON_API_CASES_2026_10_19.json',
    'merchant': 'R9_A4D2C_MERCHANT_API_CASES_2026_10_19.json',
    'community': 'R9_A4D2D1_COMMUNITY_KNOWLEDGE_API_2026_10_19.json',
    'publication': 'R9_A4D2D2_PUBLIC_QA_API_2026_10_19.json',
    'experience': 'R9_A4D2D3_EXPERIENCE_API_2026_10_19.json',
    'health': 'R9_A4D2E1_OWNER_HEALTH_API_2026_10_19.json',
    'export': 'R9_A4D2E2A_ARCHIVE_EXPORT_SHARE_API_2026_10_19.json',
    'points': 'R9_A4D2E3_POINTS_LEDGER_API_2026_10_19.json',
    'exchange': 'R9_A4D2E4_POINTS_EXCHANGE_API_2026_10_19.json',
    'invitation': 'R9_A4D2F1_INVITATION_MONTHLY_API_2026_10_19.json',
    'diagnosis': 'R9_A4D2G1_DIAGNOSIS_API_2026_10_19.json',
    'discovery': 'R9_A4D2H1_SERVICE_DISCOVERY_2026_10_20.json',
    'quotes': 'R9_A4D2H2_MERCHANT_QUOTES_2026_10_20.json',
    'reviews': 'R9_A4D2H3_OWNER_REVIEWS_2026_10_20.json',
    'finance': 'R9_A4D6_FINANCIAL_RECOVERY_2026_10_21.json',
    'workflow': 'R9_A4D7_WORKFLOW_NOTIFICATIONS_2026_10_21.json',
    'dispute': 'R9_A4D8_DISPUTE_RECOVERY_2026_10_21.json',
    'assessment': 'R9_A4D10_ASSESSMENT_READS_2026_10_21.json',
}
JOURNEYS = {
    'upload': ('run_upload_acceptance', 'R9_A4D2E2B_PRIVATE_UPLOAD_API_2026_10_19.json'),
    'service_archive': ('run_service_archive_acceptance', 'R9_A4D2G2_SERVICE_ARCHIVE_ACCESS_2026_10_20.json'),
    'material_workbench': ('run_material_workbench_acceptance', 'R9_A4D2G3_MATERIAL_WORKBENCH_2026_10_20.json'),
    'pickup_work_files': ('run_pickup_work_files_acceptance', 'R9_A4D9_PICKUP_WORK_FILES_2026_10_21.json'),
}
UPLOAD_CHECKS = set('anonymous_object_denied tampered_signature_denied expired_signature_denied only_three_clean_files three_successful_uploads three_scan_rejections one_owned_archive_attachment'.split())
FINANCE_CHECKS = set('payment_recovered_once invalid_amount_not_paid one_recovered_payment_event fresh_refund_success failed_refund_success unique_refunds two_success_events commission_reversed_once uncompleted_refund_no_charge commission_test_only foreign_payment_unchanged foreign_shop_no_commission lease_cleared appointment_capacity_released'.split())
WORKFLOW_CHECKS = set('six_unique_order_events outbox_delivered exact_recipient_fanout three_read_notifications three_read_audits no_foreign_notifications'.split())
DISPUTE_CHECKS = set('resolved_once four_append_only_records original_received_state one_open_and_resume_transition resolution_confirmed five_dispute_audits eleven_unique_events exact_notification_fanout no_foreign_dispute'.split())
OUTPUT = ROOT / 'docs/testing/api-coverage.json'
SUMMARY = ROOT / 'docs/testing/API_COVERAGE.md'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    # Git checks out these text assets with CRLF on Windows and LF on Linux.
    return hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()


def collection_digests(path):
    """A historical raw digest must match the exact LF or CRLF form of these bytes."""
    lf = path.read_bytes().replace(b'\r\n', b'\n')
    return {hashlib.sha256(lf).hexdigest(), hashlib.sha256(lf.replace(b'\n', b'\r\n')).hexdigest()}


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def named(rows):
    require(isinstance(rows, list) and bool(rows), 'Empty or invalid case list')
    result = {}
    for row in rows:
        name = row.get('name')
        require(isinstance(name, str) and bool(name) and name not in result, 'Missing or duplicate case name')
        result[name] = row
    return result


def matches(template, path):
    pattern = ''.join('[^/]+' if part.startswith('{') else re.escape(part)
                      for part in re.split(r'(\{[^}]+\})', template))
    return isinstance(path, str) and re.fullmatch(pattern, path.split('?', 1)[0]) is not None


def case_coverage(cases, catalog):
    named(cases)
    coverage = {}
    for case in cases:
        op = case.get('operation_id')
        require(op in catalog, 'Unknown operation in case list')
        method, path = catalog[op]['method'], catalog[op]['path']
        require(case.get('method') == method and matches(path, case.get('path')), 'Case method/path differs from contract')
        require(case.get('kind') in KINDS, 'Unknown case category')
        require(isinstance(case.get('role'), str) and bool(case['role']), 'Missing case role')
        require(type(case.get('status')) is int and 100 <= case['status'] <= 599, 'Invalid expected HTTP status')
        coverage.setdefault(op, set()).add(case['kind'])
    return {op: sorted(kinds) for op, kinds in sorted(coverage.items())}


def successful(report):
    require(report.get('passed') is True and report.get('sources_unchanged') is True,
            'Recorded run failed or sources changed')
    require(bool(re.fullmatch(r'[0-9a-f]{64}', report.get('source_sha256', ''))), 'Missing recorded source digest')


def newman_evidence(suite, manifest, collection, collection_hash, report, catalog):
    cases = named(manifest.get('items'))
    require(type(manifest.get('cases')) is int and manifest['cases'] == len(cases), 'Manifest count differs from cases')
    require(all(type(c.get('assertions')) is int and c['assertions'] > 0 for c in cases.values()), 'Invalid manifest assertion count')
    definitions = case_coverage(list(cases.values()), catalog)
    if 'operations' in manifest:
        require(manifest['operations'] == len(definitions), 'Manifest operation count differs')
    requests = named(collection.get('item'))
    require(requests.keys() == cases.keys(), 'Collection case names differ from manifest')
    for name, case in cases.items():
        request = requests[name]['request']
        url = request.get('url')
        require(isinstance(url, str) and url.startswith('{{base_url}}'), 'Unexpected collection destination')
        require(request.get('method') == case['method'] and
                url.removeprefix('{{base_url}}').split('?', 1)[0] == case['path'].split('?', 1)[0],
                'Collection request differs from manifest')
    if report is None:
        return definitions, {}
    successful(report)
    accepted_hashes = collection_hash if isinstance(collection_hash, set) else {collection_hash}
    require(report.get(suite + '_collection_sha256') in accepted_hashes, 'Collection digest differs from recorded run')
    require(type(report.get(suite + '_newman_exit_code')) is int and report[suite + '_newman_exit_code'] == 0,
            'Recorded business runner failed')
    cleanup = report.get('cleanup')
    require(isinstance(cleanup, list) and len(cleanup) == 3 and
            {r.get('role') for r in cleanup} == {'backend', 'mysql', 'network'} and
            all(r.get('passed') is True for r in cleanup), 'Incomplete recorded cleanup')
    evidence = report.get(suite + '_evidence', {})
    actual = named(evidence.get('cases'))
    require(actual.keys() == cases.keys(), 'Recorded cases are missing, extra or duplicated')
    for name, case in cases.items():
        row = actual[name]
        require(set(row) == {'name', 'operation_id', 'kind', 'status', 'passed'} and row.get('passed') is True and
                all(row.get(k) == case[k] for k in ('operation_id', 'kind', 'status')),
                'Recorded case differs from manifest or did not pass')
    require(type(evidence.get('failures')) is int and evidence['failures'] == 0, 'Recorded case failures')
    require(type(evidence.get('requests')) is int and evidence['requests'] == len(cases) and
            type(evidence.get('assertions')) is int and
            evidence['assertions'] == sum(c['assertions'] for c in cases.values()), 'Recorded counts differ')
    require(evidence.get('coverage') == definitions, 'Recorded category summary differs from cases')
    if suite == 'finance':
        supplier = report.get('finance_supplier', {})
        require(supplier.get('passed') is True and supplier.get('counts') == {
            'payment_1_queries': 1, 'payment_4_queries': 1, 'fresh_refund_queries': 1,
            'fresh_refund_posts': 1, 'failed_refund_queries': 1, 'failed_refund_posts': 0,
            'invalid_submissions': 0}, 'Financial supplier calls do not prove recovery/unique submission')
        checks = report.get('finance_database', {}).get('checks', {})
        require(set(checks) == FINANCE_CHECKS and all(v is True for v in checks.values()), 'Financial database invariants are incomplete')
    if suite == 'workflow':
        require(evidence.get('signed_callback_and_exact_replay_verified') is True and
                evidence.get('inbox_ids_and_read_receipts_verified') is True,
                'Workflow callback or inbox/read receipt evidence missing')
        checks = report.get('workflow_database', {}).get('notification_checks', {})
        require(set(checks) == WORKFLOW_CHECKS and all(v is True for v in checks.values()),
                'Workflow notification database invariants are incomplete')
    if suite == 'dispute':
        require(evidence.get('signed_callback_and_exact_replay_verified') is True and
                evidence.get('dispute_write_intents_verified') is True,
                'Dispute signed callback or original write intent evidence missing')
        checks = report.get('dispute_database', {}).get('dispute_checks', {})
        require(set(checks) == DISPUTE_CHECKS and all(v is True for v in checks.values()),
                'Dispute recovery database invariants are incomplete')
        fulfilled = report.get('dispute_database', {}).get('checks', {})
        require(set(fulfilled) == set('order_completed local_payment_only redemption_test_mode report_signed foreign_vehicle_unchanged no_foreign_orders'.split()) and
                all(v is True for v in fulfilled.values()), 'Resumed fulfillment evidence is incomplete')
    return definitions, definitions


def journey_evidence(expected, report, catalog, expected_checks=None):
    normalized = []
    for case in expected:
        require(case.get('passed') is True, 'Journey inventory has a nonpassing case marker')
        candidates = [op for op, value in catalog.items()
                      if value['method'] == case['method'] and value['path'] == case['operation']]
        require(len(candidates) == 1, 'Journey operation does not match one contract operation')
        normalized.append(dict(case, operation_id=candidates[0]))
    definitions = case_coverage(normalized, catalog)
    if report is None:
        return definitions, {}
    successful(report)
    require(type(report.get('maven_exit_code')) is int and report['maven_exit_code'] == 0, 'Recorded journey runner failed')
    proof = report.get('proof', {})
    require(proof.get('cases') == expected, 'Recorded journey cases differ from inventory')
    require(type(proof.get('requests')) is int and proof['requests'] == len(expected) and
            type(proof.get('operations')) is int and proof['operations'] == len(definitions), 'Journey counts differ')
    for key in ('checks', 'cleanup'):
        require(isinstance(proof.get(key), dict) and bool(proof[key]) and all(v is True for v in proof[key].values()),
                'Incomplete journey checks/cleanup')
    if expected_checks is not None:
        require(set(proof['checks']) == set(expected_checks), 'Missing or unexpected journey check')
    require(set(proof['cleanup']) == {'initializer', 'clamav', 'minio', 'mysql', 'network'}, 'Missing journey cleanup role')
    # Earlier upload report keys omit the method; each key still must resolve uniquely.
    summary = {}
    for key, kinds in proof.get('coverage', {}).items():
        candidates = [op for op, value in catalog.items() if key in
                      (value['path'], value['method'] + ' ' + value['path']) and op in definitions]
        require(len(candidates) == 1 and candidates[0] not in summary, 'Ambiguous/duplicate journey coverage key')
        summary[candidates[0]] = kinds
    require(summary == definitions, 'Journey category summary differs from cases')
    return definitions, definitions


def summarize(spec, suites):
    catalog = {op['operationId']: {'operation_id': op['operationId'], 'method': method, 'path': path,
                                 'roles': op.get('x-roles'), 'protected': protected,
                                 'implementation_status': op.get('x-implementation-status', 'unspecified')}
               for method, path, op, protected in operations(spec)}
    rows = []
    for op, item in catalog.items():
        defined, historical = set(), set()
        sources = []
        for suite in suites:
            designed = suite['defined'].get(op, [])
            recorded = suite['historical'].get(op, [])
            defined.update(designed); historical.update(recorded)
            if designed:
                sources.append({'suite': suite['name'], 'defined_kinds': designed, 'historical_kinds': recorded})
        rows.append(dict(item, defined_kinds=sorted(defined), historical_kinds=sorted(historical),
                         missing_definition_kinds=sorted(set(KINDS) - defined),
                         missing_historical_kinds=sorted(set(KINDS) - historical),
                         current_acceptance='NOT_RUN', sources=sources))
    rows.sort(key=lambda r: (r['path'], r['method']))
    historical = sum(set(r['historical_kinds']) == set(KINDS) for r in rows)
    return {'version': 1, 'reconciliation_passed': True, 'full_api_acceptance_passed': False,
            'current_backend_revalidated': False,
            'scope': 'Selected recorded synthetic-identity/LOCAL_TEST suites only; other tests are not indexed. Reconciled with current definitions; no new HTTP execution or production acceptance.',
            'counts': {'contract_operations': len(rows), 'protected_operations': sum(r['protected'] for r in rows),
                       'four_kind_definitions': sum(set(r['defined_kinds']) == set(KINDS) for r in rows),
                       'historical_four_kind_operations': historical,
                       'without_complete_historical_evidence': len(rows) - historical,
                       'current_run_operations': 0, 'suites': len(suites)},
            'suites': [{k: v for k, v in suite.items() if k not in ('defined', 'historical')} for suite in suites],
            'operations': rows}


def build(root=ROOT):
    def path(name):
        return root / name
    def provenance(files):
        return {p.relative_to(root).as_posix(): digest(p) for p in files if p.is_file()}
    spec_path = path('docs/api/openapi.json')
    spec = load(spec_path)
    catalog = {op['operationId']: {'method': method, 'path': route}
               for method, route, op, _ in operations(spec)}
    suites = []
    for name, filename in sorted(POSTMAN.items()):
        manifest = path('docs/testing/postman/' + name + '-case-manifest.json')
        collection = path('docs/testing/postman/' + name + '-cases.postman_collection.json')
        report_path = path('docs/testing/reports/' + filename)
        report = load(report_path) if report_path.is_file() else None
        defined, historical = newman_evidence(name, load(manifest), load(collection), collection_digests(collection), report, catalog)
        suites.append({'name': name, 'type': 'postman', 'defined': defined, 'historical': historical,
                       'evidence_status': 'HISTORICAL_RECONCILED' if report else 'MISSING_REPORT',
                       'recorded_source_sha256': report.get('source_sha256') if report else None,
                       'inputs': provenance([manifest, collection, report_path])})
    for name, (module_name, filename) in sorted(JOURNEYS.items()):
        module = importlib.import_module(module_name)
        inventory = module.inventory()
        report_path = path('docs/testing/reports/' + filename)
        report = load(report_path) if report_path.is_file() else None
        defined, historical = journey_evidence(inventory, report, catalog, getattr(module, 'CHECKS', UPLOAD_CHECKS))
        suites.append({'name': name, 'type': 'http-journey', 'defined': defined, 'historical': historical,
                       'evidence_status': 'HISTORICAL_RECONCILED' if report else 'MISSING_REPORT',
                       'recorded_source_sha256': report.get('source_sha256') if report else None,
                       'inputs': provenance([path('scripts/' + module_name + '.py'), report_path])})
    result = summarize(spec, suites)
    result['contract_sha256'] = digest(spec_path)
    result['input_hash_policy'] = 'Input SHA256 uses CRLF-to-LF normalization. Historical raw collection digests must match its LF or CRLF byte form; content changes are rejected.'
    return result


def render(summary):
    c = summary['counts']
    lines = ['# 接口验收覆盖核对', '',
             '本文件由 `python scripts/summarize_api_coverage.py` 生成。', '',
             f"- 当前契约：{c['contract_operations']} 项；受保护接口：{c['protected_operations']} 项。",
             f"- 四类用例定义覆盖：{c['four_kind_definitions']} 项；有匹配历史四类执行记录：{c['historical_four_kind_operations']} 项（接口去重）。",
             f"- 所选记录中未获完整四类证据：{c['without_complete_historical_evidence']} 项；汇总过程新发起接口：0 项（各套实际执行见来源报告）。", '',
             '历史证据不等于当前版本重跑通过，不等于完整权限矩阵或真实身份/资金验收。',
             '匿名401不算业务四类覆盖；未列历史证据不等于功能未开发。',
             f"本工具只索引明确选定的{c['suites']}套记录，不否认其他单元、HTTP或历史验证；后续可增加经过校验的证据适配器。",
             '全API验收仍为false。结构化来源、哈希及逐项状态见 [api-coverage.json](api-coverage.json)。', '',
             '## 历史证据缺口（按领域分组）', '']
    groups = {}
    for row in summary['operations']:
        if row['missing_historical_kinds']:
            parts = row['path'].split('/')
            domain = '/'.join(parts[1:3])
            groups.setdefault(domain, []).append(row)
    for domain, rows in sorted(groups.items()):
        lines.extend([f'### {domain}（{len(rows)}项）', ''])
        for row in rows:
            lines.append(f"- `{row['method']} {row['path']}`：缺 {', '.join(row['missing_historical_kinds'])}；实现标记 `{row['implementation_status']}`。")
        lines.append('')
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if tracked summary is stale; do not write files')
    args = parser.parse_args()
    try:
        summary = build()
        documents = {OUTPUT: json.dumps(summary, ensure_ascii=False, indent=2) + '\n', SUMMARY: render(summary)}
        for path, content in documents.items():
            if args.check:
                require(path.is_file() and path.read_text(encoding='utf-8') == content, 'Tracked API coverage summary is stale: ' + path.name)
            else:
                path.write_text(content, encoding='utf-8')
        print('API coverage reconciliation PASS; no current API execution; ' + json.dumps(summary['counts']))
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as exc:
        parser.exit(1, 'API coverage reconciliation FAIL: ' + str(exc) + '\n')


if __name__ == '__main__':
    main()
