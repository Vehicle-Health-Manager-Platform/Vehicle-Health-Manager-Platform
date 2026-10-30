"""Strictly re-audit source-bound API executions after an orchestration-only fix.

Does not send new business HTTP or relabel an audit as a new execution. Original
failed parent reports remain untouched. Every business request/response/private
context is checked again with the existing auditor, then journeys and recognition
are audited against their original Java HTTP evidence.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import traceback
import uuid
import run_business_api_acceptance as r


def audit_business_rows(prior, cat):
    expected = r.source_hash()
    if prior.get('sources_unchanged') is not True or prior.get('api_source_sha256') != expected:
        raise ValueError('Business implementation changed or original execution was unstable')
    original = [row for row in prior.get('suites', []) if row.get('kind') == 'business']
    if (len(original) != len(r.SUITES) or {row.get('name') for row in original} != set(r.SUITES)
            or any(row.get('passed') is not True or row.get('origin') != 'EXECUTED' for row in original)):
        raise ValueError('Complete passing original business executions required')
    cache = (r.ROOT / '.cache').resolve()
    by_hash = {}
    for path in cache.glob('api-acceptance-*/report.json'):
        if path.is_symlink() or not path.resolve().is_relative_to(cache):
            continue
        by_hash.setdefault(r.digest(path), []).append(path)
    results = []
    for row in original:
        paths = by_hash.get(row.get('report_sha256'), [])
        if len(paths) != 1:
            raise ValueError('Original child report missing, changed or ambiguous')
        proof = r.audit_business(row['name'], paths[0], expected, cat)
        if proof != row:
            raise ValueError('Fresh raw audit differs from original execution receipt')
        results.append(dict(proof, origin='AUDIT_EXISTING'))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-run', type=Path, required=True)
    args = parser.parse_args()
    cache = (r.ROOT / '.cache').resolve()
    source = args.source_run.resolve()
    if not source.is_relative_to(cache) or not source.is_file() or args.source_run.is_symlink():
        parser.error('Source must be a retained report inside this workspace cache')
    lock = r.RunLock(cache / 'business-api-acceptance.lock')
    lock.acquire()
    work = cache / ('current-api-reaudit-' + uuid.uuid4().hex[:12])
    work.mkdir()
    report = dict(version=1, passed=False, in_progress=True, execution_mode='AUDIT_EXISTING',
                  started_at=datetime.now(timezone.utc).isoformat(), suites=[],
                  production_acceptance_passed=False, full_api_acceptance_passed=False,
                  native_ui_verified=False, real_upstream_verified=False,
                  full_backend_revalidated=False, new_business_http_executed=False)
    keep_lock = False
    try:
        before = r.input_digest()
        prior = r.load(source)
        requested = r.plan(list(r.SUITES), 'audit', 'audit')
        if (prior.get('in_progress') is not False or prior.get('lock_released') is not True
                or len(prior.get('requested', [])) != len(requested)
                or {(v['kind'], v['name']) for v in prior['requested']} != set(requested)):
            raise ValueError('Completed full original plan required')
        report.update(input_sha256=before, api_source_sha256=r.source_hash(),
                      requested=[dict(kind=k, name=n) for k, n in requested],
                      original_parent_report_sha256=r.digest(source),
                      original_parent_passed=prior.get('passed') is True,
                      original_parent_input_sha256=prior.get('input_sha256'),
                      original_execution_started_at=prior.get('started_at'),
                      original_execution_finished_at=prior.get('finished_at'))
        cat = r.catalog()
        report['suites'] = audit_business_rows(prior, cat)
        report['original_executed_business_requests'] = sum(v['requests'] for v in report['suites'])
        report['original_executed_anonymous_requests'] = sum(v['anonymous_requests'] for v in report['suites'])
        for name in r.JOURNEYS:
            report['suites'].append(r.execute_journey(name, 'audit', work, cat, r.child_environment()))
        report['suites'].append(r.execute_recognition('audit', work, cat, r.child_environment()))
        report['summary'] = r.summarize(report['suites'], cat, r.verdicts(cat))
        report['sources_unchanged'] = before == r.input_digest()
        report['passed'] = report['sources_unchanged'] and len(report['suites']) == len(requested) and all(v['passed'] is True for v in report['suites'])
    except r.ChildStillRunning:
        keep_lock = True
        report['reason'] = 'READ_ONLY_CHILD_MAY_STILL_RUN_LOCK_RETAINED'
    except Exception as exc:
        report['reason'] = type(exc).__name__
        (work / 'failure.private.log').write_text(traceback.format_exc(), encoding='utf-8')
    finally:
        report['lock_released'] = False if keep_lock else lock.release()
        report['passed'] = report['passed'] and report['lock_released']
        report.update(in_progress=False, finished_at=datetime.now(timezone.utc).isoformat())
        (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Current API raw re-audit: ' + ('PASS' if report['passed'] else 'FAIL') + '; ' + str(work), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
