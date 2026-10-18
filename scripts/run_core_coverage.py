"""Run a fresh isolated core-test measurement; Docker mode supports this Windows workspace."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from check_core_coverage import load_scope, audit_tests

ROOT = Path(__file__).resolve().parents[1]

def digest_sources():
    digest = hashlib.sha256()
    for path in sorted((ROOT / 'backend/src').rglob('*.java')):
        digest.update(path.relative_to(ROOT).as_posix().encode()); digest.update(path.read_bytes())
    return digest.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', action='store_true')
    parser.add_argument('--full', action='store_true', help='Full suite; requires real upload test images/dependencies.')
    args = parser.parse_args()
    # Clear a previous green result before validating even the scope/tool setup.
    for name in ('target/site/core-coverage/jacoco.xml', 'target/core-coverage-summary.json', 'target/core-coverage-run.json'):
        (ROOT / 'backend' / name).unlink(missing_ok=True)
    scope = load_scope(ROOT / 'scripts/core_coverage_scope.json', ROOT / 'backend/src/main/java')
    command = ['mvn', '-B', '-Pcore-coverage', 'clean', 'verify']
    if not args.full:
        command.append('-Dtest=' + ','.join(scope['local_tests']))
    if args.docker:
        command = ['docker', 'run', '--rm', '--name', 'autocare-core-coverage-' + uuid.uuid4().hex[:8],
                   '--mount', f'type=bind,source={ROOT},target=/workspace',
                   '--mount', 'type=volume,source=autocare-maven-cache,target=/root/.m2',
                   '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
                   '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal',
                   '-e', 'JAVA_TOOL_OPTIONS=-Dapi.version=1.44', '-w', '/workspace/backend',
                   'maven:3.9-eclipse-temurin-17', *command]
    start = time.monotonic()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    before = digest_sources()
    try:
        code = subprocess.run(command, cwd=ROOT / 'backend', shell=False).returncode
    except OSError:
        code = 127
    tests = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    report_error = None
    try:
        tests = audit_tests(ROOT / 'backend/target/surefire-reports', scope['local_tests'])
    except (ValueError, OSError, ET.ParseError) as exc:
        report_error = str(exc)
    record = {'version': 1, 'started_from_commit': head, 'finished_at': datetime.now(timezone.utc).isoformat(),
              'command': command, 'full_suite': args.full, 'maven_exit_code': code, 'tests': tests,
              'duration_seconds': round(time.monotonic() - start, 2), 'host_system': platform.platform(),
              'host_logical_cpus': os.cpu_count(), 'backend_sources_sha256': before,
              'sources_unchanged': before == digest_sources(), 'test_report_error': report_error}
    target = ROOT / 'backend/target'; target.mkdir(exist_ok=True)
    (target / 'core-coverage-run.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if code or report_error or not record['sources_unchanged'] or tests['tests'] == 0 or any(tests[k] for k in ('failures', 'errors', 'skipped')):
        print('Coverage run rejected: Maven failure, changing sources, missing/failed/skipped tests.', file=sys.stderr)
        return 1
    return subprocess.run([sys.executable, str(ROOT / 'scripts/check_core_coverage.py')], cwd=ROOT, shell=False).returncode

if __name__ == '__main__':
    sys.exit(main())
