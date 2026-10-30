"""Recognition current HTTP evidence; upstream/storage/scanner are explicit fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
SPECS={'vin':('VinHttpJourneyTest','vin_four_kind_http_evidence','/api/vehicle/vin-decode'),
       'local-vin':('LocalVinHttpJourneyTest','local_vin_four_kind_http_evidence','/api/vehicle/vin-decode'),
       'license':('LicenseHttpJourneyTest','license_four_kind_http_evidence','/api/vehicle/license-recognition'),
       'asr':('AsrHttpJourneyTest','asr_four_kind_http_evidence','/api/recognition/asr/jobs'),
       'document':('DocumentHttpJourneyTest','document_four_kind_http_evidence','/api/recognition/documents/jobs'),
       'mileage':('PickupMileageHttpJourneyTest','mileage_four_kind_http_evidence','/api/vehicle/mileage-recognition'),
       'pickup':('PickupMileageHttpJourneyTest','pickup_four_kind_http_evidence','/api/check/pickup/mileage-recognition')}
CHECKS={'no_vehicle_write','no_archive_write','candidate_requires_confirmation'}
LOCAL_VIN_CHECKS={'synthetic_only','no_vehicle_write','tamper_fails_closed'}
MILEAGE_CHECKS={'no_vehicle_write','no_archive_write','mileage_unchanged','candidate_requires_confirmation','rejected_requests_no_model_calls'}
JOB_CHECKS={'no_vehicle_write','no_archive_write','job_binding_verified','cancelled_result_withheld','idempotent_replay_verified'}

def source_digest():
    paths=[p for directory in ('backend/src','docs/sql/migrations') for p in (ROOT/directory).rglob('*') if p.is_file()]
    paths += [ROOT/'backend/pom.xml',Path(__file__)]
    paths += [ROOT/'docs/testing/fixtures/vin-local-synthetic.json']
    h=hashlib.sha256()
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()

def inventory(name,bindings=None):
    path=SPECS[name][2]
    if name=='local-vin':
        rows=[('local_candidate','normal','owner',path,200),('disabled_catalog','boundary','owner',path,200),
              ('wrong_consent_source','error','owner',path,400),('query_refused','error','owner',path+'?unexpected=1',400),
              ('anonymous','unauthorized','anonymous',path,401),('wrong_role','unauthorized','merchant',path,403),('tampered_source','error','owner',path,503)]
        return [dict(name=n,kind=k,role=r,method='POST',operation=path,path=p,status=status,passed=True) for n,k,r,p,status in rows]
    if name in ('mileage','pickup'):
        role='owner' if name=='mileage' else 'merchant'
        rows=[('candidate','normal',role,path,200),('repeat','boundary',role,path,200),('no_consent','error',role,path,400),('query','error',role,path+'?unexpected=1',400),('anonymous','unauthorized','anonymous',path,401),('wrong_role','unauthorized','merchant' if name=='mileage' else 'owner',path,403),('foreign_owner' if name=='mileage' else 'foreign_shop','unauthorized','foreign' if name=='mileage' else 'other_merchant',path,404),('foreign_file','unauthorized',role,path,404)]
        if name=='pickup':rows.append(('order_state','boundary',role,path,409))
        return [dict(name=n,kind=k,role=r,method='POST',operation=path,path=p,status=status,passed=True) for n,k,r,p,status in rows]
    if name in ('asr','document'):
        job='{{job_id}}' if bindings is None else str(bindings['job_id'])
        read=path+'/'+job;cancel=read+'/cancel';read_op=path+'/{id}';cancel_op=read_op+'/cancel'
        rows=[('create','normal','owner','POST',path,path,200),('create_repeat','boundary','owner','POST',path,path,200),
              ('create_no_consent','error','owner','POST',path,path,400),('create_query','error','owner','POST',path,path+'?unexpected=1',400),
              ('create_anonymous','unauthorized','anonymous','POST',path,path,401),('create_role','unauthorized','merchant','POST',path,path,403),('create_foreign','unauthorized','foreign','POST',path,path,404),
              ('read','normal','owner','GET',read_op,read,200),('read_missing','boundary','owner','GET',read_op,path+'/999999999',404),('read_query','error','owner','GET',read_op,read+'?unexpected=1',400),
              ('read_anonymous','unauthorized','anonymous','GET',read_op,read,401),('read_role','unauthorized','merchant','GET',read_op,read,403),('read_foreign','unauthorized','foreign','GET',read_op,read,404),
              ('cancel_body','error','owner','POST',cancel_op,cancel,400),('cancel_query','error','owner','POST',cancel_op,cancel+'?unexpected=1',400),
              ('cancel_anonymous','unauthorized','anonymous','POST',cancel_op,cancel,401),('cancel_role','unauthorized','merchant','POST',cancel_op,cancel,403),('cancel_foreign','unauthorized','foreign','POST',cancel_op,cancel,404),
              ('cancel','normal','owner','POST',cancel_op,cancel,200),('cancel_repeat','boundary','owner','POST',cancel_op,cancel,200)]
        return [dict(name=n,kind=k,role=r,method=m,operation=op,path=p,status=status,passed=True) for n,k,r,m,op,p,status in rows]
    rows=[('candidate','normal','owner',path,200),('repeat','boundary','owner',path,200),
          ('no_consent','error','owner',path,400),('query','error','owner',path+'?unexpected=1',400),
          ('anonymous','unauthorized','anonymous',path,401),('wrong_role','unauthorized','merchant',path,403)]
    if name=='license':rows.append(('foreign_file','unauthorized','foreign',path,404))
    return [dict(name=n,kind=k,role=r,method='POST',operation=path,path=p,status=s,passed=True) for n,k,r,p,s in rows]

def audit(name,junit,evidence):
    cls,method,path=SPECS[name];suite=ET.parse(junit).getroot();cases=suite.findall('testcase')
    if suite.get('name')!='com.autocare.platform.order.'+cls or not cases or int(suite.get('tests','-1'))!=len(cases) or any(int(suite.get(k,'-1'))!=0 for k in ('failures','errors','skipped')):raise ValueError('Missing or nonpassing JUnit')
    if sum(c.get('name')==method and c.get('classname')==suite.get('name') for c in cases)!=1 or any(c.find(k) is not None for c in cases for k in ('error','failure','skipped')):raise ValueError('Required execution missing')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('version')!=1 or d.get('passed') is not True or d.get('source_sha256')!=source_digest() or d.get('provider_fixture') is not True:raise ValueError('Invalid source or fixture boundary')
    bindings=d.get('bindings',{})
    if name in ('asr','document'):
        if set(bindings)!= {'job_id'} or type(bindings['job_id']) is not int or not 0<bindings['job_id']<=9007199254740991:raise ValueError('Invalid observed job binding')
    elif name in ('mileage','pickup'):
        keys={'vehicle_id','file_id'}|({'order_id'} if name=='pickup' else set())
        if set(bindings)!=keys or any(type(v) is not int or not 0<v<=9007199254740991 for v in bindings.values()):raise ValueError('Invalid mileage scope binding')
    elif bindings:raise ValueError('Unexpected binding')
    actual=d.get('cases');expected=inventory(name,bindings)
    if not isinstance(actual,list) or len(actual)!=len(expected):raise ValueError('Incomplete observed requests')
    for row,want in zip(actual,expected):
        if set(row)!=set(want)|{'request_sha256','response_sha256'} or any(row.get(k)!=v for k,v in want.items()) or any(not re.fullmatch('[a-f0-9]{64}',str(row.get(k,''))) for k in ('request_sha256','response_sha256')):raise ValueError('Observed HTTP differs from reviewed inventory')
    required=JOB_CHECKS if name in ('asr','document') else (MILEAGE_CHECKS|({'no_pickup_write','order_state_preserved'} if name=='pickup' else set()) if name in ('mileage','pickup') else CHECKS)
    if name=='local-vin':required=LOCAL_VIN_CHECKS
    if set(d.get('checks',{}))!=required or any(v is not True for v in d['checks'].values()):raise ValueError('SQL or confirmation checks missing')
    if not re.fullmatch('[a-f0-9]{64}',str(d.get('mysql_container_id',''))):raise ValueError('Missing owned database identity')
    coverage={}
    for row in actual:coverage.setdefault(row['method']+' '+row['operation'],set()).add(row['kind'])
    if any(v!={'normal','boundary','error','unauthorized'} for v in coverage.values()):raise ValueError('Incomplete operation categories')
    return dict(passed=True,test_class=cls,requests=len(actual),tests=len(cases),coverage={k:sorted(v) for k,v in coverage.items()},checks=d['checks'],bindings=bindings,mysql_container_id=d['mysql_container_id'],source_sha256=d['source_sha256'],raw_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');parser.add_argument('--docker',action='store_true');args=parser.parse_args()
    work=ROOT/'.cache'/('recognition-http-'+uuid.uuid4().hex[:12]);work.mkdir(parents=True)
    before=source_digest();started=time.monotonic();report=dict(passed=False,source_sha256=before,full_api_acceptance_passed=False,production_acceptance_passed=False,execution_mode='AUDIT_EXISTING' if args.check_only else 'EXECUTED',suites=[])
    try:
        if not args.check_only:
            for name,(cls,_,_) in SPECS.items():
                for p in (ROOT/'backend/target'/f'{name}-http-evidence.json',ROOT/'backend/target/surefire-reports'/f'TEST-com.autocare.platform.order.{cls}.xml'):p.unlink(missing_ok=True)
            command=['mvn','-B','-Dtest='+','.join(dict.fromkeys(spec[0] for spec in SPECS.values())),'test']
            if args.docker:
                command=['docker','run','--rm','--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock','-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal','-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
            with (work/'maven.private.log').open('wb') as log:
                code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT).returncode
            report['maven_exit_code']=code
            if code:raise ValueError('Maven did not complete successfully')
        for name,(cls,_,_) in SPECS.items():
            proof=audit(name,ROOT/'backend/target/surefire-reports'/f'TEST-com.autocare.platform.order.{cls}.xml',ROOT/'backend/target'/f'{name}-http-evidence.json');report['suites'].append(dict(name=name,**proof))
        if not args.check_only:
            for row in report['suites']:
                check=subprocess.run(['docker','ps','-aq','--no-trunc','--filter','id='+row['mysql_container_id']],capture_output=True)
                if check.returncode or check.stdout.strip():raise ValueError('Owned database cleanup not verified')
            report['owned_databases_removed']=True
            report['owned_database_count']=len({v['mysql_container_id'] for v in report['suites']})
        report['sources_unchanged']=before==source_digest()
        if not report['sources_unchanged']:raise ValueError('Sources changed')
        report['selected_java_executions']=sum({v['test_class']:v['tests'] for v in report['suites']}.values())
        report['passed']=True
    except Exception as exc:report['reason']=type(exc).__name__; (work/'failure.private.txt').write_text(str(exc),encoding='utf-8')
    report['duration_seconds']=round(time.monotonic()-started,3)
    (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Recognition HTTP '+('PASS' if report['passed'] else 'FAIL')+'; '+str(work),flush=True)
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
