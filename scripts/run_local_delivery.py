"""Reproducible local regression; no production service credentials are required.

Logs stay under .cache. A successful run certifies only the selected checks,
never real WeChat/SMS, device acceptance or production readiness.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BOUNDARIES = [
    'Synthetic identities and LOCAL_TEST funds in backend integration tests.',
    'No real WeChat/SMS/payment/model provider or mobile-device acceptance.',
    'Builds and unit tests do not certify rendered page interactions.',
    'Selected regression checks are not all-API business or performance acceptance.',
]


def source_digest(root):
    """Bind evidence to code/config, excluding generated outputs and private env files."""
    paths = [root / 'package.json', root / 'package-lock.json', root / 'backend/pom.xml']
    for directory in ('backend/src', 'scripts', 'services/ocr', 'services/asr', 'docs/sql/migrations'):
        paths += [p for p in (root / directory).rglob('*') if p.is_file()
                  and (p.suffix in ('.py', '.java', '.sql', '.js', '.mjs', '.cjs', '.yml', '.yaml', '.json', '.txt', '.in', '.sh', '.ps1')
                       or p.name.startswith('Dockerfile'))]
    for app in (root / 'apps').iterdir():
        for name in ('src', 'test', 'preview'):
            paths += [p for p in (app / name).rglob('*') if p.is_file()]
        paths += [p for p in (app / 'package.json', app / 'vite.config.js') if p.is_file()]
    digest = hashlib.sha256()
    for path in sorted(set(paths)):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return digest.hexdigest()


def steps(root, backend=False):
    npm = shutil.which('npm') or 'npm'
    commands = [
        ('miniapp-unit', [npm, 'test', '--workspace', '@autocare/miniapp'], root),
        ('python-regression', [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_*.py'], root),
        ('ocr-regression', [sys.executable, '-m', 'unittest', 'discover', '-s', 'services/ocr', '-p', 'test_*.py'], root),
        ('asr-regression', [sys.executable, '-m', 'unittest', 'discover', '-s', 'services/asr', '-p', 'test_*.py'], root),
        ('wechat-build', [npm, 'run', 'build:miniapp'], root),
        ('h5-build', [npm, 'run', 'build:h5', '--workspace', '@autocare/miniapp'], root),
        ('openapi-consistency', [sys.executable, 'scripts/generate_openapi.py', '--check'], root),
        ('migration-mounts', [sys.executable, 'scripts/verify_compose_migrations.py'], root),
        ('handover-consistency', [sys.executable, 'scripts/miniapp_handover.py', '--check'], root),
        ('contract-verdicts', [sys.executable, 'scripts/reconcile_contract.py', '--check'], root),
    ]
    if backend:
        commands += [
            ('backend-mysql-http', [shutil.which('mvn') or 'mvn', '-B', 'test'], root / 'backend'),
            ('core-journeys-evidence', [sys.executable, 'scripts/run_isolated_journeys.py', '--check-only'], root),
        ]
    return commands


def run_steps(commands, output, env, execute=subprocess.run):
    rows = []
    for name, command, cwd in commands:
        start = time.monotonic()
        log = output / (name + '.log')
        try:
            # Locked reports must fail this check without aborting independent checks.
            # Remove old evidence before running Maven, never after a failed command.
            if name == 'backend-mysql-http':
                for path in (cwd / 'target/surefire-reports').glob('TEST-*.xml'):
                    path.unlink()
            with log.open('wb') as stream:
                result = execute(command, cwd=cwd, env=env, stdout=stream,
                                 stderr=subprocess.STDOUT, timeout=5400, shell=False)
            status = 'passed' if result.returncode == 0 else 'failed'
            code = result.returncode
        except (OSError, subprocess.TimeoutExpired) as exc:
            status, code = 'failed', None
            # Never copy arbitrary exception text/environment into shared reports.
            with log.open('ab') as stream:
                stream.write(('\nRunner failure: ' + type(exc).__name__ + '\n').encode())
        rows.append({'name': name, 'status': status, 'exit_code': code,
                     'duration_seconds': round(time.monotonic() - start, 3), 'log': log.name})
        print(name + ': ' + status, flush=True)
    return rows


def report(rows, before, after, backend):
    return {'version': 1, 'finished_at': datetime.now(timezone.utc).isoformat(),
            'selected_checks_passed': bool(rows) and all(r['status'] == 'passed' for r in rows) and before == after,
            'source_sha256': before, 'sources_unchanged': before == after,
            'backend_requested': backend, 'backend_runtime_verified': backend and
                all(any(r['name'] == name and r['status'] == 'passed' for r in rows)
                    for name in ('backend-mysql-http', 'core-journeys-evidence')) and before == after,
            'production_acceptance_passed': False, 'boundaries': BOUNDARIES, 'checks': rows}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', action='store_true', help='Also run full Java/MySQL/HTTP tests; requires Java17/Maven/Docker.')
    args = parser.parse_args(argv)
    output = ROOT / '.cache' / ('local-delivery-' + uuid.uuid4().hex[:12])
    output.mkdir(parents=True)
    temporary = output / 'tmp'
    temporary.mkdir()
    env = dict(os.environ, PYTHONUTF8='1', TEMP=str(temporary), TMP=str(temporary), TMPDIR=str(temporary))
    before = source_digest(ROOT)
    rows = run_steps(steps(ROOT, args.backend), output, env)
    record = report(rows, before, source_digest(ROOT), args.backend)
    (output / 'report.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Evidence: ' + str(output / 'report.json'))
    return 0 if record['selected_checks_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
