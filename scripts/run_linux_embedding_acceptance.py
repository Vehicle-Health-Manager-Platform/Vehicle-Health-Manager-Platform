"""Explicit local Linux embedding acceptance; no deployment or user-text upload."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'autocare.embedding.acceptance'
IMAGE = 'autocare-embedding-runtime:acceptance'


def digest():
    paths = [p for p in (ROOT / 'services/embedding').rglob('*') if p.is_file() and
        (p.suffix in ('.py', '.json', '.txt', '.in') or p.name == 'Dockerfile')]
    paths += [ROOT / 'services/ocr/src/process_limits.py', ROOT / 'services/ocr/src/network_guard.py', Path(__file__), ROOT / 'scripts/embedding_java_bridge.py', ROOT / 'backend/src/test/java/com/autocare/platform/order/RealEmbeddingIndexJourneyTest.java']
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
    required = {'/models', '/workspace/services/embedding'}
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
    parser.add_argument('--allow-isolated-linux-embedding', action='store_true')
    parser.add_argument('--models-root', type=Path, required=True)
    parser.add_argument('--wheelhouse', type=Path, required=True)
    parser.add_argument('--java-maven',type=Path)
    parser.add_argument('--maven-repository',type=Path)
    args = parser.parse_args(argv)
    if not args.allow_isolated_linux_embedding:
        parser.error('Explicit --allow-isolated-linux-embedding required')
    if bool(args.java_maven)!=bool(args.maven_repository):parser.error('Both Java acceptance paths required')
    sys.path.insert(0, str(ROOT / 'services/embedding'))
    from prepare_assets import verify
    models = args.models_root.resolve(strict=True)
    wheels = args.wheelhouse.resolve(strict=True)
    verify(models, 'model')
    verify(wheels, 'wheels')
    run = uuid.uuid4().hex
    work = ROOT / '.cache' / ('linux-embedding-' + run)
    work.mkdir(parents=True)
    print('Linux embedding run: ' + str(work), flush=True)
    name = 'embedding-acceptance-' + run[:12]
    before = digest()
    report = {'passed': False, 'source_sha256': before, 'production_acceptance_passed': False,
        'real_user_accuracy_accepted': False, 'automotive_semantic_accuracy_accepted': False}

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
        docker(['build', '--progress=plain', '--build-context', 'embedding_wheels=' + str(wheels),
            '-f', str(ROOT / 'services/embedding/Dockerfile'), '-t', IMAGE, str(ROOT)], timeout=600)
        docker(['run', '-d', '--name', name, '--label', OWNER + '=' + run, '--network', 'none',
            '--memory', '2g', '--memory-swap', '2g', '--cpus', '2', '--pids-limit', '128', '--shm-size', '16m',
            '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--tmpfs', '/tmp:rw,noexec,nosuid,size=256m,mode=1777',
            '--mount', f'type=bind,src={models},dst=/models,readonly',
            '--mount', f'type=bind,src={ROOT / "services/embedding"},dst=/workspace/services/embedding,readonly',
            IMAGE, 'python', '-c', 'import time;time.sleep(600)'])
        item = inspect()
        if item is None or not owned(item, run):
            raise RuntimeError('Container owner mismatch')
        report['resource_proof'] = resource_proof(item)
        report['image_id'] = item['Image']
        report['guard_image_id'] = json.loads(docker(['image','inspect','autocare-asr-runtime:acceptance']).stdout)[0]['Id']
        report['guard_library_sha256'] = docker(['exec',name,'sha256sum','/usr/lib/x86_64-linux-gnu/libgomp.so.1','/usr/lib/x86_64-linux-gnu/libseccomp.so.2']).stdout.splitlines()
        report['os_packages'] = docker(['exec', name, 'dpkg-query', '-W', '-f=${Package}=${Version}\n']).stdout.splitlines()
        result = docker(['exec', name, 'python', '/workspace/services/embedding/linux_acceptance.py'], timeout=400, required=False)
        if result.stdout.strip():
            report['runtime'] = json.loads(result.stdout)
        if result.returncode or not report.get('runtime', {}).get('passed'):
            raise RuntimeError('Real-model checks did not pass')
        if args.java_maven:
            from embedding_java_bridge import Bridge,TOKEN
            docker(['exec','-d','-e','EMBEDDING_SERVICE_ENABLED=true','-e','EMBEDDING_MODEL_ENABLED=true','-e','EMBEDDING_MODEL_ROOT=/models','-e','EMBEDDING_SERVICE_TOKEN='+TOKEN,name,'python','embedding_server.py'])
            docker(['exec',name,'python','-c',"import http.client,time\nfor i in range(60):\n try:\n  c=http.client.HTTPConnection('127.0.0.1',8093,timeout=1);c.request('GET','/health');r=c.getresponse();assert r.status==200;r.read();c.close();break\n except OSError:time.sleep(.2)\nelse:raise RuntimeError('private server unavailable')"])
            with Bridge(name) as bridge:
                env=dict(os.environ,REAL_EMBEDDING_ACCEPTANCE='true',REAL_EMBEDDING_URL=bridge.url,REAL_EMBEDDING_TOKEN=TOKEN,REAL_EMBEDDING_PROFILE=json.dumps(report['runtime']['profile'],ensure_ascii=False))
                java=subprocess.run([str(args.java_maven.resolve()),'-B','-o','-Dmaven.repo.local='+str(args.maven_repository.resolve()),'-f',str(ROOT/'backend/pom.xml'),'-Dtest=RealEmbeddingIndexJourneyTest','test'],env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=500)
                (work/'java.private.log').write_text(java.stdout+java.stderr,encoding='utf-8')
                import xml.etree.ElementTree as ET
                suite=ET.parse(ROOT/'backend/target/surefire-reports/TEST-com.autocare.platform.order.RealEmbeddingIndexJourneyTest.xml').getroot()
                report['java_mysql_actual_model']={key:int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')}
                if java.returncode or report['java_mysql_actual_model']!={'tests':31,'failures':0,'errors':0,'skipped':0}:raise RuntimeError('Java/MySQL actual model checks failed')
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
