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
CLASS='com.autocare.platform.RealServiceArchiveAccessJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/service-archive-access-evidence.json'
OUTPUT=ROOT/'backend/target/service-archive-access-acceptance.json'


CHECKS=set("no_default_grants only_selected_archive_shared audit_failure_rolls_back_grant old_receipt_never_revives_grant source_hash_changes_with_fields changed_source_hidden updated_safe_fields_visible current_assignment_required vehicle_owner_rechecked scan_status_rechecked closed_task_denies_new_access issued_bearer_url_has_bounded_lease old_url_expires_naturally one_final_revoked_grant exact_five_grant_audits one_scanned_upload_two_archives grant_audit_contains_no_source_text no_payment_or_rewards".split())


def inventory():
    rows=[]
    def a(name,op,kind,role,method,path,status):rows.append(dict(name=name,operation=op,kind=kind,role=role,method=method,path=path,status=status,passed=True))
    def read(name,op,role,path,missing):
        for suffix,kind,who,route,status in [('', 'normal',role,path,200),('_missing','boundary',role,missing,404),('_query','error',role,path+'?owner_id=2',400),('_anonymous','unauthorized','anonymous',path,401),('_wrong_role','unauthorized','merchant' if role=='owner' else 'owner',path,403)]:a(name+suffix,op,kind,who,'GET',route,status)
    def write(name,op,role,method,path):
        for suffix,kind,who,status in [('', 'normal',role,200),('_replay','boundary',role,200),('_invalid','error',role,400),('_anonymous','unauthorized','anonymous',401),('_wrong_role','unauthorized','merchant' if role=='owner' else 'owner',403)]:a(name+suffix,op,kind,who,method,path,status)
    upload='/api/file/upload';write('owner_upload',upload,'owner','POST',upload)
    # The upload transport names its invalid-byte case exactly like the shared write inventory.
    write('archive_create','/api/archive/add','owner','POST','/api/archive/add');a('archive_unselected','/api/archive/add','boundary','owner','POST','/api/archive/add',200)
    C='/api/order/{id}/archive-grants';G=C+'/{archive}';P=G+'/files/{file}/preview';M='/api/merchant/orders/{id}/service-context';T='/api/tech/orders/{id}/service-context';MF=M+'/archives/{archive}/files/{file}/access';TF=T+'/archives/{archive}/files/{file}/access'
    c='/api/order/1/archive-grants';g=c+'/1';p=g+'/files/1/preview';m='/api/merchant/orders/1/service-context';t='/api/tech/orders/1/service-context';mf=m+'/archives/1/files/1/access';tf=t+'/archives/1/files/1/access'
    read('candidates',C,'owner',c,'/api/order/999999/archive-grants');read('preview',P,'owner',p,g+'/files/999999/preview');read('merchant_context',M,'merchant',m,'/api/merchant/orders/999999/service-context');read('tech_context',T,'technician',t,'/api/tech/orders/999999/service-context')
    for n,op,who,path in [('ungranted_merchant_file',MF,'merchant',mf),('ungranted_tech_file',TF,'technician',tf),('foreign_candidates',C,'other',c),('foreign_preview',P,'other',p),('foreign_shop_context',M,'other_merchant',m),('unassigned_peer_context',T,'peer',t)]:a(n,op,'unauthorized',who,'GET',path,404)
    write('grant_activate',G,'owner','PUT',g)
    for n,kind,who,path,status in [('grant_changed_intent','error','owner',g,400),('grant_foreign_owner','unauthorized','other',g,404),('grant_stale_revision','boundary','owner',g,409),('grant_write_query','error','owner',g+'?owner_id=2',400)]:a(n,G,kind,who,'PUT',path,status)
    read('merchant_file',MF,'merchant',mf,m+'/archives/1/files/999999/access');read('tech_file',TF,'technician',tf,t+'/archives/1/files/999999/access')
    a('shared_context',M,'normal','merchant','GET',m,200);a('unselected_archive_file',MF,'unauthorized','merchant','GET',m+'/archives/2/files/1/access',404);a('grant_audit_failure',G,'error','owner','PUT',g,503)
    write('grant_revoke',G,'owner','PUT',g);a('old_receipt_after_revoke',G,'boundary','owner','PUT',g,200)
    a('revoked_merchant_file',MF,'unauthorized','merchant','GET',mf,404);a('revoked_tech_file',TF,'unauthorized','technician','GET',tf,404)
    write('grant_reactivate',G,'owner','PUT',g);write('structured_save','/api/archive/{id}/structured','owner','PUT','/api/archive/1/structured')
    a('source_changed_candidates',C,'boundary','owner','GET',c,200);a('source_unavailable_context',M,'boundary','merchant','GET',m,200);a('changed_source_file',MF,'unauthorized','merchant','GET',mf,404);a('old_source_reconfirm',G,'boundary','owner','PUT',g,409)
    write('grant_reconfirm',G,'owner','PUT',g);a('updated_context',T,'normal','technician','GET',t,200)
    for n,op,kind,who,path,status in [('former_tech_context',T,'unauthorized','technician',t,404),('former_tech_file',TF,'unauthorized','technician',tf,404),('peer_not_accepted',T,'unauthorized','peer',t,404),('peer_context',T,'normal','peer',t,200),('peer_file',TF,'normal','peer',tf,200),('transferred_vehicle_context',M,'unauthorized','merchant',m,404),('infected_file_denied',MF,'unauthorized','merchant',mf,404),('lease_before_close',TF,'boundary','peer',tf,200),('closed_merchant_context',M,'unauthorized','merchant',m,404),('closed_peer_context',T,'unauthorized','peer',t,404),('closed_peer_file',TF,'unauthorized','peer',tf,404)]:a(n,op,kind,who,'GET',path,status)
    a('closed_old_grant_receipt',G,'unauthorized','owner','PUT',g,404);a('owner_history_preview',P,'boundary','owner','GET',p,200);write('grant_final_revoke',G,'owner','PUT',g)
    return rows


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Upload journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realServiceConsentContextAndSignedAttachments' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected upload journey test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Upload execution evidence belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Upload HTTP method/path/query/role/status evidence changed')
    required=CHECKS
    if set(d.get('checks',{}))!=required or not all(v is True for v in d['checks'].values()):raise ValueError('Missing storage/scanner/expiry/database proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for c in d['cases']:coverage.setdefault(c['method']+' '+c['operation'],set()).add(c['kind'])
    if len(coverage)!=10 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete upload operation coverage')
    return {'requests':len(d['cases']),'operations':len(coverage),'coverage':{k:sorted(v) for k,v in coverage.items()},'checks':d['checks'],'cleanup':d['cleanup'],'cases':d['cases']}


def source_digest():
    h=hashlib.sha256()
    paths=[]
    for directory,pattern in [('backend/src','*'),('docs/sql/migrations','*.sql'),('deploy/minio','*'),('deploy/upload-init','*')]:paths.extend(p for p in (ROOT/directory).rglob(pattern) if p.is_file())
    paths += [ROOT/'backend/pom.xml',Path(__file__)]
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group()
    mode.add_argument('--docker',action='store_true');mode.add_argument('--check-only',action='store_true');args=parser.parse_args()
    OUTPUT.unlink(missing_ok=True);before=source_digest();start=time.monotonic();code=0;error=None;proof=None
    if not args.check_only:
        JUNIT.unlink(missing_ok=True);EVIDENCE.unlink(missing_ok=True)
        command=['mvn','-B','-Dupload.test.images.prebuilt=true','-Dtest=RealServiceArchiveAccessJourneyTest,ServiceWorkspaceHttpTest,JdbcServiceWorkspaceTest','test']
        if args.docker:
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows','Darwin') else []
            command=['docker','run','--rm','--name','autocare-service-archive-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'service-archive-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1200).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic identities; real HTTP/MySQL/MinIO/ClamAV','synthetic scan database injected only in test configuration; production freshness unchanged','synthetic task/dispatch/closure fixtures; HTTP consent/archive/source writes; issued bearer URLs live only until short TTL','no production malware coverage, real identity, external cloud supplier or device acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Service archive access acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/service-archive-journey.private.log and backend/target/service-archive-access-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
