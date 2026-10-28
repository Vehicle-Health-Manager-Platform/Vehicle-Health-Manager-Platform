"""Explicit local Linux ASR acceptance; no deployment or user-audio upload."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'autocare.asr.acceptance'
IMAGE = 'autocare-asr-runtime:acceptance'


def digest():
    paths = [p for p in (ROOT / 'services/asr').rglob('*') if p.is_file() and
        (p.suffix in ('.py', '.json', '.txt', '.in') or p.name == 'Dockerfile')]
    paths += [ROOT / 'services/ocr/src/process_limits.py', ROOT / 'services/ocr/src/network_guard.py', Path(__file__)]
    value = hashlib.sha256()
    for path in sorted(paths):
        value.update(path.relative_to(ROOT).as_posix().encode())
        value.update(path.read_bytes())
    return value.hexdigest()


def resource_proof(item):
    host, config = item['HostConfig'], item['Config']
    expected = {'Memory': 2*1024**3, 'MemorySwap': 2*1024**3, 'NanoCpus': 2000000000,
        'PidsLimit': 128, 'ShmSize': 16*1024**2, 'ReadonlyRootfs': True, 'NetworkMode': 'none'}
    if any(type(host.get(key)) is not type(value) or host[key] != value for key, value in expected.items()):
        raise ValueError('Runtime resource constraints changed')
    if config.get('User') != '10001:10001' or host.get('Privileged') is not False or host.get('CapAdd') or \
            host.get('CapDrop') != ['ALL'] or not any(x.startswith('no-new-privileges') for x in host.get('SecurityOpt', [])) or host.get('PortBindings'):
        raise ValueError('Runtime privilege or port changed')
    mounts = item.get('Mounts', [])
    required = {'/models', '/workspace/services/asr', '/fixtures/synthetic-english.wav'}
    bound = [m for m in mounts if m.get('Type') == 'bind']
    if {m['Destination'] for m in bound} != required or any(m.get('RW') is not False for m in bound):
        raise ValueError('Unexpected or writable resource bind')
    temporary = host.get('Tmpfs', {})
    if set(temporary) != {'/tmp'} or not {'rw','noexec','nosuid','size=256m','mode=1777'} <= set(temporary.get('/tmp','').split(',')):
        raise ValueError('Unexpected writable temporary filesystem')
    return {**expected, 'nonroot': True, 'capabilities_dropped': True, 'no_new_privileges': True,
        'host_ports': False, 'resource_binds_readonly': True}


def owned(item, run):
    return item.get('Config', {}).get('Labels', {}).get(OWNER) == run


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-linux-asr', action='store_true')
    parser.add_argument('--models-root', type=Path, required=True)
    parser.add_argument('--wheelhouse', type=Path, required=True)
    parser.add_argument('--synthetic-english-wav', type=Path, required=True)
    args = parser.parse_args(argv)
    if not args.allow_isolated_linux_asr:
        parser.error('Explicit --allow-isolated-linux-asr required')
    sys.path.insert(0, str(ROOT / 'services/asr'))
    from prepare_assets import verify
    models = args.models_root.resolve(strict=True)
    wheels = args.wheelhouse.resolve(strict=True)
    audio = args.synthetic_english_wav.resolve(strict=True)
    if not audio.is_file() or audio.suffix.lower() != '.wav' or not 44 < audio.stat().st_size <= 8*1024**2:
        raise ValueError('Expected bounded, synthetic English WAV')
    verify(models, 'model')
    verify(wheels, 'wheels')
    run = uuid.uuid4().hex
    work = ROOT / '.cache' / ('linux-asr-' + run)
    work.mkdir(parents=True)
    print('Linux ASR run: ' + str(work), flush=True)
    name = 'asr-acceptance-' + run[:12]
    before = digest()
    report = {'passed': False, 'source_sha256': before, 'production_acceptance_passed': False,
        'real_user_accuracy_accepted': False, 'chinese_accuracy_accepted': False,
        'synthetic_audio_sha256': hashlib.sha256(audio.read_bytes()).hexdigest()}

    def docker(arguments, timeout=120, required=True):
        result = subprocess.run(['docker', *arguments], capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=timeout)
        with (work / 'docker.private.log').open('a', encoding='utf-8') as log:
            log.write(result.stdout + result.stderr)
        if required and result.returncode:
            raise RuntimeError('Isolated Docker operation failed; inspect private log')
        return result

    def inspect():
        result = docker(['container', 'inspect', name], required=False)
        if result.returncode:
            if result.stderr.strip() in (f'Error response from daemon: No such container: {name}', f'Error: No such object: {name}') and result.stdout.strip() in ('', '[]'):
                return None
            raise RuntimeError('Cannot verify owned container')
        rows = json.loads(result.stdout)
        if not isinstance(rows, list) or len(rows) != 1:
            raise RuntimeError('Unexpected Docker inspection')
        return rows[0]

    try:
        docker(['build', '--progress=plain', '--build-context', 'asr_wheels=' + str(wheels),
            '-f', str(ROOT / 'services/asr/Dockerfile'), '-t', IMAGE, str(ROOT)], timeout=600)
        docker(['run', '-d', '--name', name, '--label', OWNER + '=' + run, '--network', 'none',
            '--memory', '2g', '--memory-swap', '2g', '--cpus', '2', '--pids-limit', '128', '--shm-size', '16m',
            '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--tmpfs', '/tmp:rw,noexec,nosuid,size=256m,mode=1777',
            '--mount', f'type=bind,src={models},dst=/models,readonly',
            '--mount', f'type=bind,src={ROOT / "services/asr"},dst=/workspace/services/asr,readonly',
            '--mount', f'type=bind,src={audio},dst=/fixtures/synthetic-english.wav,readonly',
            IMAGE, 'python', '-c', 'import time;time.sleep(600)'])
        item = inspect()
        if item is None or not owned(item, run):
            raise RuntimeError('Container owner mismatch')
        report['resource_proof'] = resource_proof(item)
        report['image_id'] = item['Image']
        report['os_packages'] = docker(['exec', name, 'dpkg-query', '-W', '-f=${Package}=${Version}\n']).stdout.splitlines()
        result = docker(['exec', name, 'python', '/workspace/services/asr/linux_acceptance.py'], timeout=400, required=False)
        if result.stdout.strip():
            report['runtime'] = json.loads(result.stdout)
        if result.returncode or not report.get('runtime', {}).get('passed'):
            raise RuntimeError('Real-model checks did not pass')
        report['memory_peak_bytes'] = int(docker(['exec', name, 'cat', '/sys/fs/cgroup/memory.peak']).stdout)
        report['passed'] = True
    except Exception as error:
        report['failure'] = type(error).__name__ + ': ' + str(error)
    finally:
        try:
            item = inspect()
            if item is not None:
                if not owned(item, run):
                    raise RuntimeError('Refusing cleanup of unowned container')
                docker(['container', 'rm', '-f', name])
            report['cleanup_complete'] = inspect() is None
        except Exception:
            report['cleanup_complete'] = False
        report['sources_unchanged'] = digest() == before
        report['passed'] = report['passed'] and report['cleanup_complete'] and report['sources_unchanged']
        (work / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'passed': report['passed'], 'report': str(work / 'report.json')}, ensure_ascii=False))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
