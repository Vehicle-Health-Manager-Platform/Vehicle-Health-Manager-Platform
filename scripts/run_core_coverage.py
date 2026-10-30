"""Fresh core-test measurement; the complete adapter suite uses native Java/Maven."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from check_core_coverage import load_scope, audit_tests

ROOT = Path(__file__).resolve().parents[1]

def digest_sources():
    digest = hashlib.sha256()
    paths = [p for p in (ROOT / 'backend/src').rglob('*') if p.is_file()]
    paths += [p for p in (ROOT / 'backend/pom.xml', ROOT / 'scripts/core_coverage_scope.json', ROOT/'scripts/run_core_coverage.py', ROOT/'scripts/check_core_coverage.py') if p.is_file()]
    paths += sorted((ROOT / 'docs/sql/migrations').glob('*.sql'))
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode()); digest.update(path.read_bytes())
    return digest.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', action='store_true', help='Selected core suites only; complete adapters require native host Java/Maven.')
    parser.add_argument('--full', action='store_true', help='Full suite; requires real upload test images/dependencies.')
    parser.add_argument('--offline', action='store_true', help='Use already cached Maven dependencies; missing artifacts fail.')
    parser.add_argument('--prebuilt-upload-images', action='store_true', help='Use the explicitly prepared local upload fixture images.')
    parser.add_argument('--maven-repository', type=Path, help='Optional host Maven dependency cache.')
    args = parser.parse_args()
    # Clear a previous green result before validating even the scope/tool setup.
    for name in ('target/site/core-coverage/jacoco.xml', 'target/core-coverage-summary.json', 'target/core-coverage-run.json'):
        (ROOT / 'backend' / name).unlink(missing_ok=True)
    if args.docker and args.full:
        parser.error('Complete adapters require host Java/Maven and loopback endpoints; omit --docker for --full.')
    scope = load_scope(ROOT / 'scripts/core_coverage_scope.json', ROOT / 'backend/src/main/java')
    if args.docker and args.maven_repository:
        parser.error('--maven-repository is a host-only option')
    maven = 'mvn' if args.docker else (shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn') or 'mvn')
    command = [maven, '-B', '-Pcore-coverage', 'clean', 'verify']
    # Some Windows JVMs decode javaagent options through the legacy code page.
    # Only agent data needs an ASCII path; keep all source and XML reports in the workspace.
    coverage_temp=None
    if os.name=='nt' and not args.docker and not str(ROOT).isascii():
        temp_root=Path(tempfile.gettempdir()).resolve()
        if not str(temp_root).isascii():parser.error('Windows JaCoCo needs an ASCII TEMP directory')
        coverage_temp=Path(tempfile.mkdtemp(prefix='autocare-core-coverage-',dir=temp_root))
        command.append('-Dcore.coverage.dataFile='+str(coverage_temp/'coverage.exec'))
    if args.offline:
        command.append('-o')
    if args.prebuilt_upload_images:
        command.append('-Dupload.test.images.prebuilt=true')
    if args.maven_repository:
        command.append('-Dmaven.repo.local=' + str(args.maven_repository.resolve()))
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
    coverage_copy_error=None
    if coverage_temp is not None:
        try:
            data=coverage_temp/'coverage.exec'
            if data.is_file():
                shutil.copy2(data,ROOT/'backend/target/core-coverage.exec');data.unlink()
            coverage_temp.rmdir()
        except OSError as exc:coverage_copy_error=type(exc).__name__
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
    record['coverage_data_mode']='windows-ascii-temp' if coverage_temp is not None else 'workspace'
    record['coverage_copy_error']=coverage_copy_error
    target = ROOT / 'backend/target'; target.mkdir(exist_ok=True)
    (target / 'core-coverage-run.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if code or report_error or coverage_copy_error or not record['sources_unchanged'] or tests['tests'] == 0 or any(tests[k] for k in ('failures', 'errors', 'skipped')):
        print('Coverage run rejected: Maven failure, changing sources, missing/failed/skipped tests.', file=sys.stderr)
        return 1
    return subprocess.run([sys.executable, str(ROOT / 'scripts/check_core_coverage.py')], cwd=ROOT, shell=False).returncode

if __name__ == '__main__':
    sys.exit(main())
