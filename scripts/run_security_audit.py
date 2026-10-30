"""Repeatable local SAST/dependency inventory. Findings are never converted to a clean verdict.

Raw logs stay private under .cache. --report-only changes the process exit policy,
not the recorded vulnerability verdict. Java SBOM is freshly resolved from the POM.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import uuid
from run_performance_baseline import Docker, Owned

ROOT=Path(__file__).resolve().parents[1]
SEM_IMAGE='semgrep/semgrep@sha256:10301f060aacf84078f9704fb1ba3a321df4ac46b009fd29c1c66880d1db8e77'
TRIVY_IMAGE='aquasec/trivy@sha256:e2b22eac59c02003d8749f5b8d9bd073b62e30fefaef5b7c8371204e0a4b0c08'


def python_bom(root=ROOT):
    components={}
    for profile in ('ocr','asr','embedding'):
        source=root/f'services/{profile}/runtime-wheels-linux.json'
        data=json.loads(source.read_text(encoding='utf-8'))
        for row in data.get('wheels',data.get('files',[])):
            name=re.sub('[-_.]+','-',row['name']).lower();version=row['version'];sha=row['sha256']
            if not re.fullmatch('[a-z0-9][a-z0-9-]*',name) or not re.fullmatch('[A-Za-z0-9_.+!-]+',version) or not re.fullmatch('[a-f0-9]{64}',sha):raise ValueError('Invalid locked component')
            purl=f'pkg:pypi/{name}@{version}'
            item=components.setdefault(purl,{'type':'library','bom-ref':purl,'name':name,'version':version,'purl':purl,'hashes':[],'properties':[]})
            if not any(h['content']==sha for h in item['hashes']):item['hashes'].append({'alg':'SHA-256','content':sha})
            item['properties'].append({'name':'autocare:runtime-profile','value':profile})
    if not components:raise ValueError('Empty runtime dependency inventory')
    return {'bomFormat':'CycloneDX','specVersion':'1.5','version':1,'components':list(components.values())}


def inputs():
    paths=[ROOT/p for p in ('.semgrep.yml','package.json','package-lock.json','backend/pom.xml')]
    folders=[ROOT/p for p in ('backend/src/main','scripts','services','docs/sql/migrations')]
    for app in (ROOT/'apps').iterdir():
        folders.append(app/'src')
        paths.extend(p for p in (app/'package.json',app/'vite.config.js',app/'.env.example') if p.is_file())
    for folder in folders:
        for parent,dirs,files in os.walk(folder):
            dirs[:]=[d for d in dirs if d not in ('node_modules','dist','target','__pycache__','.venv','.cache','.git')]
            paths.extend(Path(parent)/name for name in files if Path(name).suffix in ('.py','.java','.js','.mjs','.cjs','.ts','.vue','.xml','.json','.yml','.yaml','.txt','.in','.properties','.sql'))
    return sorted(set(paths))


def fingerprints(paths):return {p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


class Scanner(Owned):
    def __init__(self,run,role,image,work):
        super().__init__(run,role,f'autocare-security-{run[:12]}-{role}',{'autocare.security.run':run})
        self.image,self.work=image,work
    def start(self):
        self.id=Docker.run(['run','--detach','--rm','--name',self.name,'--label',f'autocare.security.run={self.run}',
                           '--mount',f'type=bind,source={self.work},target=/output','--entrypoint','sh',self.image,'-c','sleep infinity']).stdout.decode().strip()
        self.guard()
    def execute(self,command):
        self.guard();result=Docker.run(['exec',self.id,*command],timeout=1200,allow_failure=True)
        (self.work/f'{self.role}.private.log').write_bytes(result.stdout+result.stderr)
        if result.returncode:raise ValueError('Scanner failed: '+self.role)


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report-only',action='store_true');parser.add_argument('--maven-repository',type=Path)
    args=parser.parse_args(argv);run=uuid.uuid4().hex;work=ROOT/'.cache'/('security-audit-'+run[:12]);work.mkdir(parents=True)
    report={'completed':False,'vulnerability_gate_passed':False,'production_security_accepted':False,'started_at':datetime.now(timezone.utc).isoformat(),'cleanup':[]}
    scans=[];paths=inputs();before=fingerprints(paths);report['source_inputs_sha256']=before
    def command(name,values,cwd=ROOT):
        with (work/f'{name}.private.log').open('wb') as stream:return subprocess.run(values,cwd=cwd,stdout=stream,stderr=subprocess.STDOUT,timeout=1200).returncode
    try:
        maven=shutil.which('mvn.cmd' if sys.platform=='win32' else 'mvn');npm=shutil.which('npm.cmd' if sys.platform=='win32' else 'npm')
        if not maven or not npm:raise ValueError('Maven and npm must be on PATH')
        values=[maven,'-B','-DskipTests','org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom']
        if args.maven_repository:values.append('-Dmaven.repo.local='+str(args.maven_repository.resolve()))
        if command('java-sbom',values,ROOT/'backend'):raise ValueError('Java inventory generation failed')
        shutil.copy2(ROOT/'backend/target/bom.json',work/'java-bom.json')
        (work/'python-bom.json').write_text(json.dumps(python_bom(),ensure_ascii=False),encoding='utf-8')
        # npm audit's nonzero finding exit is expected; malformed/error output is not.
        command('npm-audit',[npm,'audit','--json','--package-lock-only'])
        npm_report=json.loads((work/'npm-audit.private.log').read_text(encoding='utf-8-sig'))
        if npm_report.get('error') or 'vulnerabilities' not in npm_report:raise ValueError('npm audit did not produce a complete result')
        report['npm']=npm_report['metadata']['vulnerabilities']
        with tarfile.open(work/'inputs.tar','w') as archive:
            for path in paths:archive.add(path,arcname=path.relative_to(ROOT).as_posix(),recursive=False)
        sem=Scanner(run,'semgrep',SEM_IMAGE,work);scans.append(sem);sem.start()
        sem.execute(['sh','-c','mkdir -p /src; tar -xf /output/inputs.tar -C /src; cd /src; semgrep scan --config .semgrep.yml --metrics off --disable-version-check --json --output /output/sast.private.json'])
        sast=json.loads((work/'sast.private.json').read_text(encoding='utf-8'))
        if sast.get('errors'):raise ValueError('Incomplete SAST parsing')
        report['sast']={'rules':6,'findings':[{'rule':r['check_id'],'severity':r['extra']['severity'],'path':r['path'],'line':r['start']['line']} for r in sast['results']]}
        sem.close()
        trivy=Scanner(run,'trivy',TRIVY_IMAGE,work);scans.append(trivy);trivy.start();report['dependencies']={}
        for profile in ('java','python'):
            trivy.execute(['trivy','sbom','--skip-version-check','--cache-dir','/output/trivy-cache','--format','json','--output',f'/output/{profile}-vulnerabilities.private.json',f'/output/{profile}-bom.json'])
            raw=json.loads((work/f'{profile}-vulnerabilities.private.json').read_text(encoding='utf-8'))
            report['dependencies'][profile]=[{'package':v['PkgName'],'version':v['InstalledVersion'],'id':v['VulnerabilityID'],'severity':v['Severity'],'fixed_version':v.get('FixedVersion',''),'url':v.get('PrimaryURL','')} for result in raw.get('Results',[]) for v in result.get('Vulnerabilities',[])]
        report['sources_unchanged']=before==fingerprints(inputs())
        report['completed']=report['sources_unchanged']
        report['vulnerability_gate_passed']=bool(report['completed'] and not report['npm']['high'] and not report['npm']['critical']
            and not any(r['severity']=='ERROR' for r in report['sast']['findings'])
            and not any(v['severity'] in ('HIGH','CRITICAL') for values in report['dependencies'].values() for v in values))
    except Exception as exc:report['failure_type']=type(exc).__name__
    finally:
        for scanner in scans:
            try:scanner.close();report['cleanup'].append({'role':scanner.role,'passed':True})
            except Exception:report['cleanup'].append({'role':scanner.role,'passed':False})
        report['completed']=report['completed'] and all(row['passed'] for row in report['cleanup'])
        report['vulnerability_gate_passed']=report['vulnerability_gate_passed'] and report['completed']
        report['finished_at']=datetime.now(timezone.utc).isoformat();(work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Security inventory completed={report["completed"]}; vulnerability gate={report["vulnerability_gate_passed"]}; report={work / "report.json"}',flush=True)
    return 0 if report['completed'] and (args.report_only or report['vulnerability_gate_passed']) else 1


if __name__=='__main__':raise SystemExit(main())
