"""Explicit isolated OCR worker -> shared gateway, with opt-in WeChat Devtools journey."""
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
import time
import traceback
import uuid
from run_material_workbench_acceptance import ROOT, JUNIT, EVIDENCE, audit, source_digest

EXPECTED = {'upload': 200, 'anonymous': 401, 'foreign_owner': 404, 'consent_required': 400,
    'query_rejected': 400, 'missing_file': 404, 'unsafe_file': 404, 'real_candidate': 200,
    'deleted_file': 404, 'transferred_vehicle': 404, 'signed_access': 200, 'revoked_session': 401}
NATIVE_CHECKS = set('upload_real_png selection_consent_required candidate_not_saved ack_required cancel_revokes_consent second_real_recognition confirmation_only_applies original_save_succeeds reentry_empty'.split())
CHECKS = {'passed', 'signed_download_matches', 'one_clean_owned_png', 'no_archive_created',
    'vehicle_mileage_unchanged', 'no_input_material_created', 'requires_confirmation', 'forwarder_closed'}

class ObservedWorker:
    """Transparent request-boundary counter; no parent-side image decode or response replacement."""
    def __init__(self, worker):
        self.worker = worker
        self.initial_pid = worker.pid
        self.initial_ready = worker.status == 'READY'
        self.isolated = type(self.initial_pid) is int and self.initial_pid > 0 and self.initial_pid != os.getpid()
        self._lock = threading.Lock()
        self.attempts = self.completed = self.failed = 0
        self.same_process = True

    @property
    def status(self): return self.worker.status

    def recognize_request(self, raw):
        with self._lock:
            self.attempts += 1
            self.same_process &= self.worker.pid == self.initial_pid
        try:
            result = self.worker.recognize_request(raw)
        except Exception:
            with self._lock: self.failed += 1
            raise
        else:
            with self._lock: self.completed += 1
            return result
        finally:
            with self._lock: self.same_process &= self.worker.pid == self.initial_pid

    def snapshot(self):
        with self._lock:
            return {'request_attempts': self.attempts, 'completed_requests': self.completed, 'failed_requests': self.failed,
                'process_isolated': self.isolated, 'initial_ready': self.initial_ready,
                'same_warm_process': self.same_process and self.worker.pid == self.initial_pid,
                'recognition': self.status, 'inference_deadline_seconds': self.worker.timeout,
                'startup_deadline_seconds': self.worker.startup_timeout, 'os_limits': self.worker.resource_profile}

    def close(self): self.worker.close()


def worker_audit(proof, expected):
    for name, value in [('request_attempts', expected), ('completed_requests', expected), ('failed_requests', 0)]:
        if type(proof.get(name)) is not int or proof[name] != value: raise ValueError('Unexpected worker request count')
    if any(proof.get(name) is not True for name in ['process_isolated', 'initial_ready', 'same_warm_process']):
        raise ValueError('Missing isolated warm-worker proof')
    if proof.get('recognition') != 'READY' or proof.get('inference_deadline_seconds') != 8 or proof.get('startup_deadline_seconds') != 30:
        raise ValueError('Worker is not ready with production deadlines')
    if not isinstance(proof.get('os_limits'), dict) or not proof['os_limits'].get('kind'):
        raise ValueError('Worker resource profile missing')
    return proof


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

def native_audit(ui, database):
    if ui.get('sdk_version') != '3.17.2': raise ValueError('Native mileage SDK evidence missing or changed')
    rows = ui.get('checks', [])
    if len(rows) != len(NATIVE_CHECKS) or {r['name'] for r in rows} != NATIVE_CHECKS or any(r.get('passed') is not True for r in rows): raise ValueError('Missing native journey checks')
    if any(ui.get(k) is not True for k in ['passed','session_cleared','system_api_restored','proxy_closed','local_reference_released']): raise ValueError('Native cleanup incomplete')
    if ui.get('counts') != {'upload':1,'recognition':2,'archive':1}: raise ValueError('Unexpected native API count')
    if set(database.get('checkpoints', [])) != {'candidate','cancelled','applied'} or len(database['checkpoints']) != 3: raise ValueError('Missing database checkpoints')
    if any(database.get(k) is not True for k in ['passed','zero_archives_before_save','one_archive_saved','correct_mileage_and_file','vehicle_mileage_unchanged','signed_download_matches','no_input_material_created']): raise ValueError('Native database proof incomplete')
    return {'ui':ui,'database':database}

def digest():
    value = hashlib.sha256(source_digest().encode())
    paths = [Path(__file__), *sorted((ROOT / 'services/ocr').glob('*.py')),
        *sorted((ROOT / 'services/ocr/src').glob('*.py')), *sorted((ROOT / 'services/ocr').glob('requirements*.txt')), ROOT / 'services/ocr/models.lock.json']
    paths += [ROOT / p for p in ['scripts/native-mileage-e2e.cjs','scripts/native-mileage-proxy.cjs','scripts/native-acceptance-plugin.mjs','apps/miniapp/src/components/InputCandidateReview.vue','apps/miniapp/src/pages/archive/record-add.vue','apps/miniapp/src/services/mileage-recognition.js','apps/miniapp/src/services/input-candidates.js','apps/miniapp/src/services/archive-flow.js','apps/miniapp/src/services/archives.js','apps/miniapp/test/helpers/native-elements.cjs','apps/miniapp/test/helpers/devtools-rpc.cjs']]
    for path in paths: value.update(path.relative_to(ROOT).as_posix().encode()); value.update(path.read_bytes())
    return value.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-real-ocr', action='store_true')
    parser.add_argument('--native', action='store_true', help='Also run the isolated real WeChat Devtools journey')
    parser.add_argument('--models-root', type=Path, default=ROOT / '.cache/ocr-prepared')
    args = parser.parse_args()
    if not args.allow_isolated_real_ocr: parser.error('Explicit --allow-isolated-real-ocr required')
    if platform.system() not in ('Windows', 'Darwin'): parser.error('This host loopback profile requires Docker Desktop')
    if os.environ.get('AUTOCARE_NATIVE_MATERIAL_RUN_ID') or os.environ.get('AUTOCARE_REAL_MILEAGE_RUN_ID'):
        parser.error('Do not reuse another run identity')
    args.models_root = local_model_root(args.models_root)
    os.chdir(ROOT)
    run = uuid.uuid4().hex; work = ROOT / '.cache' / ('real-mileage-' + run); work.mkdir(parents=True)
    print('Real mileage run: ' + str(work), flush=True)
    before = digest(); report = {'passed': False, 'full_api_acceptance_passed': False, 'source_sha256': before,
        'scope': ('Devtools events assisted; real upload/OCR/archive save; synthetic identities/image/scanner; no phone or owner accuracy acceptance' if args.native else 'synthetic PNG; real CPU OCR, gateway HTTP, MySQL and private MinIO; synthetic ClamAV signatures; no WeChat or owner accuracy acceptance')}
    server = thread = worker = None
    container = 'autocare-real-mileage-' + run[:8]
    config = work / 'config.private.json'
    try:
        sys.path.insert(0, str(ROOT / 'services/ocr'))
        from isolated_check import run as isolated_check
        report['runtime_preflight'] = isolated_check(args.models_root)
        from model_assets import verify_models
        from worker import ProcessRecognizer
        from server import create_server
        from PIL import Image, ImageDraw, ImageFont
        models = verify_models(args.models_root)
        worker = ProcessRecognizer(models['PP-OCRv5_mobile_det'], models['PP-OCRv5_mobile_rec'])
        with Image.new('RGB', (1100, 220), 'white') as image:
            ImageDraw.Draw(image).text((40, 65), 'ODO 123456 km', font=ImageFont.load_default(size=80), fill='black')
            encoded = io.BytesIO(); image.save(encoded, format='PNG')
        observed = ObservedWorker(worker)
        token = secrets.token_hex(32)
        server = create_server(('127.0.0.1', 0), observed, token)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        config.write_text(json.dumps({'port': server.server_port, 'token': token, 'image': base64.b64encode(encoded.getvalue()).decode('ascii'), 'native':args.native}), encoding='utf-8')
        for path in (JUNIT, EVIDENCE): path.unlink(missing_ok=True)
        command = ['docker', 'run', '--rm', '--name', container, '--mount', f'type=bind,source={ROOT},target=/workspace',
            '--mount', 'type=volume,source=autocare-maven-cache,target=/root/.m2', '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
            '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal', '-e', 'AUTOCARE_REAL_MILEAGE_RUN_ID=' + run,
            '-e', 'JAVA_TOOL_OPTIONS=-Dapi.version=1.44', '-w', '/workspace/backend', 'maven:3.9-eclipse-temurin-17',
            'mvn', '-B', '-Dupload.test.images.prebuilt=true', '-Dtest=RealMaterialWorkbenchJourneyTest', 'test']
        with (work / 'backend.private.log').open('wb') as log:
            if args.native:
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                try:
                    with (work / 'native-run.private.log').open('wb') as native_log:
                        native_process = subprocess.Popen(['node', str(ROOT / 'scripts/native-mileage-e2e.cjs'), str(work), '--allow-isolated-mileage'], stdout=native_log, stderr=subprocess.STDOUT)
                        try:
                            deadline = time.monotonic() + 1100
                            while native_process.poll() is None:
                                if process.poll() is not None: (work / 'backend-finished').touch()
                                if time.monotonic() > deadline: raise ValueError('Native process deadline exceeded')
                                time.sleep(0.2)
                            native_code = native_process.returncode
                        finally:
                            if native_process.poll() is None: native_process.terminate(); native_process.wait(timeout=30)
                    code = process.wait(timeout=120)
                    if native_code: raise ValueError('Native journey failed; inspect private run logs')
                finally:
                    if process.poll() is None:
                        subprocess.run(['docker','stop',container], capture_output=True, timeout=30)
                        process.wait(timeout=30)
            else: code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1200).returncode
        report['maven_exit_code'] = code
        if code: raise ValueError('Isolated JVM journey failed; inspect private log')
        report['material_workbench'] = audit(JUNIT, EVIDENCE)
        gateway = json.loads((work / 'gateway.json').read_text(encoding='utf-8'))
        report['gateway'] = gateway_audit(gateway)
        if args.native: report['native'] = native_audit(json.loads((work / 'native.json').read_text(encoding='utf-8')), gateway['native_database'])
        report['gateway_worker'] = worker_audit(observed.snapshot(), 3 if args.native else 1)
        report['uploaded_image_sha256'] = hashlib.sha256(encoded.getvalue()).hexdigest()
        report['sources_unchanged'] = before == digest()
        if not report['sources_unchanged']: raise ValueError('Sources changed during acceptance')
        report['passed'] = True
    except Exception as error:
        report['error_kind'] = type(error).__name__
        with (work / 'error.private.log').open('w', encoding='utf-8') as log: traceback.print_exc(file=log)
    finally:
        if server:
            if thread and thread.is_alive(): server.shutdown(); thread.join(5)
            server.server_close()
            report['ocr_server_stopped'] = thread is None or not thread.is_alive()
        elif worker: worker.close()
        if worker:
            report['ocr_worker_closed'] = worker.status == 'CLOSED' and worker.pid is None
            report['ocr_supervisor_stopped'] = not worker._supervisor.is_alive()
        config.unlink(missing_ok=True)
        (work / 'native-ready.private.json').unlink(missing_ok=True)
        report['private_config_removed'] = not config.exists()
        # Stop only a remaining Maven process whose exact run identity matches this invocation.
        remaining = subprocess.run(['docker', 'inspect', container], capture_output=True)
        if remaining.returncode == 0:
            item = json.loads(remaining.stdout)[0]
            if 'AUTOCARE_REAL_MILEAGE_RUN_ID=' + run in item['Config']['Env']:
                subprocess.run(['docker', 'stop', container], capture_output=True, timeout=30)
            report['passed'] = False
        report['maven_container_removed'] = subprocess.run(['docker', 'inspect', container], capture_output=True).returncode != 0
        report['passed'] = report['passed'] and all(report.get(name) is True for name in
            ['ocr_server_stopped','ocr_worker_closed','ocr_supervisor_stopped','private_config_removed','maven_container_removed'])
        (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Real mileage acceptance: ' + ('PASS' if report['passed'] else 'FAIL; inspect private run log'), flush=True)
    return 0 if report['passed'] else 1

if __name__ == '__main__': raise SystemExit(main())
