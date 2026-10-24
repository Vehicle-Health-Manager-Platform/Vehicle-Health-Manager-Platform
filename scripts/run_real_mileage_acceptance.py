"""Explicit local real OCR -> real shared gateway acceptance. No WeChat UI claims."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import secrets
import subprocess
import sys
import threading
import traceback
import uuid
from run_material_workbench_acceptance import ROOT, JUNIT, EVIDENCE, audit, source_digest

EXPECTED = {'upload': 200, 'anonymous': 401, 'foreign_owner': 404, 'consent_required': 400,
    'query_rejected': 400, 'missing_file': 404, 'unsafe_file': 404, 'real_candidate': 200,
    'deleted_file': 404, 'transferred_vehicle': 404, 'signed_access': 200, 'revoked_session': 401}
CHECKS = {'passed', 'signed_download_matches', 'one_clean_owned_png', 'no_archive_created',
    'vehicle_mileage_unchanged', 'no_input_material_created', 'requires_confirmation', 'forwarder_closed'}

def local_model_root(path):
    resolved = Path(path).resolve()
    # Paddle's Windows reader failed on this workspace's absolute Unicode prefix.
    # Preserve the exact verified files using their repository-relative path.
    return resolved.relative_to(ROOT) if resolved.is_relative_to(ROOT) else resolved

def gateway_audit(proof):
    if any(proof.get(name) is not True for name in CHECKS): raise ValueError('Missing gateway database/storage/cleanup proof')
    rows = proof.get('cases', [])
    if len(rows) != len(EXPECTED) or {r['name'] for r in rows} != set(EXPECTED): raise ValueError('Gateway cases changed')
    for row in rows:
        name = row['name']
        path = '/api/file/upload' if name == 'upload' else '/api/vehicle/mileage-recognition'
        if name == 'query_rejected': path += '?owner_id=991'
        if name == 'signed_access':
            import re
            if not re.fullmatch(r'/api/file/[1-9][0-9]*/access', row['path']): raise ValueError('Unexpected signed access path')
            path = row['path']
        if row.get('passed') is not True or row['status'] != EXPECTED[name] or row['path'] != path or row['method'] != ('GET' if name == 'signed_access' else 'POST'):
            raise ValueError('Gateway method/path/status changed')
    return {'requests': len(rows), 'checks': {name: True for name in sorted(CHECKS)}, 'cases': rows}

def digest():
    value = hashlib.sha256(source_digest().encode())
    paths = [Path(__file__), *sorted((ROOT / 'services/ocr').glob('*.py')),
        *sorted((ROOT / 'services/ocr/src').glob('*.py')), *sorted((ROOT / 'services/ocr').glob('requirements*.txt')), ROOT / 'services/ocr/models.lock.json']
    for path in paths: value.update(path.relative_to(ROOT).as_posix().encode()); value.update(path.read_bytes())
    return value.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-real-ocr', action='store_true')
    parser.add_argument('--models-root', type=Path, default=ROOT / '.cache/ocr-prepared')
    args = parser.parse_args()
    if not args.allow_isolated_real_ocr: parser.error('Explicit --allow-isolated-real-ocr required')
    if platform.system() not in ('Windows', 'Darwin'): parser.error('This host loopback profile requires Docker Desktop')
    if os.environ.get('AUTOCARE_NATIVE_MATERIAL_RUN_ID') or os.environ.get('AUTOCARE_REAL_MILEAGE_RUN_ID'):
        parser.error('Do not reuse another run identity')
    args.models_root = local_model_root(args.models_root)
    os.chdir(ROOT)
    run = uuid.uuid4().hex; work = ROOT / '.cache' / ('real-mileage-' + run); work.mkdir()
    print('Real mileage run: ' + str(work), flush=True)
    before = digest(); report = {'passed': False, 'full_api_acceptance_passed': False, 'source_sha256': before,
        'scope': 'synthetic PNG; real CPU OCR, gateway HTTP, MySQL and private MinIO; synthetic ClamAV signatures; no WeChat or owner accuracy acceptance'}
    server = thread = None
    container = 'autocare-real-mileage-' + run[:8]
    config = work / 'config.private.json'
    try:
        sys.path.insert(0, str(ROOT / 'services/ocr'))
        from runtime_check import run as runtime_check
        report['runtime_preflight'] = runtime_check(args.models_root)
        from model_assets import verify_models
        from recognition import PaddleEngine
        from server import create_server
        from PIL import Image, ImageDraw, ImageFont
        models = verify_models(args.models_root)
        engine = PaddleEngine(models['PP-OCRv5_mobile_det'], models['PP-OCRv5_mobile_rec'])
        with Image.new('RGB', (1100, 220), 'white') as image:
            ImageDraw.Draw(image).text((40, 65), 'ODO 123456 km', font=ImageFont.load_default(size=80), fill='black')
            pixels = image.tobytes(); encoded = io.BytesIO(); image.save(encoded, format='PNG')
        calls = []
        class ObservedEngine:
            def recognize(self, image):
                if image.tobytes() != pixels: raise ValueError('Unexpected OCR image pixels')
                calls.append(True)
                return engine.recognize(image)
        token = secrets.token_hex(32)
        server = create_server(('127.0.0.1', 0), ObservedEngine(), token)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        config.write_text(json.dumps({'port': server.server_port, 'token': token, 'image': base64.b64encode(encoded.getvalue()).decode('ascii')}), encoding='utf-8')
        for path in (JUNIT, EVIDENCE): path.unlink(missing_ok=True)
        command = ['docker', 'run', '--rm', '--name', container, '--mount', f'type=bind,source={ROOT},target=/workspace',
            '--mount', 'type=volume,source=autocare-maven-cache,target=/root/.m2', '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
            '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal', '-e', 'AUTOCARE_REAL_MILEAGE_RUN_ID=' + run,
            '-e', 'JAVA_TOOL_OPTIONS=-Dapi.version=1.44', '-w', '/workspace/backend', 'maven:3.9-eclipse-temurin-17',
            'mvn', '-B', '-Dupload.test.images.prebuilt=true', '-Dtest=RealMaterialWorkbenchJourneyTest', 'test']
        with (work / 'backend.private.log').open('wb') as log:
            code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1200).returncode
        report['maven_exit_code'] = code
        if code: raise ValueError('Isolated JVM journey failed; inspect private log')
        report['material_workbench'] = audit(JUNIT, EVIDENCE)
        report['gateway'] = gateway_audit(json.loads((work / 'gateway.json').read_text(encoding='utf-8')))
        report['gateway_provider_calls'] = len(calls)
        if len(calls) != 1: raise ValueError('Rejected requests reached OCR or success did not use the real engine')
        report['uploaded_image_sha256'] = hashlib.sha256(encoded.getvalue()).hexdigest()
        report['sources_unchanged'] = before == digest()
        if not report['sources_unchanged']: raise ValueError('Sources changed during acceptance')
        report['passed'] = True
    except Exception as error:
        report['error_kind'] = type(error).__name__
        with (work / 'error.private.log').open('w', encoding='utf-8') as log: traceback.print_exc(file=log)
    finally:
        if server:
            server.shutdown(); server.server_close(); thread.join(5)
            report['ocr_server_stopped'] = not thread.is_alive()
        config.unlink(missing_ok=True)
        report['private_config_removed'] = not config.exists()
        # Stop only a remaining Maven process whose exact run identity matches this invocation.
        remaining = subprocess.run(['docker', 'inspect', container], capture_output=True)
        if remaining.returncode == 0:
            item = json.loads(remaining.stdout)[0]
            if 'AUTOCARE_REAL_MILEAGE_RUN_ID=' + run in item['Config']['Env']:
                subprocess.run(['docker', 'stop', container], capture_output=True, timeout=30)
            report['passed'] = False
        report['maven_container_removed'] = subprocess.run(['docker', 'inspect', container], capture_output=True).returncode != 0
        report['passed'] = report['passed'] and report.get('ocr_server_stopped') is True and report['private_config_removed'] and report['maven_container_removed']
        (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Real mileage acceptance: ' + ('PASS' if report['passed'] else 'FAIL; inspect private run log'), flush=True)
    return 0 if report['passed'] else 1

if __name__ == '__main__': raise SystemExit(main())
