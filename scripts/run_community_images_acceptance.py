"""Real community image publication and withdrawal evidence with synthetic identities."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CLASS='com.autocare.platform.RealCommunityImageJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/community-images-evidence.json'
OUTPUT=ROOT/'backend/target/community-images-acceptance.json'
TESTS='RealCommunityImageJourneyTest,PublicImageHttpTest,PublicImageRendererTest'
CHECKS=set("""two_real_scanned_uploads metadata_removed_pixels_preserved approval_failure_rolls_back
public_projection_minimal new_uuid_never_revives_old private_original_unsigned_denied
three_withdrawn_applications nine_events_and_audits only_independent_copy_persisted
stored_png_bytes_match_preview parent_approval_and_withdrawal_real bindings_from_real_responses""".split())
BINDINGS={'experience_id','preview_hash','public_id','next_public_id','archive_id'}


def inventory():
    rows=[]
    def add(name,op,kind,role,path,status=200,body=None,method='GET'):
        rows.append(dict(name=name,operation=op,kind=kind,role=role,method=method,path=path,status=status,passed=True,body=body))
    def read(name,op,role,path,boundary,invalid):
        add(name,op,'normal',role,path)
        add(name+'_boundary',op,'boundary',role,boundary,404 if '999999' in boundary else 200)
        add(name+'_invalid',op,'error',role,invalid,400)
        add(name+'_anonymous',op,'unauthorized','anonymous',path,401)
        add(name+'_wrong_role',op,'unauthorized','owner' if role=='reviewer' else 'merchant',path,403)
    def post(name,op,kind,role,path,body,status=200):add(name,op,kind,role,path,status,body,'POST')
    def write(name,op,role,path,body):
        post(name,op,'normal',role,path,body)
        post(name+'_replay',op,'boundary',role,path,body)
        post(name+'_invalid',op,'error',role,path,{},400)
        post(name+'_anonymous',op,'unauthorized','anonymous',path,body,401)
        post(name+'_wrong_role',op,'unauthorized','merchant' if role=='owner' else 'owner',path,body,403)
    sources='/api/owner/community/image-sources';own='/api/owner/community/images';pending='/api/admin/community/images';public='/api/community/images'
    experience='{{experience_id}}';pid='{{public_id}}';nxt='{{next_public_id}}'
    selection=dict(experience_id=experience,archive_id='{{archive_id}}',source_file_id=1,copy_file_id=2)
    consent=dict(selection,preview_hash='{{preview_hash}}',consent_version='R8C_IMAGE_V1',consent=True)
    approval=dict(expected_revision=1,decision='APPROVE',reason_code=None);revoke=dict(expected_revision=2)
    sq=sources+'?experience_id='+experience
    read('sources',sources,'owner',sq,sq+'&page=1000000',sq+'&user_id=2')
    add('sources_foreign',sources,'unauthorized','foreign',sq,404)
    op=own+'/preview'
    post('preview',op,'normal','owner',op,selection)
    post('preview_repeat',op,'boundary','owner',op,selection)
    post('preview_original_as_copy',op,'error','owner',op,dict(selection,copy_file_id=1),400)
    for name,role,status in [('anonymous','anonymous',401),('wrong_role','merchant',403),('foreign','foreign',404)]:post('preview_'+name,op,'unauthorized',role,op,selection,status)
    write('submit',own,'owner',own,consent)
    post('submit_no_consent',own,'error','owner',own,dict(consent,consent=False),400)
    post('submit_changed_hash',own,'error','owner',own,dict(consent,preview_hash='0'*64),409)
    read('own',own,'owner',own,own+'?page=1000000',own+'?page=0')
    op=own+'/{id}/preview'
    read('owner_preview',op,'owner',own+'/1/preview?format=base64',own+'/999999/preview?format=base64',own+'/1/preview?format=bad')
    add('owner_png',op,'normal','owner',own+'/1/preview')
    add('owner_preview_foreign',op,'unauthorized','foreign',own+'/1/preview?format=base64',404)
    read('pending',pending,'reviewer',pending,pending+'?page=1000000',pending+'?page=0')
    add('pending_no_permission',pending,'unauthorized','plain',pending,403)
    op=pending+'/{id}/preview'
    read('review_preview',op,'reviewer',pending+'/1/preview?format=base64',pending+'/999999/preview?format=base64',pending+'/1/preview?format=bad')
    add('review_png',op,'normal','reviewer',pending+'/1/preview')
    add('review_preview_no_permission',op,'unauthorized','plain',pending+'/1/preview?format=base64',403)
    listing=public+'?vehicle_id=2&experience_id='+experience
    add('unapproved_hidden',public,'boundary','viewer',listing)
    op=pending+'/{id}/moderate';moderate=pending+'/1/moderate'
    post('approval_audit_failure',op,'error','reviewer',moderate,approval,503)
    write('approve',op,'reviewer',moderate,approval)
    post('approve_no_permission',op,'unauthorized','plain',moderate,approval,403)
    add('approved_binding',own,'normal','owner',own)
    read('public_list',public,'viewer',listing,public+'?vehicle_id=999999&experience_id='+experience,listing+'&user_id=2')
    op=public+'/{publicId}';raw=public+'/'+pid+'?vehicle_id=2';encoded=raw+'&format=base64'
    read('public_bytes',op,'viewer',encoded,public+'/00000000-0000-4000-8000-000000999999?vehicle_id=2&format=base64',raw+'&format=bad')
    add('public_png',op,'normal','viewer',raw)
    add('public_wrong_model',op,'unauthorized','viewer',public+'/'+pid+'?vehicle_id=4&format=base64',404)
    add('public_foreign_vehicle',op,'unauthorized','owner',encoded,404)
    withdraw=own+'/{id}/withdraw'
    write('withdraw',withdraw,'owner',own+'/1/withdraw',revoke)
    add('old_public_hidden',op,'boundary','viewer',encoded,404)
    post('old_approval_conflict',pending+'/{id}/moderate','boundary','reviewer',moderate,approval,409)
    post('old_submission_receipt',own,'boundary','owner',own,consent)
    post('submit_second',own,'normal','owner',own,consent)
    post('reject_second',pending+'/{id}/moderate','normal','reviewer',pending+'/2/moderate',dict(expected_revision=1,decision='REJECT',reason_code='PRIVACY'))
    post('withdraw_second',withdraw,'normal','owner',own+'/2/withdraw',revoke)
    post('submit_third',own,'normal','owner',own,consent)
    post('approve_third',pending+'/{id}/moderate','normal','reviewer',pending+'/3/moderate',approval)
    add('new_public_binding',own,'normal','owner',own)
    add('old_public_still_hidden',op,'boundary','viewer',encoded,404)
    fresh=public+'/'+nxt+'?vehicle_id=2&format=base64'
    add('new_public_visible',op,'normal','viewer',fresh)
    add('parent_withdrawal_hides_image',op,'unauthorized','viewer',fresh,404)
    post('withdraw_after_parent_loss',withdraw,'normal','owner',own+'/3/withdraw',revoke)
    add('owner_history_after_withdrawal',own+'/{id}/preview','boundary','owner',own+'/3/preview?format=base64')
    return rows


def resolve(value,bindings):
    if isinstance(value,dict):return {k:resolve(v,bindings) for k,v in value.items()}
    if isinstance(value,list):return [resolve(v,bindings) for v in value]
    if isinstance(value,str):
        for k,v in bindings.items():
            marker='{{'+k+'}}'
            if value==marker:return v
            value=value.replace(marker,str(v))
    return value


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Community image journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realUploadConsentModerationAndWithdrawal' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected community image test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Evidence belongs to different source content')
    b=d.get('bindings',{})
    if set(b)!=BINDINGS:raise ValueError('Missing response bindings')
    for k in ('experience_id','public_id','next_public_id'):
        if not isinstance(b[k],str) or str(uuid.UUID(b[k]))!=b[k]:raise ValueError('Invalid UUID binding')
    if b['public_id']==b['next_public_id'] or type(b['archive_id']) is not int or b['archive_id']<=0 or not isinstance(b['preview_hash'],str) or not re.fullmatch('[0-9a-f]{64}',b['preview_hash']):raise ValueError('Invalid image binding')
    # Serialize to keep JSON booleans distinct from Python integers.
    if d.get('passed') is not True or json.dumps(d.get('cases'),sort_keys=True)!=json.dumps(resolve(inventory(),b),sort_keys=True):raise ValueError('HTTP method/path/role/body/status evidence changed')
    if set(d.get('checks',{}))!=CHECKS or not all(v is True for v in d['checks'].values()):raise ValueError('Missing storage/privacy/withdrawal proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for case in d['cases']:coverage.setdefault(case['method']+' '+case['operation'],set()).add(case['kind'])
    if len(coverage)!=11 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete community image coverage')
    return dict(requests=len(d['cases']),operations=len(coverage),coverage={k:sorted(v) for k,v in coverage.items()},checks=d['checks'],cleanup=d['cleanup'],cases=inventory(),actual_requests_bound_to_responses=True)


def source_digest():
    h=hashlib.sha256();paths=[]
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
        command=['mvn','-B','-Dupload.test.images.prebuilt=true','-Dtest='+TESTS,'test']
        if args.docker:
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows','Darwin') else []
            command=['docker','run','--rm','--name','autocare-community-images-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'community-images-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1800).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic identities, prior orders/payments/reports; no real payment acceptance','real MySQL, private MinIO, scanner engine, HTTP consent/moderation/withdrawal and immutable PNG bytes','synthetic scan database; metadata removal preserves supplied pixels, no automatic visual redaction or production malware acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Community image acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/community-images-journey.private.log and backend/target/community-images-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
