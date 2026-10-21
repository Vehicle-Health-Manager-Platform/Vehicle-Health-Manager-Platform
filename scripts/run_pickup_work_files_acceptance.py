"""Real pickup and construction attachment access journey, with synthetic identities only."""
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
CLASS='com.autocare.platform.RealPickupWorkFileAccessJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/pickup-work-files-evidence.json'
OUTPUT=ROOT/'backend/target/pickup-work-files-acceptance.json'
TESTS='RealPickupWorkFileAccessJourneyTest,PickupHttpTest,ServiceWorkHttpTest,PrivateFileAccessTest'

CHECKS=set("""nineteen_clean_uploads pickup_and_work_links_exact files_owned_by_this_shop_only
unsigned_object_denied tampered_signature_denied expired_signature_denied signature_lease_bounded
two_orders_only orders_keep_expected_states""".split())


def inventory():
    rows=[]
    def add(name,operation,kind,role,path,status):rows.append(dict(name=name,operation=operation,kind=kind,role=role,method='GET',path=path,status=status,passed=True))
    pickup='/api/check/pickup/{order}/files/{file}/access'
    for name,kind,role,path,status in [
        ('pickup_owner_photo','normal','owner','/api/check/pickup/1/files/1/access',200),
        ('pickup_merchant_photo','normal','merchant','/api/check/pickup/1/files/2/access',200),
        ('pickup_own_second_order','normal','owner','/api/check/pickup/2/files/13/access',200),
        ('pickup_cross_order','boundary','owner','/api/check/pickup/2/files/1/access',404),
        ('pickup_unattached_work_file','boundary','owner','/api/check/pickup/1/files/8/access',404),
        ('pickup_query','error','owner','/api/check/pickup/1/files/1/access?owner_id=1',400),
        ('pickup_zero_file','error','owner','/api/check/pickup/1/files/0/access',400),
        ('pickup_anonymous','unauthorized','anonymous','/api/check/pickup/1/files/1/access',401),
        ('pickup_wrong_role','unauthorized','technician','/api/check/pickup/1/files/1/access',403),
        ('pickup_other_owner','unauthorized','other','/api/check/pickup/1/files/1/access',404),
        ('pickup_other_shop','unauthorized','other_merchant','/api/check/pickup/1/files/1/access',404)]:add(name,pickup,kind,role,path,status)
    merchant='/api/merchant/orders/{id}/work/files/{file}/access'
    for name,kind,role,path,status in [
        ('merchant_work_protection','normal','merchant','/api/merchant/orders/1/work/files/8/access',200),
        ('merchant_work_process','normal','merchant','/api/merchant/orders/1/work/files/9/access',200),
        ('merchant_work_pickup_file','boundary','merchant','/api/merchant/orders/1/work/files/1/access',404),
        ('merchant_work_missing_order','boundary','merchant','/api/merchant/orders/999999/work/files/8/access',404),
        ('merchant_work_query','error','merchant','/api/merchant/orders/1/work/files/8/access?page=1',400),
        ('merchant_work_zero_order','error','merchant','/api/merchant/orders/0/work/files/8/access',400),
        ('merchant_work_anonymous','unauthorized','anonymous','/api/merchant/orders/1/work/files/8/access',401),
        ('merchant_work_wrong_role','unauthorized','owner','/api/merchant/orders/1/work/files/8/access',403),
        ('merchant_work_other_shop','unauthorized','other_merchant','/api/merchant/orders/1/work/files/8/access',404)]:add(name,merchant,kind,role,path,status)
    tech='/api/tech/orders/{id}/work/files/{file}/access'
    for name,kind,role,path,status in [
        ('tech_work_protection','normal','technician','/api/tech/orders/1/work/files/8/access',200),
        ('tech_work_finish','normal','technician','/api/tech/orders/1/work/files/11/access',200),
        ('tech_work_pickup_file','boundary','technician','/api/tech/orders/1/work/files/1/access',404),
        ('tech_work_missing_order','boundary','technician','/api/tech/orders/999999/work/files/8/access',404),
        ('tech_work_query','error','technician','/api/tech/orders/1/work/files/8/access?page=1',400),
        ('tech_work_zero_order','error','technician','/api/tech/orders/0/work/files/8/access',400),
        ('tech_work_anonymous','unauthorized','anonymous','/api/tech/orders/1/work/files/8/access',401),
        ('tech_work_wrong_role','unauthorized','owner','/api/tech/orders/1/work/files/8/access',403),
        ('tech_work_peer','unauthorized','peer','/api/tech/orders/1/work/files/8/access',404)]:add(name,tech,kind,role,path,status)
    owner='/api/order/{id}/work/files/{file}/access'
    for name,kind,role,path,status in [
        ('owner_work_protection','normal','owner','/api/order/1/work/files/8/access',200),
        ('owner_work_process','normal','owner','/api/order/1/work/files/9/access',200),
        ('owner_work_signature','normal','owner','/api/order/1/work/files/12/access',200),
        ('owner_work_cross_order','boundary','owner','/api/order/1/work/files/13/access',404),
        ('owner_work_missing_order','boundary','owner','/api/order/999999/work/files/8/access',404),
        ('owner_work_query','error','owner','/api/order/1/work/files/8/access?owner_id=1',400),
        ('owner_work_zero_file','error','owner','/api/order/1/work/files/0/access',400),
        ('owner_work_anonymous','unauthorized','anonymous','/api/order/1/work/files/8/access',401),
        ('owner_work_wrong_role','unauthorized','merchant','/api/order/1/work/files/8/access',403),
        ('owner_work_other_owner','unauthorized','other','/api/order/1/work/files/8/access',404)]:add(name,owner,kind,role,path,status)
    return rows


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Pickup/work attachment journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realPickupAndWorkAttachmentPermissionsSignaturesAndOrderIsolation' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected pickup/work attachment journey test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Pickup/work attachment evidence belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Pickup/work attachment HTTP method/path/role/status evidence changed')
    if set(d.get('checks',{}))!=CHECKS or not all(v is True for v in d['checks'].values()):raise ValueError('Missing storage/signature/isolation/database proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for case in d['cases']:coverage.setdefault(case['method']+' '+case['operation'],set()).add(case['kind'])
    if len(coverage)!=4 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete attachment operation coverage')
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
        command=['mvn','-B','-Dupload.test.images.prebuilt=true','-Dtest='+TESTS,'test']
        if args.docker:
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ('Windows','Darwin') else []
            command=['docker','run','--rm','--name','autocare-pickup-files-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'pickup-work-files-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1800).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic identities, order and payment rows; real HTTP uploads, pickup, dispatch acceptance, protection, report signing and signed object downloads','synthetic scan database injected only in test configuration; production freshness unchanged','signature lease is 5 seconds and one natural expiry was measured; no production malware coverage, real identity, external cloud supplier or device acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Pickup/work private file acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/pickup-work-files-journey.private.log and backend/target/pickup-work-files-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
