"""Run/check real HTTP core journeys against a new Testcontainers MySQL database."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CLASS = 'com.autocare.platform.order.IsolatedCoreJourneysTest'
CASES = {
    'ownerVehicleArchiveAndInquiryPreparation',
    'reservationThroughPickupServiceRedemptionAndArchive',
    'merchantApplicationReviewAndPendingActivationBoundary',
}
REPORT = ROOT / 'backend/target/surefire-reports' / f'TEST-{CLASS}.xml'
OUTPUT = ROOT / 'backend/target/isolated-journeys-run.json'


def check_report(path):
    suite = ET.parse(path).getroot()
    if suite.tag != 'testsuite' or suite.get('name') != CLASS:
        raise ValueError('Unexpected journey suite')
    counts = {key: int(suite.get(key, '-1')) for key in ('tests', 'failures', 'errors', 'skipped')}
    cases = suite.findall('testcase')
    if counts != {'tests': 3, 'failures': 0, 'errors': 0, 'skipped': 0}:
        raise ValueError('Journeys missing, failed or skipped')
    if len(cases) != 3 or {case.get('name') for case in cases} != CASES:
        raise ValueError('Expected all three declared business journeys')
    if any(case.get('classname') != CLASS or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')) for case in cases):
        raise ValueError('Journey testcase failure or unexpected class')
    return {'counts': counts, 'cases': sorted(CASES)}


def source_digest():
    digest = hashlib.sha256()
    for directory, pattern in [('backend/src', '*.java'), ('docs/sql/migrations', '*.sql')]:
        for path in sorted((ROOT / directory).rglob(pattern)):
            digest.update(path.relative_to(ROOT).as_posix().encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--docker', action='store_true')
    mode.add_argument('--check-only', action='store_true', help='CI: validate JUnit evidence from the preceding Maven run.')
    args = parser.parse_args()
    OUTPUT.unlink(missing_ok=True)
    command = ['mvn', '-B', '-Dtest=IsolatedCoreJourneysTest', 'clean', 'test']
    if args.docker:
        host_override = ['-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows', 'Darwin') else []
        command = ['docker', 'run', '--rm', '--name', 'autocare-journeys-' + uuid.uuid4().hex[:8],
                   '--mount', f'type=bind,source={ROOT},target=/workspace',
                   '--mount', 'type=volume,source=autocare-maven-cache,target=/root/.m2',
                   '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
                   *host_override,
                   '-e', 'JAVA_TOOL_OPTIONS=-Dapi.version=1.44', '-w', '/workspace/backend',
                   'maven:3.9-eclipse-temurin-17', *command]
    start = time.monotonic()
    before = source_digest()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    code = 0
    if not args.check_only:
        REPORT.unlink(missing_ok=True)
        try:
            code = subprocess.run(command, cwd=ROOT / 'backend', shell=False).returncode
        except OSError:
            code = 127
    error, tests = None, None
    try:
        tests = check_report(REPORT)
    except (ValueError, OSError, ET.ParseError) as exc:
        error = str(exc)
    unchanged = before == source_digest()
    passed = code == 0 and error is None and unchanged
    record = {'version': 1, 'passed': passed, 'finished_at': datetime.now(timezone.utc).isoformat(),
              'started_from_commit': head, 'execution_mode': 'ci-report-check' if args.check_only else 'local-execution',
              'command': None if args.check_only else command, 'maven_exit_code': None if args.check_only else code,
              'duration_seconds': round(time.monotonic()-start, 2), 'host_system': platform.platform(),
              'java_and_migrations_sha256': before, 'sources_unchanged': unchanged,
              'tests': tests, 'report_error': error,
              'boundaries': ['synthetic identities and CLEAN file metadata', 'LOCAL_TEST funds only',
                             'no real WeChat/SMS/AI/upload/device acceptance',
                             'merchant activation remains unimplemented; pending account must reject login',
                             'three HTTP business journeys, not all API or UI coverage']}
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(record, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Isolated journeys: ' + ('PASS' if passed else 'FAIL; see backend/target/isolated-journeys-run.json'))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
