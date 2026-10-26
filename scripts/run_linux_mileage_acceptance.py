"""Opt-in Linux OCR -> host JVM -> WeChat journey. No container receives Docker control."""
import argparse
import base64
import hashlib
from inspect import getsource
import io
import json
import os
from pathlib import Path
import platform
import secrets
import subprocess
import sys
import time
import traceback
import uuid

from run_linux_ocr_acceptance import (ROOT, OWNER, IMAGE, digest as image_digest,
    resource_proof, worker_command, inspection_arguments, inspected_object, cleanup_owned)
from run_real_mileage_acceptance import digest as journey_digest, gateway_audit, native_audit
from run_material_workbench_acceptance import JUNIT, EVIDENCE, audit


def digest():
    value = hashlib.sha256((image_digest() + journey_digest()).encode())
    for name in ('run_linux_mileage_acceptance.py', 'linux_mileage_ingress.py'):
        value.update(name.encode()); value.update((ROOT/'scripts'/name).read_bytes())
    return value.hexdigest()


def transport_audit(proof, native):
    if proof.get('provider_transport') != 'linux-private-network-via-loopback':
        raise ValueError('Linux provider transport missing')
    count = proof.get('provider_tcp_connections')
    if type(count) is not int or count != (3 if native else 1):
        raise ValueError('Unexpected successful provider TCP connection count')
    return {'successful_provider_tcp_connections': count, 'model_internal_calls_measured': False}


def ingress_audit(item, private, transit):
    host = item['HostConfig']; config = item['Config']
    bindings = item['NetworkSettings']['Ports']['8091/tcp']
    if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1' or set(item['NetworkSettings']['Networks']) != {private, transit}:
        raise ValueError('Ingress is not loopback/private only')
    port = int(bindings[0]['HostPort'])
    if not 0 < port < 65536: raise ValueError('Invalid ingress port')
    expected = {'Privileged': False, 'ReadonlyRootfs': True, 'Memory': 128*1024*1024,
        'MemorySwap': 128*1024*1024, 'NanoCpus': 500000000, 'PidsLimit': 32}
    if any(type(host.get(k)) is not type(v) or host[k] != v for k, v in expected.items()):
        raise ValueError('Ingress resources or privilege changed')
    if config.get('User') != '10001:10001' or host.get('CapAdd') or 'ALL' not in host.get('CapDrop', []):
        raise ValueError('Ingress capabilities changed')
    if not any(v.startswith('no-new-privileges') for v in host.get('SecurityOpt', [])):
        raise ValueError('Ingress privilege boundary missing')
    mounts = item.get('Mounts', [])
    if len(mounts) != 1 or mounts[0].get('Destination') != '/ingress.py' or mounts[0].get('RW') is not False:
        raise ValueError('Ingress mounts changed')
    return port


def cleanup_uploads(run, docker, inspect, remove):
    complete = True
    for kind, is_network in [('container', False), ('network', True)]:
        arguments = [kind, 'ls', *([] if is_network else ['--all']),
            '--filter', 'label=autocare.upload.run='+run, '--format', '{{.ID}}']
        try: names = docker(arguments).stdout.split()
        except Exception: complete = False; continue
        for name in names:
            try:
                item = inspect(name, is_network)
                if item is None: continue
                labels = item.get('Labels', {}) if is_network else item.get('Config', {}).get('Labels', {})
                if labels.get('autocare.upload.run') != run:
                    complete = False; continue
                remove(name, is_network)
                if inspect(name, is_network) is not None: complete = False
            except Exception: complete = False
        try:
            if docker(arguments).stdout.split(): complete = False
        except Exception: complete = False
    return complete


def host_command(record, work):
    java = Path(record['java_home'])/'bin/java.exe'
    maven = Path(record['maven_home'])
    repository = Path(record['maven_repository'])
    jars = list((maven/'boot').glob('plexus-classworlds-*.jar'))
    if not java.is_file() or len(jars) != 1 or not repository.is_dir():
        raise ValueError('Prepare verified host Java17/Maven first')
    temporary = work/'java-tmp'; temporary.mkdir()
    return [str(java), '-Dfile.encoding=UTF-8', '-Dapi.version=1.44',
        '-Djava.io.tmpdir='+str(temporary), '-Dmaven.home='+str(maven),
        '-Dmaven.conf='+str(maven/'conf'), '-Dclassworlds.conf='+str(maven/'bin/m2.conf'),
        '-Dmaven.multiModuleProjectDirectory='+str(ROOT/'backend'), '-cp', str(jars[0]),
        'org.codehaus.plexus.classworlds.launcher.Launcher', '-B',
        '-Dmaven.repo.local='+str(repository), '-Dupload.test.images.prebuilt=true',
        '-Dtest=RealMaterialWorkbenchJourneyTest', 'test']


def stop_process(process):
    # Popen handle is owned by this invocation; never discover/kill unrelated JVMs.
    if process is not None and process.poll() is None:
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=30)
        process.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-linux-mileage', action='store_true')
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--models-root', type=Path, default=ROOT/'.cache/ocr-prepared')
    parser.add_argument('--wheelhouse', type=Path, default=ROOT/'.cache/ocr-linux-wheels')
    parser.add_argument('--host-tools', type=Path, default=ROOT/'.cache/host-tools/host-tools.private.json')
    args = parser.parse_args()
    if not args.allow_isolated_linux_mileage: parser.error('Explicit --allow-isolated-linux-mileage required')
    if platform.system() != 'Windows': parser.error('This host-JVM profile requires Windows Docker Desktop')
    if any(os.environ.get(k) for k in ('AUTOCARE_NATIVE_MATERIAL_RUN_ID', 'AUTOCARE_REAL_MILEAGE_RUN_ID')):
        parser.error('Do not reuse another run identity')
    os.chdir(ROOT)
    run = uuid.uuid4().hex; work = ROOT/'.cache'/('real-mileage-'+run); work.mkdir(parents=True)
    print('Linux mileage run: '+str(work), flush=True)
    before = digest(); component = image_digest()
    report = {'passed': False, 'source_sha256': before, 'full_api_acceptance_passed': False,
        'scope': 'Linux CPU OCR/shared gateway; synthetic image/identities/scanner; Devtools events assisted when native; no phone/owner accuracy/production acceptance'}
    network = 'ocr-mileage-'+run[:10]; transit = 'ocr-mileage-loopback-'+run[:10]
    server = 'ocr-mileage-runtime-'+run[:10]
    ingress = 'ocr-mileage-ingress-'+run[:10]; client = 'ocr-mileage-client-'+run[:10]
    containers = [server, ingress, client]
    environment = work/'runtime.private.env'; config = work/'config.private.json'
    process = native = None

    def docker(arguments, timeout=120, required=True):
        result = subprocess.run(['docker', *arguments], capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=timeout)
        with (work/'docker.private.log').open('a', encoding='utf-8') as log: log.write(result.stdout+result.stderr)
        if required and result.returncode: raise RuntimeError('Docker operation failed; inspect private log')
        return result

    def inspect(name, network=False):
        return inspected_object(docker(inspection_arguments(name, network), required=False), name, network)

    def execute(code): return json.loads(docker(['exec', server, 'python', '-c', code]).stdout)

    def identity():
        return execute(getsource(worker_command)+"\nfrom pathlib import Path;import json;rows=[]\nfor p in Path('/proc').iterdir():\n if not p.name.isdigit():continue\n try:\n  cmd=(p/'cmdline').read_bytes().split(bytes([0]))\n  if not worker_command(cmd):continue\n  status=dict(line.split(':',1) for line in (p/'status').read_text().splitlines() if ':' in line)\n  rows.append({'pid':int(p.name),'start':(p/'stat').read_text().split()[21],'filters':int(status['Seccomp_filters']),'uid':status['Uid'].split()[0]})\n except FileNotFoundError:continue\nparent=dict(line.split(':',1) for line in Path('/proc/1/status').read_text().splitlines() if ':' in line)\nprint(json.dumps({'workers':rows,'parent_filters':int(parent['Seccomp_filters'])}))")

    def remove(name, network=False):
        docker(['network', 'rm', name] if network else ['rm', '--force', name])

    try:
        sys.path.insert(0, str(ROOT/'services/ocr'))
        from model_assets import verify_models
        from prepare_runtime_wheels import verify_wheels
        from PIL import Image, ImageDraw, ImageFont
        models = args.models_root.resolve(); verify_models(models)
        wheels = args.wheelhouse.resolve(); verify_wheels(wheels)
        tools = json.loads(args.host_tools.read_text(encoding='utf-8'))
        command = host_command(tools, work)
        endpoint = docker(['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).stdout.strip()
        if not endpoint.startswith('npipe://'): raise ValueError('Expected host-only Docker Desktop named pipe')
        token = secrets.token_hex(32)
        environment.write_text('OCR_ENABLED=true\nOCR_SERVICE_TOKEN='+token+'\n', encoding='utf-8')
        docker(['build', '--tag', IMAGE, '--label', 'autocare.ocr.source-sha='+component,
            '--build-context', 'ocr_wheels='+str(wheels), '-f', str(ROOT/'services/ocr/Dockerfile.runtime'),
            str(ROOT/'services/ocr')], timeout=1800)
        image = json.loads(docker(['image', 'inspect', IMAGE]).stdout)[0]
        if image['Config']['Labels'].get('autocare.ocr.source-sha') != component: raise ValueError('Image source changed')
        image_id = image['Id']; report['image_id'] = image_id
        docker(['network', 'create', '--internal', '--label', OWNER+'='+run, network])
        docker(['network', 'create', '--label', OWNER+'='+run, transit])
        common = ['--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--tmpfs', '/tmp:size=67108864,mode=1777']
        docker(['run', '-d', '--pull=never', '--name', server, '--label', OWNER+'='+run,
            '--network', network, '--network-alias', 'ocr', *common, '--memory', '1536m',
            '--memory-swap', '1536m', '--shm-size', '16m', '--cpus', '2', '--pids-limit', '128',
            '--env-file', str(environment), '--mount', f'type=bind,source={models},target=/models,readonly', image_id])
        report['resources'] = resource_proof(inspect(server), inspect(network, True), network)
        ready = "import json,time,urllib.request\nend=time.monotonic()+60\nwhile True:\n try:\n  r=json.load(urllib.request.urlopen('http://localhost:8090/health',timeout=2))\n  if r.get('recognition')=='READY':break\n except OSError:pass\n if time.monotonic()>end:raise RuntimeError('OCR not ready')\n time.sleep(.2)\nprint(json.dumps({'ready':True}))"
        if execute(ready) != {'ready': True}: raise ValueError('OCR readiness missing')
        initial = identity()
        if len(initial['workers']) != 1 or initial['workers'][0]['uid'] != '10001' or initial['workers'][0]['filters'] <= initial['parent_filters']:
            raise ValueError('Worker identity or seccomp missing')
        result = docker(['run', '--rm', '--pull=never', '--name', client, '--label', OWNER+'='+run,
            '--network', network, *common, '--memory', '512m', '--pids-limit', '32', '--env-file', str(environment),
            '--mount', f'type=bind,source={ROOT/"services/ocr/container_check.py"},target=/check.py,readonly', image_id, 'python', '/check.py'])
        report['runtime_preflight'] = json.loads(result.stdout)
        if report['runtime_preflight'].get('passed') is not True or identity() != initial: raise ValueError('Preflight failed')
        docker(['run', '-d', '--pull=never', '--name', ingress, '--label', OWNER+'='+run, '--network', transit,
            *common, '--memory', '128m', '--memory-swap', '128m', '--cpus', '0.5', '--pids-limit', '32',
            '--publish', '127.0.0.1::8091', '--mount', f'type=bind,source={ROOT/"scripts/linux_mileage_ingress.py"},target=/ingress.py,readonly',
            image_id, 'python', '/ingress.py'])
        docker(['network', 'connect', network, ingress])
        ingress_port = ingress_audit(inspect(ingress), network, transit)
        report['transport'] = {'host_jvm': True, 'ryuk_disabled': True, 'docker_control_mounted': False, 'ingress_loopback_only': True}
        with Image.new('RGB', (1100, 220), 'white') as picture:
            ImageDraw.Draw(picture).text((40, 65), 'ODO 123456 km', font=ImageFont.load_default(size=80), fill='black')
            encoded = io.BytesIO(); picture.save(encoded, format='PNG')
        config.write_text(json.dumps({'port': ingress_port, 'token': token,
            'image': base64.b64encode(encoded.getvalue()).decode('ascii'), 'native': args.native, 'linux_runtime': True}), encoding='utf-8')
        for path in (JUNIT, EVIDENCE): path.unlink(missing_ok=True)
        env = os.environ.copy(); env.update({'JAVA_HOME': tools['java_home'], 'DOCKER_HOST': endpoint,
            'JAVA_TOOL_OPTIONS': '-Dapi.version=1.44 -Dfile.encoding=UTF-8',
            'TESTCONTAINERS_HOST_OVERRIDE': '127.0.0.1', 'TESTCONTAINERS_RYUK_DISABLED': 'true',
            'AUTOCARE_REAL_MILEAGE_RUN_ID': run, 'AUTOCARE_LINUX_MILEAGE_HOST': 'true'})
        with (work/'backend.private.log').open('wb') as log:
            process = subprocess.Popen(command, cwd=ROOT/'backend', env=env, stdout=log, stderr=subprocess.STDOUT)
            if args.native:
                with (work/'native-run.private.log').open('wb') as native_log:
                    native = subprocess.Popen(['node', str(ROOT/'scripts/native-mileage-e2e.cjs'), str(work), '--allow-isolated-mileage'], stdout=native_log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic()+1100
                    while native.poll() is None:
                        if process.poll() is not None: (work/'backend-finished').touch()
                        if time.monotonic() > deadline: raise TimeoutError('Native deadline exceeded')
                        time.sleep(.2)
                    if native.returncode: raise ValueError('Native journey failed; inspect private log')
            code = process.wait(timeout=120 if args.native else 1200)
        report['maven_exit_code'] = code
        if code: raise ValueError('Host JVM journey failed; inspect private log')
        report['material_workbench'] = audit(JUNIT, EVIDENCE)
        gateway = json.loads((work/'gateway.json').read_text(encoding='utf-8'))
        report['gateway'] = gateway_audit(gateway)
        report['gateway_transport'] = transport_audit(gateway, args.native)
        if args.native: report['native'] = native_audit(json.loads((work/'native.json').read_text(encoding='utf-8')), gateway['native_database'])
        if identity() != initial or execute(ready) != {'ready': True}: raise ValueError('Warm worker changed or stopped')
        report['same_ready_filtered_worker'] = True
        report['uploaded_image_sha256'] = hashlib.sha256(encoded.getvalue()).hexdigest()
        report['sources_unchanged'] = before == digest()
        if not report['sources_unchanged']: raise ValueError('Sources changed during acceptance')
        report['passed'] = True
    except Exception as error:
        report['error_kind'] = type(error).__name__
        (work/'error.private.log').write_text(traceback.format_exc(), encoding='utf-8')
    finally:
        cleanup = {}
        for name, child in [('host_jvm_stopped', process), ('native_runner_stopped', native)]:
            try: stop_process(child); cleanup[name] = child is None or child.poll() is not None
            except Exception: cleanup[name] = False
        try: cleanup['owned_upload_resources_removed'] = cleanup_uploads(run, docker, inspect, remove)
        except Exception: cleanup['owned_upload_resources_removed'] = False
        cleanup['owned_ocr_containers_and_network_removed'] = cleanup_owned(run, containers, [network, transit], inspect, remove)
        for path in (environment, config, work/'native-ready.private.json', work/'native-ready.private.json.tmp', work/'native-curl.private.cfg', work/'native-headers.private.txt'):
            try: path.unlink(missing_ok=True)
            except OSError: pass
        cleanup['private_controls_removed'] = all(not path.exists() for path in (environment, config, work/'native-ready.private.json', work/'native-ready.private.json.tmp', work/'native-curl.private.cfg', work/'native-headers.private.txt'))
        report['cleanup'] = cleanup; report['passed'] = report['passed'] and all(cleanup.values())
        (work/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Linux mileage acceptance: '+('PASS' if report['passed'] else 'FAIL; inspect private log'), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__': raise SystemExit(main())
