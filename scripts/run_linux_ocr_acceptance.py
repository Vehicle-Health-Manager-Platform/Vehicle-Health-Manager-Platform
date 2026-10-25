"""Explicit isolated Linux image/real-model/network acceptance; never production deployment."""
import argparse
import hashlib
from inspect import getsource
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'autocare.ocr.acceptance'
IMAGE = 'autocare-ocr-runtime:acceptance'


def worker_command(arguments):
    return len(arguments) >= 4 and arguments[1:4] == [b'-u', b'/app/worker.py', b'--child']


def inspection_arguments(name, network=False):
    return ['network' if network else 'container', 'inspect', name]


def inspected_object(result, name, network=False):
    if result.returncode == 0:
        rows = json.loads(result.stdout)
        if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
            raise RuntimeError('Unexpected Docker inspection response')
        return rows[0]
    kind = 'network' if network else 'container'
    missing = {f'Error response from daemon: No such {kind}: {name}', f'Error: No such {kind}: {name}'}
    if network: missing.add(f'Error response from daemon: network {name} not found')
    if result.stderr.strip() in missing and result.stdout.strip() in ('', '[]'): return None
    raise RuntimeError('Cannot verify owned Docker resource')


def cleanup_owned(run, containers, networks, inspect, remove):
    complete = True
    for name, network in [(name, False) for name in containers] + [(name, True) for name in networks]:
        try:
            item = inspect(name, network)
            if item is None: continue
            if not owned(item, run, network):
                complete = False; continue
            remove(name, network)
            if inspect(name, network) is not None: complete = False
        except Exception: complete = False
    return complete


def digest():
    paths = [*sorted((ROOT/'services/ocr').rglob('*.py')), *sorted((ROOT/'services/ocr').glob('requirements*.txt')),
        ROOT/'services/ocr/Dockerfile.runtime', ROOT/'services/ocr/.dockerignore', ROOT/'services/ocr/models.lock.json',
        ROOT/'services/ocr/runtime-wheels-linux.json',
        ROOT/'deploy/compose/ocr-runtime.yml', Path(__file__)]
    value = hashlib.sha256()
    for path in paths:
        value.update(path.relative_to(ROOT).as_posix().encode()); value.update(path.read_bytes())
    return value.hexdigest()


def owned(item, run, network=False):
    labels = item.get('Labels', {}) if network else item.get('Config', {}).get('Labels', {})
    return isinstance(labels, dict) and labels.get(OWNER) == run


def resource_proof(item, network, network_name):
    host = item['HostConfig']; profile = item['Config']
    expected = {'Memory': 1536*1024*1024, 'MemorySwap': 1536*1024*1024, 'ShmSize':16*1024*1024,
        'NanoCpus': 2000000000, 'PidsLimit': 128, 'ReadonlyRootfs': True}
    if any(type(host.get(key)) is not type(value) or host[key] != value for key, value in expected.items()): raise ValueError('Runtime resource constraints changed')
    if profile.get('User') != '10001:10001' or 'ALL' not in host.get('CapDrop', []): raise ValueError('Runtime privileges changed')
    if host.get('Privileged') is not False or host.get('CapAdd'): raise ValueError('Runtime privileges expanded')
    if not any(value.startswith('no-new-privileges') for value in host.get('SecurityOpt', [])): raise ValueError('Runtime privileges not bounded')
    if set(item['NetworkSettings']['Networks']) != {network_name} or network.get('Internal') is not True or network.get('EnableIPv6') is not False:
        raise ValueError('OCR network is not exclusively internal')
    if host.get('PortBindings'): raise ValueError('OCR runtime must not publish a host port')
    if not any(m.get('Destination') == '/models' and m.get('RW') is False for m in item.get('Mounts', [])):
        raise ValueError('Models are not mounted read-only')
    return {**expected, 'nonroot_user': profile['User'], 'capabilities_dropped': True, 'no_new_privileges': True,
        'internal_network_only': True, 'host_port_published': False, 'models_read_only': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-linux-ocr', action='store_true')
    parser.add_argument('--models-root', type=Path, default=ROOT/'.cache/ocr-prepared')
    parser.add_argument('--wheelhouse', type=Path, default=ROOT/'.cache/ocr-linux-wheels')
    args = parser.parse_args()
    if not args.allow_isolated_linux_ocr: parser.error('Explicit --allow-isolated-linux-ocr required')
    sys.path.insert(0, str(ROOT/'services/ocr'))
    from model_assets import verify_models
    from prepare_runtime_wheels import verify_wheels
    models = args.models_root.resolve(); verify_models(models)
    wheels = args.wheelhouse.resolve(); verify_wheels(wheels)
    run = uuid.uuid4().hex; work = ROOT/'.cache'/('linux-ocr-'+run); work.mkdir(parents=True)
    print('Linux OCR run: '+str(work), flush=True)
    before = digest(); report = {'passed': False, 'source_sha256': before, 'full_api_acceptance_passed': False,
        'scope': 'Linux x86_64 CPU image; generated mileage image; no WeChat, owner instrument accuracy or production acceptance'}
    private = 'ocr-private-'+run[:10]; outside = 'ocr-control-'+run[:10]
    server = 'ocr-runtime-'+run[:10]; control = 'ocr-control-'+run[:10]
    containers = [server, control]; networks = [private, outside]
    environment = work/'runtime.private.env'
    environment.write_text('OCR_ENABLED=true\nOCR_SERVICE_TOKEN='+secrets.token_hex(32)+'\n', encoding='utf-8')

    def docker(arguments, *, timeout=120, required=True):
        result = subprocess.run(['docker', *arguments], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        with (work/'docker.private.log').open('a', encoding='utf-8') as log: log.write(result.stdout+result.stderr)
        if required and result.returncode: raise ValueError('Owned Docker operation failed; inspect private log')
        return result

    def inspect(name, network=False):
        result = docker(inspection_arguments(name,network), required=False)
        return inspected_object(result,name,network)

    def ephemeral(name, arguments, timeout=120):
        containers.append(name)
        return docker(['run','--rm','--pull=never','--name',name,'--label',OWNER+'='+run, *arguments], timeout=timeout)

    def execute(code): return json.loads(docker(['exec',server,'python','-c',code]).stdout)

    def worker_identity():
        return execute(getsource(worker_command)+"\nfrom pathlib import Path;import json; rows=[]\nfor p in Path('/proc').iterdir():\n if not p.name.isdigit():continue\n try:\n  cmd=(p/'cmdline').read_bytes().split(bytes([0]))\n  if not worker_command(cmd):continue\n  status=dict(line.split(':',1) for line in (p/'status').read_text().splitlines() if ':' in line)\n  rows.append({'pid':int(p.name),'start':(p/'stat').read_text().split()[21],'filters':int(status['Seccomp_filters']),'uid':status['Uid'].split()[0]})\n except FileNotFoundError:continue\nparent=dict(line.split(':',1) for line in Path('/proc/1/status').read_text().splitlines() if ':' in line)\nprint(json.dumps({'workers':rows,'parent_filters':int(parent['Seccomp_filters'])}))")

    try:
        docker(['build','--tag',IMAGE,'--label','autocare.ocr.source-sha='+before,'--build-context','ocr_wheels='+str(wheels),
            '--file',str(ROOT/'services/ocr/Dockerfile.runtime'),str(ROOT/'services/ocr')], timeout=1800)
        image = json.loads(docker(['image','inspect',IMAGE]).stdout)[0]; runtime_image = image['Id']; report['image_id'] = runtime_image
        if image['Config']['Labels'].get('autocare.ocr.source-sha') != before: raise ValueError('Image source identity changed')
        for name in networks:
            docker(['network','create','--label',OWNER+'='+run, *(['--internal'] if name == private else []), name])
        common = ['--memory','1536m','--memory-swap','1536m','--shm-size','16m','--cpus','2','--pids-limit','128','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges:true','--tmpfs','/tmp:size=67108864,mode=1777']
        docker(['run','--detach','--pull=never','--name',server,'--label',OWNER+'='+run,'--network',private,'--network-alias','ocr',
            *common,'--env-file',str(environment),'--mount',f'type=bind,source={models},target=/models,readonly',runtime_image])
        container = inspect(server); report['resources'] = resource_proof(container,inspect(private,True),private)
        # Wait for real model startup before taking the worker identity baseline.
        ready = "import json,time,urllib.request\nend=time.monotonic()+60\nwhile True:\n try:\n  r=json.load(urllib.request.urlopen('http://localhost:8090/health',timeout=2))\n  if r.get('recognition')=='READY':break\n except OSError:pass\n if time.monotonic()>end:raise RuntimeError('OCR not ready')\n time.sleep(.2)\nprint(json.dumps({'ready':True}))"
        if execute(ready) != {'ready':True}: raise ValueError('Runtime readiness missing')
        initial = worker_identity()
        if len(initial['workers']) != 1: raise ValueError('Expected exactly one worker')
        worker = initial['workers'][0]
        if worker['filters'] <= initial['parent_filters'] or worker['uid'] != '10001': raise ValueError('Worker filter or identity missing')
        client = ephemeral('ocr-client-'+run[:10], ['--network',private,'--env-file',str(environment),
            '--mount',f'type=bind,source={ROOT/"services/ocr/container_check.py"},target=/check.py,readonly',runtime_image,'python','/check.py'])
        report['real_model'] = json.loads(client.stdout)
        if report['real_model'].get('passed') is not True or worker_identity() != initial: raise ValueError('Warm process was replaced or real inference failed')
        report['warm_worker_reused'] = True; report['worker_filter_count'] = worker['filters']; report['parent_filter_count'] = initial['parent_filters']
        report['kernel_resources'] = execute("from pathlib import Path;import json,os; p=Path('/sys/fs/cgroup'); q,t=map(int,(p/'cpu.max').read_text().split());s=os.statvfs('/tmp');m=os.statvfs('/dev/shm');print(json.dumps({'memory_bytes':int((p/'memory.max').read_text()),'swap_bytes':int((p/'memory.swap.max').read_text()),'cpus':q/t,'pids':int((p/'pids.max').read_text()),'tmp_bytes':s.f_blocks*s.f_frsize,'shm_bytes':m.f_blocks*m.f_frsize}))")
        if report['kernel_resources'] != {'memory_bytes':1536*1024*1024,'swap_bytes':0,'cpus':2,'pids':128,'tmp_bytes':64*1024*1024,'shm_bytes':16*1024*1024}: raise ValueError('Kernel constraints do not match')
        docker(['run','--detach','--pull=never','--name',control,'--label',OWNER+'='+run,'--network',outside,runtime_image,'python','-m','http.server','8091','--bind','0.0.0.0'])
        address = inspect(control)['NetworkSettings']['Networks'][outside]['IPAddress']
        positive = "import time,urllib.request\nend=time.monotonic()+10\nwhile True:\n try:\n  assert urllib.request.urlopen('http://"+address+":8091',timeout=2).status==200;break\n except OSError:\n  if time.monotonic()>end:raise\n  time.sleep(.1)\nprint('reachable')"
        result = ephemeral('ocr-control-client-'+run[:10], ['--network',outside,runtime_image,'python','-c',positive])
        if result.stdout.strip() != 'reachable': raise ValueError('Outside positive control failed')
        negative = "import json,urllib.request\ntry:urllib.request.urlopen('http://"+address+":8091',timeout=2)\nexcept OSError:print(json.dumps({'blocked':True}))\nelse:raise RuntimeError('OCR parent reached external control network')"
        if execute(negative) != {'blocked':True}: raise ValueError('Network isolation missing')
        report['network_controls'] = {'outside_service_positive':True,'ocr_parent_outside_blocked':True,'worker_kernel_filter_installed':True}
        tests = "import sys,unittest,json;sys.path.insert(0,'/tests');s=unittest.defaultTestLoader.discover('/tests',pattern='test_*.py');r=unittest.TextTestRunner().run(s);print(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'skipped':len(r.skipped)}));sys.exit(0 if r.wasSuccessful() else 1)"
        result = ephemeral('ocr-tests-'+run[:10], ['--network','none',*common,'--mount',f'type=bind,source={ROOT/"services/ocr"},target=/tests,readonly',runtime_image,'python','-c',tests])
        report['linux_tests'] = json.loads(result.stdout)
        if report['linux_tests']['tests'] < 60 or any(report['linux_tests'][key] for key in ['failures','errors','skipped']): raise ValueError('Linux suite incomplete')
        report['sources_unchanged'] = before == digest()
        if not report['sources_unchanged']: raise ValueError('Source changed during acceptance')
        report['passed'] = True
    except Exception as error:
        report['error_kind'] = type(error).__name__
        (work/'error.private.log').write_text(str(error), encoding='utf-8')
        try:
            if inspect(server): docker(['logs',server],required=False)
        except Exception: report['runtime_log_collection_failed'] = True
    finally:
        def remove(name, network=False):
            docker(['network','rm',name] if network else ['rm','--force',name],required=False)
        cleanup = cleanup_owned(run,containers,networks,inspect,remove)
        try: environment.unlink(missing_ok=True)
        except OSError: pass
        report['cleanup'] = {'owned_containers_and_networks_removed':cleanup,'private_environment_removed':not environment.exists()}
        report['passed'] = report['passed'] and all(report['cleanup'].values())
        (work/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('Linux OCR acceptance: '+('PASS' if report['passed'] else 'FAIL; inspect private log'),flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__': raise SystemExit(main())
