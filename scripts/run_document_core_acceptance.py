"""Explicit Windows-host local document-kernel acceptance using prepared OCR resources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
from run_local_delivery import source_digest

ROOT=Path(__file__).resolve().parents[1]
OWNER='autocare.document.acceptance'


def prepare_java(archive,output):
    output.mkdir(parents=True)
    classes=re.compile(r'^BOOT-INF/classes/(com/autocare/platform/(?:recognition/DocumentPdf(?:Main|Plan)|file/(?:InputMaterialValidator|FileValidator|UploadHttpException|UploadException))(?:\$[A-Za-z0-9_]+)*\.class)$')
    libraries=re.compile(r'^BOOT-INF/lib/((?:pdfbox|pdfbox-io|fontbox|commons-logging|jackson-core|jackson-annotations|jackson-databind)-[0-9][A-Za-z0-9_.-]*\.jar)$')
    rows=[];names=set();total=0
    with zipfile.ZipFile(archive) as jar:
        for item in jar.infolist():
            cls=classes.fullmatch(item.filename);lib=libraries.fullmatch(item.filename)
            if not cls and not lib:continue
            relative=('classes/'+cls[1]) if cls else 'lib/'+lib[1]
            if relative in names or item.file_size>32*1024**2:raise ValueError('Invalid Java artifact inventory')
            total+=item.file_size
            if total>96*1024**2:raise ValueError('Java artifact budget')
            names.add(relative);target=output/relative;target.parent.mkdir(parents=True,exist_ok=True)
            digest=hashlib.sha256();count=0
            with jar.open(item) as source,target.open('xb') as dest:
                for chunk in iter(lambda:source.read(1024*1024),b''):
                    count+=len(chunk)
                    if count>item.file_size:raise ValueError('Java artifact size mismatch')
                    digest.update(chunk);dest.write(chunk)
            if count!=item.file_size:raise ValueError('Java artifact truncated')
            rows.append({'path':relative,'bytes':count,'sha256':digest.hexdigest()})
    required={'DocumentPdfMain','DocumentPdfPlan','InputMaterialValidator','FileValidator','UploadHttpException','UploadException'}
    actual={Path(name).stem for name in names if name.endswith('.class')}
    if not required<=actual or len([name for name in names if name.endswith('.jar')])!=7:raise ValueError('Required PDFBox/Jackson Java closure missing')
    for name in ('pdfbox-3.0.5.jar','pdfbox-io-3.0.5.jar','fontbox-3.0.5.jar'):
        if 'lib/'+name not in names:raise ValueError('Expected fixed PDFBox3.0.5 libraries')
    return rows


def resource_proof(item):
    host=item['HostConfig']
    expected={'Memory':2*1024**3,'MemorySwap':2*1024**3,'PidsLimit':128,'NanoCpus':2000000000,'ReadonlyRootfs':True,'NetworkMode':'none'}
    if any(type(host.get(k)) is not type(v) or host[k]!=v for k,v in expected.items()) or host.get('PortBindings') or host.get('Privileged') is not False or host.get('CapAdd') or host.get('CapDrop')!=['ALL'] or item['Config'].get('User')!='10001:10001' or not any(v.startswith('no-new-privileges') for v in host.get('SecurityOpt',[])):raise ValueError('Document resource profile changed')
    binds=[m for m in item.get('Mounts',[]) if m.get('Type')=='bind']
    if {m['Destination'] for m in binds}!={'/models','/fixtures','/document/tests'} or any(m.get('RW') is not False for m in binds):raise ValueError('Document resource binds changed')
    if set(host.get('Tmpfs',{}))!={'/tmp'} or not {'noexec','nosuid','size=256m','mode=1777'}<=set(host['Tmpfs']['/tmp'].split(',')):raise ValueError('Document temporary budget changed')
    return {**expected,'nonroot':True,'capabilities_dropped':True,'resource_binds_readonly':True,'host_ports':False}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--allow-isolated-document-core',action='store_true')
    parser.add_argument('--models-root',type=Path,default=ROOT/'.cache/ocr-prepared');args=parser.parse_args(argv)
    if not args.allow_isolated_document_core:parser.error('Explicit --allow-isolated-document-core required')
    sys.path.insert(0,str(ROOT/'services/ocr'))
    from model_assets import verify_models
    models=args.models_root.resolve(strict=True);verify_models(models)
    run=uuid.uuid4().hex;work=ROOT/'.cache'/('document-core-'+run);work.mkdir(parents=True)
    print('Document core run: '+str(work),flush=True)
    before=source_digest(ROOT);name='document-core-'+run[:12]
    report={'passed':False,'source_sha256':before,'scope':'Internal kernel and private HTTP; generated English documents; no public owner API, persistent task, UI or user accuracy acceptance','production_acceptance_passed':False}
    def execute(command,log,timeout=180,required=True):
        result=subprocess.run(command,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
        with (work/log).open('a',encoding='utf-8') as stream:stream.write(result.stdout+result.stderr)
        if required and result.returncode:raise RuntimeError('Local document acceptance operation failed; inspect private log')
        return result
    def docker(arguments,**kwargs):return execute(['docker',*arguments],'docker.private.log',**kwargs)
    def inspect():
        result=docker(['container','inspect',name],required=False)
        if result.returncode:
            if result.stderr.strip() in (f'Error response from daemon: No such container: {name}',f'Error: No such object: {name}') and result.stdout.strip() in ('','[]'):return None
            raise RuntimeError('Cannot inspect owned document container')
        rows=json.loads(result.stdout)
        if not isinstance(rows,list) or len(rows)!=1:raise RuntimeError('Unexpected Docker inspection')
        return rows[0]
    try:
        selected={'DocumentPdfPlanTest','InputMaterialValidatorTest','ArchiveReportPdfRendererTest'}
        for path in (ROOT/'backend/target/surefire-reports').glob('TEST-*.xml'):
            if path.stem.split('.')[-1] in selected:path.unlink()
        command=[str(ROOT/'.cache/host-tools/maven/bin/mvn.cmd'),'-B','-o','-Dmaven.repo.local='+str(ROOT/'.cache/host-tools/m2/repository'),'-f',str(ROOT/'backend/pom.xml'),'-Dtest=DocumentPdfPlanTest,InputMaterialValidatorTest,ArchiveReportPdfRendererTest','package']
        execute(command,'maven.private.log',timeout=300)
        rows=[]
        for path in (ROOT/'backend/target/surefire-reports').glob('TEST-*.xml'):
            if path.stem.split('.')[-1] not in selected:continue
            node=ET.parse(path).getroot();row={'suite':node.attrib['name'],**{k:int(node.attrib[k]) for k in ('tests','failures','errors','skipped')}}
            if any(row[k] for k in ('failures','errors','skipped')):raise RuntimeError('Selected Java suite did not fully pass')
            row['report_sha256']=hashlib.sha256(path.read_bytes()).hexdigest();rows.append(row)
            import shutil
            archived=work/'reports.private';archived.mkdir(exist_ok=True);shutil.copyfile(path,archived/path.name)
        if len(rows)!=3:raise RuntimeError('Selected Java evidence missing')
        report['java_suites']=rows
        artifact=ROOT/'backend/target/platform-backend-0.1.0-SNAPSHOT.jar'
        report['java_artifacts']=prepare_java(artifact,work/'java')
        fixtures=work/'fixtures'
        classpath=os.pathsep.join([str(ROOT/'backend/target/test-classes'),str(work/'java/classes'),str(work/'java/lib/*')])
        java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe')
        execute([java,'-Djava.awt.headless=true','-cp',classpath,'com.autocare.platform.recognition.DocumentFixtures',str(fixtures)],'fixtures.private.log')
        report['fixtures']={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in fixtures.iterdir()}
        docker(['build','--progress=plain','--build-context','document_java='+str(work/'java'),'-f',str(ROOT/'services/documents/Dockerfile.acceptance'),'-t','autocare-document-core:acceptance',str(ROOT)],timeout=600)
        docker(['run','-d','--name',name,'--label',OWNER+'='+run,'--network','none','--memory','2g','--memory-swap','2g','--cpus','2','--pids-limit','128','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
            '--tmpfs','/tmp:rw,noexec,nosuid,size=256m,mode=1777','-e','DOCUMENT_CORE_ENABLED=true',
            '--mount',f'type=bind,src={models},dst=/models,readonly','--mount',f'type=bind,src={fixtures},dst=/fixtures,readonly',
            '--mount',f'type=bind,src={ROOT/"services/documents"},dst=/document/tests,readonly','autocare-document-core:acceptance'])
        item=inspect()
        if item is None or item.get('Config',{}).get('Labels',{}).get(OWNER)!=run:raise RuntimeError('Document container owner mismatch')
        report['resources']=resource_proof(item);report['image_id']=item['Image']
        result=docker(['exec',name,'python','/document/tests/linux_acceptance.py'],timeout=600,required=False)
        report['runtime']=json.loads(result.stdout)
        if result.returncode or not report['runtime']['passed']:raise RuntimeError('Document real runtime acceptance did not pass')
        http=docker(['exec',name,'python','/document/tests/linux_http_acceptance.py'],timeout=300,required=False)
        report['http_runtime']=json.loads(http.stdout)
        if http.returncode or not report['http_runtime']['passed']:raise RuntimeError('Private document HTTP acceptance did not pass')
        report['memory_peak_bytes']=int(docker(['exec',name,'cat','/sys/fs/cgroup/memory.peak']).stdout);report['passed']=True
    except Exception as error:report['failure']=type(error).__name__+': '+str(error)
    finally:
        try:
            item=inspect()
            if item is not None:
                if item.get('Config',{}).get('Labels',{}).get(OWNER)!=run:raise RuntimeError('Refusing unowned cleanup')
                docker(['logs',name],required=False);docker(['container','rm','-f',name])
            report['cleanup_complete']=inspect() is None
        except Exception:report['cleanup_complete']=False
        report['sources_unchanged']=source_digest(ROOT)==before
        report['passed']=report['passed'] and report['cleanup_complete'] and report['sources_unchanged']
        (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report['passed'],'report':str(work/'report.json')},ensure_ascii=False),flush=True)
    return 0 if report['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
