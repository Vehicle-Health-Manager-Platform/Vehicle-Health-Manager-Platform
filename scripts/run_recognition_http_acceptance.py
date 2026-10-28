"""VIN/license current HTTP evidence; upstream/storage/scanner are explicit fixtures."""
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
       'license':('LicenseHttpJourneyTest','license_four_kind_http_evidence','/api/vehicle/license-recognition')}
CHECKS={'no_vehicle_write','no_archive_write','candidate_requires_confirmation'}

def source_digest():
    paths=[p for directory,pattern in [('backend/src','*.java'),('docs/sql/migrations','*.sql')] for p in (ROOT/directory).rglob(pattern) if p.is_file()]
    paths += [ROOT/'backend/pom.xml',Path(__file__)]
    h=hashlib.sha256()
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()

def inventory(name):
    path=SPECS[name][2]
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
    actual=d.get('cases');expected=inventory(name)
    if not isinstance(actual,list) or len(actual)!=len(expected):raise ValueError('Incomplete observed requests')
    for row,want in zip(actual,expected):
        if set(row)!=set(want)|{'request_sha256','response_sha256'} or any(row.get(k)!=v for k,v in want.items()) or any(not re.fullmatch('[a-f0-9]{64}',str(row.get(k,''))) for k in ('request_sha256','response_sha256')):raise ValueError('Observed HTTP differs from reviewed inventory')
    if set(d.get('checks',{}))!=CHECKS or any(v is not True for v in d['checks'].values()):raise ValueError('SQL or confirmation checks missing')
    if not re.fullmatch('[a-f0-9]{64}',str(d.get('mysql_container_id',''))):raise ValueError('Missing owned database identity')
    return dict(passed=True,requests=len(actual),tests=len(cases),coverage={'POST '+path:sorted({r['kind'] for r in actual})},checks=d['checks'],mysql_container_id=d['mysql_container_id'],source_sha256=d['source_sha256'],raw_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest())

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');parser.add_argument('--docker',action='store_true');args=parser.parse_args()
    work=ROOT/'.cache'/('recognition-http-'+uuid.uuid4().hex[:12]);work.mkdir(parents=True)
    before=source_digest();started=time.monotonic();report=dict(passed=False,source_sha256=before,full_api_acceptance_passed=False,production_acceptance_passed=False,execution_mode='AUDIT_EXISTING' if args.check_only else 'EXECUTED',suites=[])
    try:
        if not args.check_only:
            for name,(cls,_,_) in SPECS.items():
                for p in (ROOT/'backend/target'/f'{name}-http-evidence.json',ROOT/'backend/target/surefire-reports'/f'TEST-com.autocare.platform.order.{cls}.xml'):p.unlink(missing_ok=True)
            command=['mvn','-B','-Dtest=VinHttpJourneyTest,LicenseHttpJourneyTest','test']
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
        report['sources_unchanged']=before==source_digest()
        if not report['sources_unchanged']:raise ValueError('Sources changed')
        report['passed']=True
    except Exception as exc:report['reason']=type(exc).__name__; (work/'failure.private.txt').write_text(str(exc),encoding='utf-8')
    report['duration_seconds']=round(time.monotonic()-started,3)
    (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Recognition HTTP '+('PASS' if report['passed'] else 'FAIL')+'; '+str(work),flush=True)
    return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
