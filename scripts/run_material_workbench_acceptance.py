"""Real multipart HTTP/MinIO/ClamAV journey, with synthetic signatures only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import platform
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CLASS='com.autocare.platform.RealMaterialWorkbenchJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/material-workbench-evidence.json'
OUTPUT=ROOT/'backend/target/material-workbench-acceptance.json'


CHECKS=set("five_isolated_materials materials_never_become_images five_successful_uploads four_type_purpose_downloads scope_and_query_enforced private_bucket_and_signature_enforced material_current_facts_rechecked workbench_actor_isolation eight_mutually_exclusive_buckets assignment_and_closed_tasks_rechecked pagination_has_no_duplicate_or_missing_tasks workbench_minimal_private_fields current_staff_and_binding_required current_owner_session_required no_payment_archive_or_rewards".split())


def inventory():
    rows=[]
    def a(name,op,kind,role,method,path,status):rows.append(dict(name=name,operation=op,kind=kind,role=role,method=method,path=path,status=status,passed=True))
    U='/api/input-materials/{purpose}/{vehicleId}/upload';A='/api/input-materials/{purpose}/{vehicleId}/{id}/access'
    ai='/api/input-materials/AI/1/upload';ar='/api/input-materials/ARCHIVE/1/upload';p='/api/input-materials/AI/1/1/access'
    for n,k,r,path,status in [
        ('audio_ai','normal','owner',ai,200),('audio_replay','boundary','owner',ai,200),('changed_content','boundary','owner',ai,409),('invalid_bytes','error','owner',ai,422),
        ('pdf_archive','normal','owner',ar,200),('audio_archive','normal','other','/api/input-materials/ARCHIVE/2/upload',200),('pdf_ai','normal','other','/api/input-materials/AI/2/upload',200),
        ('same_key_other_vehicle','boundary','owner','/api/input-materials/AI/3/upload',200),('unknown_kind','error','owner',ai,400),('upload_query','error','owner',ai+'?owner_id=2',400),('infected_bytes','error','owner',ai,422),
        ('upload_anonymous','unauthorized','anonymous',ai,401),('upload_merchant','unauthorized','merchant',ai,403),('upload_foreign_vehicle','unauthorized','owner','/api/input-materials/AI/2/upload',404),('upload_unknown_purpose','error','owner','/api/input-materials/UNKNOWN/1/upload',400)]:a(n,U,k,r,'POST',path,status)
    for n,k,r,path,status in [
        ('access_audio','normal','owner',p,200),('access_pdf','normal','owner','/api/input-materials/ARCHIVE/1/2/access',200),('access_other_audio','normal','other','/api/input-materials/ARCHIVE/2/3/access',200),('access_other_pdf','normal','other','/api/input-materials/AI/2/4/access',200),
        ('access_missing','boundary','owner','/api/input-materials/AI/1/999999/access',404),('access_query','error','owner',p+'?owner_id=2',400),('access_duplicate_query','error','owner',p+'?page=1&page=1',400),
        ('access_zero_id','error','owner','/api/input-materials/AI/1/0/access',400),('access_unsafe_id','error','owner','/api/input-materials/AI/1/9007199254740992/access',400),
        ('access_wrong_purpose','unauthorized','owner','/api/input-materials/ARCHIVE/1/1/access',404),('access_other_owned_vehicle','unauthorized','owner','/api/input-materials/AI/3/1/access',404),('access_foreign_owner','unauthorized','other',p,404),('access_anonymous','unauthorized','anonymous',p,401),('access_merchant','unauthorized','merchant',p,403),('access_technician','unauthorized','technician',p,403),
        ('access_scan_constraint','boundary','owner',p,200),('access_deleted','unauthorized','owner',p,404),('access_transferred','unauthorized','owner',p,404)]:a(n,A,k,r,'GET',path,status)
    M='/api/merchant/workbench';T='/api/tech/workbench'
    def bench(n,k,r,op,q='',status=200):a(n,op,k,r,'GET',op+q,status)
    for role,op in [('merchant',M),('technician',T)]:
        bench('bench_'+role,'normal',role,op);bench('empty_page_'+role,'boundary',role,op,'?page=2')
        for q in ['owner_id=2','bucket=BAD','bucket=SERVICE&bucket=SERVICE','page=0','page_size=100','page=1&page=1']:bench('bad_'+role+'_'+q,'error',role,op,'?'+q,400)
        bench('anonymous_'+role,'unauthorized','anonymous',op,status=401);bench('wrong_role_'+role,'unauthorized','owner',op,status=403)
    bench('other_shop','unauthorized','other_merchant',M);bench('unassigned_peer','unauthorized','peer',T)
    for bucket in ['SERVICE','RECEIVE','CONFIRM','WAIT_ACCEPT','REVIEW_SOURCE','VERIFY','DISPUTE']:
        for role,op in [('merchant',M),('technician',T)]:bench('bucket_'+bucket+'_'+role,'boundary',role,op,'?bucket='+bucket)
    bench('bucket_ASSIGN_merchant','boundary','merchant',M,'?bucket=ASSIGN');bench('no_assignment_tech','boundary','technician',T)
    bench('former_tech','unauthorized','technician',T);bench('new_tech','normal','peer',T);bench('closed_merchant','boundary','merchant',M);bench('closed_tech','boundary','peer',T)
    bench('page_one','boundary','merchant',M,'?page=1&page_size=20');bench('page_two','boundary','merchant',M,'?page=2&page_size=20')
    bench('unbound_tech','unauthorized','peer',T,status=401);bench('disabled_merchant','unauthorized','merchant',M,status=401)
    a('revoked_owner',A,'unauthorized','owner','GET',p,401)
    return rows


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Material/workbench journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realMaterialIsolationAndCurrentWorkbenches' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected material/workbench journey test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Material/workbench execution evidence belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Material/workbench HTTP method/path/query/role/status evidence changed')
    required=CHECKS
    if set(d.get('checks',{}))!=required or not all(v is True for v in d['checks'].values()):raise ValueError('Missing storage/scanner/expiry/database proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for c in d['cases']:coverage.setdefault(c['method']+' '+c['operation'],set()).add(c['kind'])
    if len(coverage)!=4 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete material/workbench operation coverage')
    return {'requests':len(d['cases']),'operations':len(coverage),'coverage':{k:sorted(v) for k,v in coverage.items()},'checks':d['checks'],'cleanup':d['cleanup'],'cases':d['cases']}


def source_digest():
    h=hashlib.sha256()
    paths=[]
    for directory,pattern in [('backend/src','*'),('docs/sql/migrations','*.sql'),('deploy/minio','*'),('deploy/upload-init','*')]:paths.extend(p for p in (ROOT/directory).rglob(pattern) if p.is_file())
    paths += [ROOT/'backend/pom.xml',Path(__file__)]
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()


def native_environment(environ):
    native_id=environ.get('AUTOCARE_NATIVE_MATERIAL_RUN_ID','')
    kind=environ.get('AUTOCARE_NATIVE_UPLOAD_KIND','pdf')
    if native_id and not re.fullmatch(r'[0-9a-f]{32}',native_id):raise ValueError('Invalid native run id')
    if kind not in ('pdf','image','audio') or (kind!='pdf' and not native_id):raise ValueError('Invalid native upload mode')
    return native_id,kind


def main():
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--docker',action='store_true');mode.add_argument('--check-only',action='store_true');args=parser.parse_args()
    OUTPUT.unlink(missing_ok=True);before=source_digest();start=time.monotonic();code=0;error=None;proof=None
    if not args.check_only:
        JUNIT.unlink(missing_ok=True);EVIDENCE.unlink(missing_ok=True)
        command=['mvn','-B','-Dupload.test.images.prebuilt=true','-Dtest=RealMaterialWorkbenchJourneyTest,InputMaterialValidatorTest,JdbcInputMaterialTest,ServiceWorkspaceHttpTest,JdbcServiceWorkspaceTest','test']
        if args.docker:
            native_id,native_kind=native_environment(os.environ)
            native=['-e','AUTOCARE_NATIVE_MATERIAL_RUN_ID='+native_id] if native_id else []
            if native_id:native+=['-e','AUTOCARE_NATIVE_UPLOAD_KIND='+native_kind]
            if native_id:command=[('-Dtest=RealMaterialWorkbenchJourneyTest' if value.startswith('-Dtest=') else value) for value in command]
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows','Darwin') else []
            command=['docker','run','--rm','--name',('autocare-material-native-'+native_id[:8] if native_id else 'autocare-material-workbench-'+uuid.uuid4().hex[:8]),'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,*native,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'material-workbench-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1200).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic identities; real HTTP/MySQL/MinIO/ClamAV','synthetic scan database injected only in test configuration; production freshness unchanged','synthetic task/dispatch/closure fixtures; real material writes and workbench reads; structural audio is not playback or recognition acceptance; material TTL is 60 seconds, natural expiry not measured','no production malware coverage, real identity, external cloud supplier or device acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Material workbench acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/material-workbench-journey.private.log and backend/target/material-workbench-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
