"""Real multipart HTTP/MinIO/ClamAV journey, with synthetic signatures only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CLASS='com.autocare.platform.RealPrivateUploadJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/private-upload-evidence.json'
OUTPUT=ROOT/'backend/target/private-upload-acceptance.json'


def inventory():
    cases=[]
    def add(name,op,kind,role,method,path,status):cases.append(dict(name=name,operation=op,kind=kind,role=role,method=method,path=path,status=status,passed=True))
    for i,(role,base) in enumerate([('owner','/api/file'),('merchant','/api/merchant/files'),('technician','/api/tech/files')],1):
        route=base+'/upload';wrong='merchant' if role=='owner' else 'owner'
        for suffix,kind,who,status in [('upload','normal',role,200),('replay','boundary',role,200),('changed_body','error',role,409),('infected','error',role,422),('bad_magic','error',role,400),('missing_key','boundary',role,400),('anonymous','unauthorized','anonymous',401),('wrong_role','unauthorized',wrong,403)]:add(role+'_'+suffix,route,kind,who,'POST',route,status)
        op=base+'/{id}/access';route=base+'/'+str(i)+'/access'
        for suffix,kind,who,path,status in [('access','normal',role,route,200),('missing_file','boundary',role,base+'/999999/access',404),('access_query','error',role,route+'?owner_id=1',400),('access_anonymous','unauthorized','anonymous',route,401),('access_wrong_role','unauthorized',wrong,route,403)]:add(role+'_'+suffix,op,kind,who,'GET',path,status)
    add('foreign_owner_file','/api/file/{id}/access','unauthorized','other','GET','/api/file/1/access',404)
    add('foreign_shop_file','/api/merchant/files/{id}/access','unauthorized','other_merchant','GET','/api/merchant/files/2/access',404)
    for name,kind,who,status in [('archive_create','normal','owner',200),('archive_replay','boundary','owner',200),('archive_invalid','error','owner',400),('archive_anonymous','unauthorized','anonymous',401),('archive_wrong_role','unauthorized','merchant',403)]:add(name,'/api/archive/add',kind,who,'POST','/api/archive/add',status)
    op='/api/archive/{archiveId}/files/{fileId}/access';path='/api/archive/1/files/1/access'
    for name,kind,who,route,status in [('attachment_access','normal','owner',path,200),('attachment_not_attached','boundary','owner','/api/archive/1/files/2/access',404),('attachment_query','error','owner',path+'?owner_id=1',400),('attachment_anonymous','unauthorized','anonymous',path,401),('attachment_wrong_role','unauthorized','merchant',path,403),('attachment_foreign','unauthorized','other',path,404)]:add(name,op,kind,who,'GET',route,status)
    add('fresh_signature','/api/file/{id}/access','boundary','owner','GET','/api/file/1/access',200)
    return cases


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Upload journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realMultipartStorageScannerSigningAndArchivePermissions' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected upload journey test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Upload execution evidence belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Upload HTTP method/path/query/role/status evidence changed')
    required={'anonymous_object_denied','tampered_signature_denied','expired_signature_denied','only_three_clean_files','three_successful_uploads','three_scan_rejections','one_owned_archive_attachment'}
    if set(d.get('checks',{}))!=required or not all(v is True for v in d['checks'].values()):raise ValueError('Missing storage/scanner/expiry/database proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for c in d['cases']:coverage.setdefault(c['operation'],set()).add(c['kind'])
    if len(coverage)!=8 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete upload operation coverage')
    return {'requests':len(d['cases']),'operations':len(coverage),'coverage':{k:sorted(v) for k,v in coverage.items()},'checks':d['checks'],'cleanup':d['cleanup'],'cases':d['cases']}


def source_digest():
    h=hashlib.sha256()
    paths=[]
    for directory,pattern in [('backend/src','*.java'),('docs/sql/migrations','*.sql'),('deploy/minio','*'),('deploy/upload-init','*')]:paths.extend(p for p in (ROOT/directory).rglob(pattern) if p.is_file())
    paths += [ROOT/'backend/pom.xml',Path(__file__)]
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--docker',action='store_true');mode.add_argument('--check-only',action='store_true');args=parser.parse_args()
    OUTPUT.unlink(missing_ok=True);before=source_digest();start=time.monotonic();code=0;error=None;proof=None
    if not args.check_only:
        JUNIT.unlink(missing_ok=True);EVIDENCE.unlink(missing_ok=True)
        command=['mvn','-B','-Dupload.test.images.prebuilt=true','-Dtest=RealPrivateUploadJourneyTest,UploadHttpTransportTest,JdbcServiceArchiveTest','test']
        if args.docker:
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows','Darwin') else []
            command=['docker','run','--rm','--name','autocare-upload-journey-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'upload-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1200).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic identities; real HTTP/MySQL/MinIO/ClamAV','synthetic scan database injected only in test configuration; production freshness unchanged','no production malware coverage, real identity, external cloud supplier or device acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Private upload acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/upload-journey.private.log and backend/target/private-upload-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
