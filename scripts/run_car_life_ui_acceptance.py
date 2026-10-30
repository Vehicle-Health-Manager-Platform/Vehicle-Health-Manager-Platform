"""Owned H5/MySQL fixture for gstack QA, never an official WeChat login/device run.

Requires an explicitly allowed synthetic fixture and a source-bound performance JAR.
The session bridge is generated only inside this run's private .cache Vite config;
the ordinary application build has no bridge. Drive commands through stdin JSON:
{"browse":["snapshot","-i"]}, {"login":"owner"}, {"fault":true}, {"finish":true}.
"""
import argparse
import base64
import hashlib
import hmac
from http.client import HTTPConnection
from http.server import SimpleHTTPRequestHandler,ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import traceback
import uuid
import run_performance_baseline as p

ROOT=Path(__file__).resolve().parents[1]


def verify_artifact(jar,source):
    report=json.loads(source.read_text(encoding='utf-8'))
    files=[ROOT/'backend/pom.xml',*sorted((ROOT/'backend/src/main').rglob('*')),*sorted((ROOT/'docs/sql/migrations').glob('*.sql'))]
    expected={f.relative_to(ROOT).as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for f in files if f.is_file()}
    actual=report.get('implementation_inputs_sha256',{})
    if report.get('passed') is not True or report.get('implementation_sources_unchanged') is not True or any(actual.get(k)!=v for k,v in expected.items()) \
            or report.get('backend_jar_sha256')!=hashlib.sha256(jar.read_bytes()).hexdigest():raise ValueError('UI fixture requires a current verified JAR')
    return expected


def jwt(secret,claims):
    encode=lambda raw:base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
    raw=encode(b'{"alg":"HS256","typ":"JWT"}')+'.'+encode(json.dumps(claims,separators=(',',':')).encode())
    return raw+'.'+encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest())


def bridge_config(work):
    src=(ROOT/'apps/miniapp/src').as_posix()
    imports=[]
    for name,module in [('setOwnerSession,clearOwnerSession','owner-session'),('merchantSession,clearMerchantSession','merchant-session'),('setTechnicianSession,clearTechnicianSession','technician-session'),('setOperatorSession,clearOperatorSession','operator-session'),('selectOwnerVehicle,clearOwnerVehicle','owner-vehicle-selection')]:
        imports.append('import {'+name+'} from '+json.dumps(src+'/services/'+module+'.js')+';')
    helper='\n'.join(imports)+'''
function clear(){clearOwnerSession();clearMerchantSession();clearTechnicianSession();clearOperatorSession();clearOwnerVehicle();}
globalThis.__AUTOCARE_SYNTHETIC_UI__=Object.freeze({clear,set(o){clear();
 if(o.role==='owner'){setOwnerSession({access_token:o.token,user:{id:1,phone_bound:false}});selectOwnerVehicle({vehicle_id:1});}
 else if(o.role==='merchant')merchantSession.accessToken=o.token;
 else if(o.role==='technician')setTechnicianSession({access_token:o.token,user:{id:2}});
 else if(o.role==='operator')setOperatorSession({access_token:o.token,expires_in:3600,user:{id:1,can_review:true,can_onboard:true,can_catalog:true,can_analytics:true}});
 else throw Error('Unknown synthetic role');return {ready:true,synthetic:true};}});
function badge(){const e=document.createElement('div');e.textContent='合成身份联调 · 非正式微信登录';e.setAttribute('data-synthetic-ui','true');e.style.cssText='position:fixed;right:6px;top:48px;z-index:9999;pointer-events:none;padding:4px 8px;border-radius:6px;background:#e9f2ec;color:#074d30;font:11px sans-serif';document.body.appendChild(e);}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',badge,{once:true});else badge();
'''
    config='import original from '+json.dumps((ROOT/'apps/miniapp/vite.config.js').as_uri())+';\n'
    config+='const source='+json.dumps(helper,ensure_ascii=False)+';\n'
    config+='const bridge={name:"private-synthetic-h5-ui",enforce:"pre",resolveId(id){if(id==="virtual:synthetic-ui")return "\\0synthetic-ui"},load(id){if(id==="\\0synthetic-ui")return source},transform(code,id){if(id.replaceAll("\\\\","/").endsWith("/apps/miniapp/src/main.js"))return "import \'virtual:synthetic-ui\';\\n"+code}};\n'
    config+='export default {...original,plugins:[bridge,...original.plugins]};\n'
    path=work/'vite.config.mjs';path.write_text(config,encoding='utf-8');return path


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--allow-synthetic-ui',action='store_true');parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--source-report',type=Path,required=True)
    parser.add_argument('--browse',type=Path,default=Path.home()/'.agents/skills/gstack/browse/dist/browse.exe');args=parser.parse_args()
    if not args.allow_synthetic_ui:parser.error('Explicit --allow-synthetic-ui required')
    source=verify_artifact(args.jar,args.source_report);run=uuid.uuid4().hex;work=ROOT/'.cache'/('car-life-ui-'+run[:12]);work.mkdir(parents=True)
    frontend_files=sorted(p for p in (ROOT/'apps/miniapp/src').rglob('*') if p.is_file())+[ROOT/'package.json',ROOT/'package-lock.json',ROOT/'apps/miniapp/package.json',ROOT/'apps/miniapp/vite.config.js',ROOT/'scripts/uni-vue-compat.mjs',Path(__file__).resolve()]
    frontend_hash=lambda:{p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in frontend_files}
    report={'passed':False,'source_inputs_sha256':source,'frontend_inputs_sha256':frontend_hash(),'synthetic_identities':True,'official_wechat_verified':False,'native_device_verified':False,'checks':[],'cleanup':[]}
    network=p.Owned(run,'network','autocare-car-life-ui-'+run[:12],{p.LABEL:run},kind='network');mysql=p.MySQL(run,network);backend=None;server=None;server_started=False;browser_rows=[]
    secret=secrets.token_hex(32);tokens={};fault={'enabled':False};requests=[]
    def browse(values):
        result=subprocess.run([str(args.browse),*values],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,encoding='utf-8',timeout=60)
        index=len(browser_rows);log=work/f'browse-{index:03d}.private.txt';log.write_text(result.stdout+'\n'+result.stderr,encoding='utf-8')
        browser_rows.append({'command':values[0],'exit_code':result.returncode,'sha256':hashlib.sha256(log.read_bytes()).hexdigest()})
        return {'exit':result.returncode,'output':result.stdout,'stderr':result.stderr}
    try:
        network.id=p.Docker.run(['network','create','--label',f'{p.LABEL}={run}',network.name]).stdout.decode().strip();network.guard();mysql.start();mysql.migrate(p.migration_files())
        sessions={i:str(uuid.uuid4()) for i in range(1,p.HOME_OWNERS+p.WRITE_OWNERS+1)}
        sql,_=p.build_dataset(sessions,lambda owner:hashlib.sha256(sessions[owner].encode()).hexdigest());mysql.seed(sql)
        mysql.seed("UPDATE brand SET name='合成测试品牌' WHERE id=1; UPDATE series SET name='合成测试车系' WHERE id=1; UPDATE model SET config_name='合成配置A' WHERE id=1;")
        mysql.seed("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-ui-merchant','ACTIVE'),(2,1,'TECHNICIAN','synthetic-ui-tech','ACTIVE'); INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'ui-fixture','synthetic-ui-tech',2); INSERT INTO operator_account(id,account,password_hash,phone,status,can_review) VALUES(1,'synthetic-ui-operator','not-a-login-password','SYNTHETIC','ACTIVE',1);")
        for column in mysql.text('SHOW COLUMNS FROM operator_account;').splitlines():
            name=column.split('\t')[0]
            if re.fullmatch('can_[a-z_]+',name):mysql.seed(f'UPDATE operator_account SET {name}=1 WHERE id=1;')
        now=int(time.time())
        for role,subject,kind,app,shop,binding in [('owner',1,'user','ui-fixture',None,None),('merchant',1,'staff_account','merchant-account',1,None),('technician',2,'staff_account','ui-fixture',1,1),('operator',1,'operator_account','operator-account',None,None)]:
            session=str(uuid.uuid4());upper={'owner':'OWNER','merchant':'MERCHANT','technician':'TECHNICIAN','operator':'OPERATOR'}[role]
            mysql.seed("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES("+','.join("'"+v+"'" if isinstance(v,str) else 'NULL' if v is None else str(v) for v in (session,kind,subject,upper,app,shop,binding,secrets.token_hex(32)))+",DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR));")
            claims={'iss':p.ISSUER,'sub':str(subject),'iat':now,'exp':now+7200,'jti':session,'subject_type':kind,'role':upper,'app_id':app}
            if shop:claims['merchant_id']=shop
            if binding:claims['binding_id']=binding
            tokens[role]=jwt(secret,claims)
        port=p.free_port();p.JAR=args.jar.resolve();p.ROOT=p.JAR.parents[2];backend=p.Backend(run,network,port,secret,40)
        fixture=ROOT/'docs/testing/fixtures/vin-local-synthetic.json'
        sha=hashlib.sha256(fixture.read_bytes()).hexdigest()
        if sha!=hashlib.sha256((p.ROOT/'docs/testing/fixtures/vin-local-synthetic.json').read_bytes()).hexdigest():raise ValueError('Fixture revision mismatch')
        extra=[('WECHAT_APP_ID','ui-fixture'),('VIN_DECODE_ENABLED','true'),('VIN_DECODER_PROVIDER','LOCAL_LICENSED'),('VIN_LOCAL_ALLOW_SYNTHETIC','true'),('VIN_LOCAL_DATA_PATH','/workspace/docs/testing/fixtures/vin-local-synthetic.json'),('VIN_LOCAL_DATA_SHA256',sha)]
        backend.start(mysql,extra_env=extra);backend.wait_healthy()
        output=work/'h5'
        class Handler(SimpleHTTPRequestHandler):
            def __init__(self,*a,**k):super().__init__(*a,directory=str(output),**k)
            def log_message(self,*a):pass
            def proxy(self):
                status=502;raw=b'{"code":50300,"message":"synthetic proxy unavailable"}';content='application/json'
                try:
                    if fault['enabled']:status=503
                    else:
                        length=int(self.headers.get('Content-Length','0'))
                        if not 0<=length<=11*1024*1024:raise ValueError('Request bound')
                        connection=HTTPConnection('127.0.0.1',port,timeout=20)
                        headers={key:self.headers[key] for key in ('Authorization','Content-Type','Idempotency-Key') if key in self.headers}
                        connection.request(self.command,self.path,body=self.rfile.read(length) if length else None,headers=headers)
                        response=connection.getresponse();status=response.status;raw=response.read();content=response.getheader('Content-Type','application/json');connection.close()
                except Exception:pass
                requests.append({'method':self.command,'path':self.path.split('?')[0],'status':status,'synthetic_proxy_fault':fault['enabled']})
                self.send_response(status);self.send_header('Content-Type',content);self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
            def do_GET(self):self.proxy() if self.path.startswith('/api/') else super().do_GET()
            def do_POST(self):self.proxy()
            def do_PUT(self):self.proxy()
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);origin='http://127.0.0.1:'+str(server.server_port)
        env=os.environ.copy();env.update(UNI_OUTPUT_DIR=str(output),UNI_PLATFORM='h5',VITE_API_BASE_URL=origin,VITE_WECHAT_CLOUD_ENV_ID='',VITE_WECHAT_CLOUD_SERVICE='',VITE_VIN_PROVIDER='LOCAL_LICENSED',VITE_CAR_LIFE_PARTNERS=json.dumps({'fuel':{'url':'https://partner.example.org/fuel','provider':'合成测试合作方（非真实服务）'}},ensure_ascii=False))
        config=bridge_config(work);npm=shutil.which('npm.cmd' if os.name=='nt' else 'npm')
        with (work/'build.private.log').open('wb') as log:
            result=subprocess.run([npm,'run','build:h5','--workspace','@autocare/miniapp','--','--config',str(config)],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=300)
        if result.returncode or not (output/'index.html').is_file():raise ValueError('Isolated H5 build failed')
        threading.Thread(target=server.serve_forever,daemon=True).start();server_started=True
        report['origin']=origin;report['vehicles_before']=int(mysql.text('SELECT COUNT(*) FROM vehicle;'));report['foreign_vehicles_before']=int(mysql.text('SELECT COUNT(*) FROM vehicle WHERE user_id=2;'))
        print(json.dumps({'fixture_ready':True,'origin':origin,'work':str(work),'synthetic':True}),flush=True)
        for line in sys.stdin:
            command=json.loads(line)
            if command=={'finish':True}:break
            if 'browse' in command:print(json.dumps(browse(command['browse']),ensure_ascii=False),flush=True)
            elif 'login' in command:
                role=command['login'];file=work/'session.private.js'
                file.write_text('globalThis.__AUTOCARE_SYNTHETIC_UI__.set('+json.dumps({'role':role,'token':tokens[role]})+')',encoding='utf-8');print(json.dumps(browse(['eval',str(file)]),ensure_ascii=False),flush=True)
            elif 'fault' in command:fault['enabled']=command['fault'] is True;print('{"fault_updated":true}',flush=True)
            elif 'check' in command:report['checks'].append(command['check']);print('{"check_recorded":true}',flush=True)
            elif 'sql' in command:
                # Fixed fixture controls only; never execute SQL from the input channel.
                action=command['sql']
                if action=='disable_model':mysql.seed('UPDATE model SET status=0 WHERE id=1;')
                elif action=='enable_model':mysql.seed('UPDATE model SET status=1 WHERE id=1;')
                else:raise ValueError('Unknown fixture control')
                print('{"fixture_updated":true}',flush=True)
        report['vehicles_after']=int(mysql.text('SELECT COUNT(*) FROM vehicle;'));report['foreign_vehicles_after']=int(mysql.text('SELECT COUNT(*) FROM vehicle WHERE user_id=2;'))
        report['new_vehicle_count']=int(mysql.text("SELECT COUNT(*) FROM vehicle WHERE user_id=1 AND vin='LTEST123456789012' AND current_mileage=421 AND model_id=1;"))
        report['frontend_sources_unchanged']=report['frontend_inputs_sha256']==frontend_hash()
        report['passed']=bool(report['checks']) and all(row.get('passed') is True for row in report['checks']) and report['vehicles_after']==report['vehicles_before']+1 and report['foreign_vehicles_before']==report['foreign_vehicles_after'] and report['new_vehicle_count']==1 and report['frontend_sources_unchanged']
    except Exception as exc:
        report['failure_type']=type(exc).__name__;print('UI fixture failed: '+type(exc).__name__,flush=True)
        (work/'failure.private.log').write_text(traceback.format_exc(),encoding='utf-8')
    finally:
        report['browser_commands']=browser_rows;report['http_observations']=requests
        if server:
            if server_started:server.shutdown()
            server.server_close()
        for owned in (backend,mysql,network):
            if owned is None:continue
            try:owned.close();report['cleanup'].append({'role':owned.role,'passed':True})
            except Exception:report['cleanup'].append({'role':owned.role,'passed':False})
        report['passed']=report['passed'] and all(row['passed'] for row in report['cleanup'])
        (work/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'passed':report['passed'],'report':str(work/'report.json')}),flush=True)
    return 0 if report['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
