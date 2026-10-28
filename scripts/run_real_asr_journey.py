"""Local real Java/ASR journey and process fault acceptance. Explicit opt in only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.request import ProxyHandler, build_opener
import uuid
import xml.etree.ElementTree as ET

from run_linux_asr_acceptance import resource_proof as offline_proof
from run_local_delivery import source_digest

ROOT=Path(__file__).resolve().parents[1]
OWNER='autocare.asr.realjourney'
IMAGE='autocare-asr-runtime:acceptance'


def network_proof(item,network,name):
    host=item['HostConfig']
    if host.get('NetworkMode')!=name or set(item['NetworkSettings']['Networks'])!={name} or network.get('Internal') is not True or network.get('EnableIPv6') is not False:
        raise ValueError('Expected exclusively internal network')
    if host.get('PortBindings'):
        raise ValueError('Model service must not publish a port')
    # Reuse the exact resource/mount constraints of the offline runner; retain actual
    # network evidence separately, rather than label this network=none.
    import copy
    normalized=copy.deepcopy(item);normalized['HostConfig'].update(NetworkMode='none',PortBindings={})
    proof=offline_proof(normalized)
    proof.update(NetworkMode=name,internal_network_only=True)
    return proof


def relay_proof(item,private,outside):
    host=item['HostConfig'];ports=host.get('PortBindings',{})
    if set(item['NetworkSettings']['Networks'])!={private,outside} or set(ports)!={'8092/tcp'} or len(ports['8092/tcp'])!=1 or ports['8092/tcp'][0].get('HostIp')!='127.0.0.1':
        raise ValueError('Expected dedicated loopback relay')
    if item['Config'].get('User')!='10001:10001' or host.get('ReadonlyRootfs') is not True or host.get('Privileged') is not False or host.get('CapAdd') or host.get('CapDrop')!=['ALL'] or not any(x.startswith('no-new-privileges') for x in host.get('SecurityOpt',[])):
        raise ValueError('Relay privileges changed')
    if host.get('Memory')!=64*1024**2 or host.get('MemorySwap')!=64*1024**2 or host.get('PidsLimit')!=32 or host.get('NanoCpus')!=500000000:
        raise ValueError('Relay resources changed')
    return {'host_port_loopback_only':True,'nonroot':True,'readonly_rootfs':True,'memory_bytes':64*1024**2,'dedicated_networks':True}


def relay_configuration(address):
    import ipaddress
    if not ipaddress.ip_address(address).is_private:raise ValueError('Expected owned private model address')
    return '''pid /tmp/nginx.pid;
error_log /dev/stderr warn;
events { worker_connections 32; }
http {
 access_log off;
 client_body_temp_path /tmp/body;
 proxy_temp_path /tmp/proxy;
 fastcgi_temp_path /tmp/fastcgi;
 uwsgi_temp_path /tmp/uwsgi;
 scgi_temp_path /tmp/scgi;
 client_max_body_size 5m;
 server { listen 8092;
  location = /health { limit_except GET { deny all; } proxy_pass http://'''+address+''':8091; }
  location = /transcribe { limit_except POST { deny all; }
   proxy_pass http://'''+address+''':8091;
   proxy_read_timeout 95s;
   proxy_next_upstream off;
   proxy_buffering off;
  }
  location / { return 404; }
 }
}
'''


def suites(directory,names):
    rows=[]
    for name in names:
        path=directory/('TEST-com.autocare.platform.order.'+name+'.xml')
        node=ET.parse(path).getroot()
        row={'suite':name,**{key:int(node.attrib[key]) for key in ('tests','failures','errors','skipped')}}
        row['report_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
        if row['tests']<1 or any(row[key] for key in ('failures','errors','skipped')):
            raise ValueError('Selected suite did not fully pass')
        rows.append(row)
    return rows


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-isolated-real-asr',action='store_true')
    parser.add_argument('--models-root',type=Path,required=True)
    parser.add_argument('--wheelhouse',type=Path,required=True)
    parser.add_argument('--synthetic-english-wav',type=Path,required=True)
    args=parser.parse_args(argv)
    if not args.allow_isolated_real_asr:parser.error('Explicit --allow-isolated-real-asr required')
    sys.path.insert(0,str(ROOT/'services/asr'))
    from prepare_assets import verify
    models=args.models_root.resolve(strict=True);wheels=args.wheelhouse.resolve(strict=True)
    audio=args.synthetic_english_wav.resolve(strict=True)
    verify(models,'model');verify(wheels,'wheels')
    if not audio.is_file() or audio.suffix.lower()!='.wav' or not 44<audio.stat().st_size<=8*1024**2:raise ValueError('Expected bounded synthetic WAV')
    run=uuid.uuid4().hex;work=ROOT/'.cache'/('real-asr-'+run);work.mkdir(parents=True)
    print('Real ASR run: '+str(work),flush=True)
    network='asr-private-'+run[:12];outside='asr-relay-net-'+run[:12]
    server='asr-real-'+run[:12];oom='asr-oom-'+run[:12];relay='asr-relay-'+run[:12]
    containers=[];networks=[];before=source_digest(ROOT)
    token=secrets.token_hex(32);environment=work/'asr.private.env'
    environment.write_text('ASR_ISOLATED_ACCEPTANCE=true\nASR_SERVICE_TOKEN='+token+'\n',encoding='utf-8')
    report={'passed':False,'source_sha256':before,'scope':'Real Java scheduler/HTTP/JWT/MySQL and model; synthetic identity, CLEAN metadata, object store and English audio',
        'chinese_or_user_accuracy_accepted':False,'production_acceptance_passed':False,'checks':{}}
    def docker(arguments,timeout=120,required=True,input_text=None):
        result=subprocess.run(['docker',*arguments],capture_output=True,text=True,input=input_text,encoding='utf-8',errors='replace',timeout=timeout)
        with (work/'docker.private.log').open('a',encoding='utf-8') as log:log.write(result.stdout+result.stderr)
        if required and result.returncode:raise RuntimeError('Owned Docker operation failed; inspect private log')
        return result
    def inspect(name,kind='container'):
        result=docker([kind,'inspect',name],required=False)
        if result.returncode:
            missing={f'Error response from daemon: No such {kind}: {name}',f'Error: No such object: {name}',f'Error response from daemon: network {name} not found'}
            if result.stderr.strip() in missing and result.stdout.strip() in ('','[]'):return None
            raise RuntimeError('Cannot inspect owned resource')
        rows=json.loads(result.stdout)
        if not isinstance(rows,list) or len(rows)!=1:raise RuntimeError('Unexpected resource inspection')
        return rows[0]
    def owned(item,kind):
        labels=item.get('Labels',{}) if kind=='network' else item.get('Config',{}).get('Labels',{})
        return labels.get(OWNER)==run
    def start(name,low_memory=False):
        containers.append(name)
        commands=['run','-d','--name',name,'--label',OWNER+'='+run,'--network','none' if low_memory else network,
            '--memory','256m' if low_memory else '2g','--memory-swap','256m' if low_memory else '2g',
            '--cpus','2','--pids-limit','128','--shm-size','16m','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges:true','--tmpfs','/tmp:rw,noexec,nosuid,size=256m,mode=1777',
            '--env-file',str(environment),'--mount',f'type=bind,src={models},dst=/models,readonly',
            '--mount',f'type=bind,src={ROOT/"services/asr"},dst=/workspace/services/asr,readonly']
        if low_memory:commands+=['--mount',f'type=bind,src={work/"request.json"},dst=/fixtures/asr-request.json,readonly']
        else:commands+=['--mount',f'type=bind,src={audio},dst=/fixtures/synthetic-english.wav,readonly']
        docker([*commands,IMAGE,'python','/workspace/services/asr/serve_acceptance.py'])
        item=inspect(name)
        if item is None or not owned(item,'container'):raise RuntimeError('Container owner mismatch')
        return item
    try:
        docker(['build','--progress=plain','--build-context','asr_wheels='+str(wheels),'-f',str(ROOT/'services/asr/Dockerfile'),'-t',IMAGE,str(ROOT)],timeout=600)
        for name,internal in ((network,True),(outside,False)):
            docker(['network','create',*(['--internal'] if internal else []),'--label',OWNER+'='+run,name]);networks.append(name)
        item=start(server);report['resource_proof']=network_proof(item,inspect(network,'network'),network);report['image_id']=item['Image']
        address=item['NetworkSettings']['Networks'][network]['IPAddress']
        nginx=work/'relay.conf';nginx.write_text(relay_configuration(address),encoding='utf-8')
        containers.append(relay)
        docker(['run','-d','--name',relay,'--label',OWNER+'='+run,'--network',outside,'-p','127.0.0.1::8092',
            '--user','10001:10001','--memory','64m','--memory-swap','64m','--cpus','.5','--pids-limit','32',
            '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp:rw,noexec,nosuid,size=16m,mode=1777',
            '--mount',f'type=bind,src={nginx},dst=/config/relay.conf,readonly','--entrypoint','nginx','nginx:1.27-alpine',
            '-c','/config/relay.conf','-g','daemon off;'])
        docker(['network','connect',network,relay]);bridge=inspect(relay)
        if not owned(bridge,'container'):raise RuntimeError('Relay owner mismatch')
        report['relay_proof']=relay_proof(bridge,network,outside);report['relay_image_id']=bridge['Image']
        bindings=bridge['NetworkSettings']['Ports'].get('8092/tcp',[])
        if len(bindings)!=1 or bindings[0]['HostIp']!='127.0.0.1':raise RuntimeError('Actual loopback mapping unavailable')
        port=bindings[0]['HostPort'];url='http://127.0.0.1:'+port
        opener=build_opener(ProxyHandler({}));deadline=time.monotonic()+30
        while True:
            try:
                with opener.open(url+'/health',timeout=2) as response:
                    assert json.load(response)['recognition']=='CONFIG_VERIFIED'
                break
            except Exception:
                if time.monotonic()>deadline:raise RuntimeError('Local ASR startup unavailable')
                time.sleep(.2)
        generated=docker(['exec',server,'python','-c','import sys;sys.path.insert(0,"/workspace/services/asr");from linux_acceptance import fixtures;print(fixtures()["english_tts"].decode())'])
        raw=generated.stdout.strip().encode();fixture=json.loads(raw);(work/'request.json').write_bytes(raw)
        mp3=base64.b64decode(fixture['audio_base64'],validate=True);(work/'audio.mp3').write_bytes(mp3)
        # Docker cp rejects a read-only rootfs even for tmpfs; write only this fixed
        # temporary fixture through an owned, nonroot exec and bounded stdin.
        docker(['exec','-i',server,'python','-c','import sys;from pathlib import Path;from asr_protocol import MAX_BODY;raw=sys.stdin.buffer.read(MAX_BODY+1);assert 0<len(raw)<=MAX_BODY;Path("/tmp/asr-request.json").write_bytes(raw)'],input_text=raw.decode())
        config=work/'java.private.json';config.write_text(json.dumps({'url':url+'/transcribe','token':token,'audio_path':str(work/'audio.mp3'),'audio_bytes':len(mp3),'duration_ms':fixture['duration_ms']}),encoding='utf-8')
        env=dict(os.environ,ASR_REAL_ACCEPTANCE='true',ASR_REAL_CONFIG_FILE=str(config),PYTHONUTF8='1')
        selected=['RealAsrModelJourneyTest','JdbcAsrJobsTest','AsrHttpJourneyTest','AsrResultTest','AsrHttpClientTest']
        output=ROOT/'backend/target/surefire-reports'
        for name in selected:
            (output/('TEST-com.autocare.platform.order.'+name+'.xml')).unlink(missing_ok=True)
        command=[str(ROOT/'.cache/host-tools/maven/bin/mvn.cmd'),'-B','-o','-Dmaven.repo.local='+str(ROOT/'.cache/host-tools/m2/repository'),'-f',str(ROOT/'backend/pom.xml'),'-Dtest='+','.join(selected),'-DforkCount=1','test']
        with (work/'maven.private.log').open('wb') as log:
            result=subprocess.run(command,cwd=ROOT/'backend',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=900)
        if result.returncode:raise RuntimeError('Selected Java real journey failed; inspect private Maven log')
        report['java_suites']=suites(output,selected);report['checks']['java_real_journey']=True
        import shutil
        archived=work/'reports.private';archived.mkdir()
        for name in selected:shutil.copyfile(output/('TEST-com.autocare.platform.order.'+name+'.xml'),archived/('TEST-'+name+'.xml'))
        # Fault helper reads a fixed temporary fixture; no production protocol additions.
        fault=docker(['exec','-e','ASR_FAULT_FIXTURE=/tmp/asr-request.json',server,'python','/workspace/services/asr/linux_fault_acceptance.py'],timeout=150,required=False)
        report['process_faults']=json.loads(fault.stdout)
        if fault.returncode or not report['process_faults']['passed']:raise RuntimeError('Real process fault acceptance failed')
        low=start(oom,True);host=low['HostConfig']
        if host['Memory']!=256*1024**2 or host['MemorySwap']!=256*1024**2 or host['NetworkMode']!='none':raise RuntimeError('OOM container constraints changed')
        memory=docker(['exec',oom,'python','/workspace/services/asr/linux_oom_acceptance.py'],timeout=120,required=False)
        report['oom_fault']=json.loads(memory.stdout)
        if memory.returncode or not report['oom_fault']['passed']:raise RuntimeError('Dedicated real-model OOM acceptance failed')
        report['passed']=True
    except Exception as error:report['failure']=type(error).__name__+': '+str(error)
    finally:
        cleanup=True
        for name,kind in [(name,'container') for name in reversed(containers)]+[(name,'network') for name in reversed(networks)]:
            try:
                item=inspect(name,kind)
                if item is not None:
                    if not owned(item,kind):raise RuntimeError('Refusing unowned cleanup')
                    if kind=='container':docker(['logs',name],required=False)
                    docker([kind,'rm',*(['-f'] if kind=='container' else []),name])
                cleanup=cleanup and inspect(name,kind) is None
            except Exception:cleanup=False
        report['cleanup_complete']=cleanup;report['sources_unchanged']=source_digest(ROOT)==before
        report['passed']=report['passed'] and cleanup and report['sources_unchanged']
        (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report['passed'],'report':str(work/'report.json')},ensure_ascii=False),flush=True)
    return 0 if report['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
