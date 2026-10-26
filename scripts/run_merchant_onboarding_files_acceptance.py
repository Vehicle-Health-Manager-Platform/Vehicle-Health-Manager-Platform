"""Real operator access to merchant onboarding qualification attachments, synthetic identities only."""
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
CLASS='com.autocare.platform.RealMerchantOnboardingFileAccessJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/merchant-onboarding-files-evidence.json'
OUTPUT=ROOT/'backend/target/merchant-onboarding-files-acceptance.json'
TESTS='RealMerchantOnboardingFileAccessJourneyTest,MerchantOnboardingHttpTest,MerchantOnboardingMySqlTest'

CHECKS=set("""three_clean_uploads files_owned_by_applicants_only applications_exact_two
qualification_snapshots_exact one_application_per_applicant no_merchant_activation
cross_application_files_never_linked unsigned_object_denied tampered_signature_denied
expired_signature_denied signature_lease_bounded""".split())


def inventory():
    rows=[]
    operation='/api/admin/merchant-applications/{id}/files/{file}/access'
    for name,kind,role,path,status in [
        ('onboarding_qualification_first','normal','operator','/api/admin/merchant-applications/1/files/1/access',200),
        ('onboarding_qualification_second','normal','operator','/api/admin/merchant-applications/1/files/2/access',200),
        ('onboarding_other_application','normal','operator','/api/admin/merchant-applications/2/files/3/access',200),
        ('onboarding_cross_application_file','boundary','operator','/api/admin/merchant-applications/1/files/3/access',404),
        ('onboarding_missing_application','boundary','operator','/api/admin/merchant-applications/999999/files/1/access',404),
        ('onboarding_query','error','operator','/api/admin/merchant-applications/1/files/1/access?page=1',400),
        ('onboarding_zero_application','error','operator','/api/admin/merchant-applications/0/files/1/access',400),
        ('onboarding_zero_file','error','operator','/api/admin/merchant-applications/1/files/0/access',400),
        ('onboarding_anonymous','unauthorized','anonymous','/api/admin/merchant-applications/1/files/1/access',401),
        ('onboarding_review_permission_only','unauthorized','review_operator','/api/admin/merchant-applications/1/files/1/access',403),
        ('onboarding_owner_denied','unauthorized','owner','/api/admin/merchant-applications/1/files/1/access',403),
        ('onboarding_merchant_denied','unauthorized','merchant','/api/admin/merchant-applications/1/files/1/access',403)]:
        rows.append(dict(name=name,operation=operation,kind=kind,role=role,method='GET',path=path,status=status,passed=True))
    return rows


def audit(junit,evidence):
    suite=ET.parse(junit).getroot()
    if suite.get('name')!=CLASS or {k:int(suite.get(k,'-1')) for k in ('tests','failures','errors','skipped')}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Onboarding attachment journey missing, failed or skipped')
    tests=suite.findall('testcase')
    if len(tests)!=1 or tests[0].get('name')!='realMerchantOnboardingQualificationAttachmentPermissionsAndSignatures' or tests[0].get('classname')!=CLASS or any(tests[0].find(k) is not None for k in ('failure','error','skipped')):raise ValueError('Unexpected onboarding attachment journey test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Onboarding attachment evidence belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Onboarding attachment HTTP method/path/role/status evidence changed')
    if set(d.get('checks',{}))!=CHECKS or not all(v is True for v in d['checks'].values()):raise ValueError('Missing upload/snapshot/permission/signature proof')
    if set(d.get('cleanup',{}))!={'initializer','clamav','minio','mysql','network'} or not all(v is True for v in d['cleanup'].values()):raise ValueError('Owned upload resources were not cleaned')
    coverage={}
    for case in d['cases']:coverage.setdefault(case['method']+' '+case['operation'],set()).add(case['kind'])
    if len(coverage)!=1 or any(k!={'normal','boundary','error','unauthorized'} for k in coverage.values()):raise ValueError('Incomplete attachment operation coverage')
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
            command=['docker','run','--rm','--name','autocare-onboarding-files-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        work=ROOT/'.cache';work.mkdir(exist_ok=True)
        try:
            with (work/'merchant-onboarding-files-journey.private.log').open('wb') as log:
                for name,directory in [('autocare-upload-minio-ci','minio'),('autocare-upload-init-ci','upload-init')]:
                    build=subprocess.run(['docker','build','--tag',name,str(ROOT/'deploy'/directory)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
                    if build.returncode:raise ValueError('Private upload test image build failed')
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1800).returncode
        except (OSError,subprocess.TimeoutExpired,ValueError):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    result={'version':1,'passed':passed,'full_api_acceptance_passed':False,'finished_at':datetime.now(timezone.utc).isoformat(),'duration_seconds':round(time.monotonic()-start,3),'source_sha256':before,'sources_unchanged':unchanged,'maven_exit_code':None if args.check_only else code,'execution_mode':'ci-report-check' if args.check_only else 'local-execution','proof':proof,'error':error,'boundaries':['synthetic user, shop, operator and application rows; real HTTP uploads, onboarding submissions, permission checks and signed object downloads','synthetic scan database injected only in test configuration; production freshness unchanged','signature lease is 5 seconds and one natural expiry was measured; no production malware coverage, real operator identity, review decision, quota or external cloud supplier acceptance']}
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Merchant onboarding attachment acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/merchant-onboarding-files-journey.private.log and backend/target/merchant-onboarding-files-acceptance.json'))
    return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
