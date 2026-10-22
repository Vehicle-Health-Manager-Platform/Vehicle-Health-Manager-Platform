"""Session rotation/revocation with real HTTP/MySQL, synthetic prior login and no supplier IO."""
import base64
import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
import secrets
import threading
import time
import uuid
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT,assemble,seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/auth-sessions-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/auth-sessions-case-manifest.json'
OPERATION_COUNT=4
CHECKS=set('same_session_count four_rotations failed_refresh_hashes_unchanged failed_refresh_rows_unchanged revoked_sessions_expired_credentials rejected_old_refresh no_new_identity no_business_writes no_plain_refresh_storage race_one_success race_winner_bound race_logout_invalidates untouched_other_session'.split())
TOKENS={};SESSIONS={};ROTATED={};BACKEND=None;SECRET=''


def cases():
    c=Cases();add=c.add
    refresh='/api/auth/refresh';logout='/api/auth/logout';operator='/api/auth/operator/logout';vehicles='/api/vehicle/list'
    c.read('session_vehicle',vehicles,extra=['pm.expect(pm.response.json().data.list.map(i=>i.vehicle_id)).to.eql([1]);'],boundary=(vehicles+'?page=1000000',200),error=(vehicles+'?page=0',400))
    for name,body,status,kind in [('malformed','{',400,'error'),('missing',{},401,'boundary'),('short',{'refresh_token':'x'*39},401,'error'),('long',{'refresh_token':'x'*201},401,'error'),('unknown',{'refresh_token':'x'*43},401,'unauthorized')]:
        add('refresh_'+name,'POST',refresh,status,kind,body,'anonymous')
    for label,status in [('expired',401),('revoked',401),('wrong_app',401),('disabled_owner',403),('disabled_tech',403),('operator',401)]:
        add('refresh_'+label,'POST',refresh,status,'unauthorized',{'refresh_token':'{{'+label+'_refresh}}'},'anonymous')
    for label,role in [('owner','owner'),('merchant','merchant'),('technician','technician')]:
        add('refresh_'+label,'POST',refresh,200,'normal',{'refresh_token':'{{'+label+'_refresh}}'},'anonymous',extra=[
            'const d=pm.response.json().data;pm.expect(d.token_type).to.eql("Bearer");pm.expect(d.expires_in).to.eql(900);pm.expect(d.refresh_token).to.match(/^[A-Za-z0-9_-]{43}$/);pm.expect(d.refresh_token).not.to.eql(pm.environment.get("'+label+'_refresh"));pm.expect(d.user.role).to.eql("'+role+'");pm.expect(Object.keys(d).sort()).to.eql(["access_token","expires_in","refresh_token","token_type","user"]);',
            'const d=pm.response.json().data;pm.collectionVariables.set("'+label+'_new_token",d.access_token);pm.collectionVariables.set("'+label+'_next_refresh",d.refresh_token);'])
        add('refresh_'+label+'_old_rejected','POST',refresh,401,'boundary',{'refresh_token':'{{'+label+'_refresh}}'},'anonymous')
    add('new_owner_access','GET',vehicles,200,'normal',role='owner_new',extra=['pm.expect(pm.response.json().data.list.map(i=>i.vehicle_id)).to.eql([1]);'])
    add('old_owner_access_before_logout','GET',vehicles,200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'])
    add('logout_bad_credential','POST',logout,401,'error',role='bad')
    add('logout_anonymous','POST',logout,401,'unauthorized',role='anonymous')
    add('logout_operator_denied','POST',logout,403,'unauthorized',role='operator')
    add('operator_logout_owner_denied','POST',operator,403,'unauthorized',{},'owner')
    add('operator_logout_bad_body','POST',operator,400,'error',{'revoke_all':True},'operator')
    add('operator_logout_bad_query','POST',operator+'?session_id=other',400,'error',{},'operator')
    add('operator_logout_anonymous','POST',operator,401,'unauthorized',{},'anonymous')
    add('operator_logout','POST',operator,200,'normal',{},'operator',extra=['pm.expect(pm.response.json().data).to.eql({revoked:true});'])
    add('operator_logout_repeat','POST',operator,401,'boundary',{},'operator')
    for label in ('merchant','technician','owner'):
        add('logout_'+label,'POST',logout,200,'normal',role=label+'_new',extra=['pm.expect(pm.response.json().data).to.eql({revoked:true});'])
        add('logout_'+label+'_repeat','POST',logout,401,'boundary',role=label)
        add('refresh_'+label+'_after_logout','POST',refresh,401,'unauthorized',{'refresh_token':'{{'+label+'_next_refresh}}'},'anonymous')
    for name,role in [('old_owner_after_logout','owner'),('new_owner_after_logout','owner_new')]:add(name,'GET',vehicles,401,'unauthorized',role=role)
    add('other_owner_survives','GET',vehicles,200,'boundary',role='other',extra=['pm.expect(pm.response.json().data.list.map(i=>i.vehicle_id)).to.eql([2]);'])
    # Session endpoints do not use business idempotency records.
    for item in c.items:item['request']['header']=[h for h in item['request']['header'] if h['key']!='Idempotency-Key']
    return c.items


def assets():return assemble(cases(),'Autocare isolated auth session rotation and revocation','isolated_auth_sessions_cases',OPERATION_COUNT)


def encoded(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=').decode()
def signed(claims,secret):
    raw=encoded(dict(alg='HS256',typ='JWT'))+'.'+encoded(claims)
    return raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
def claims(token):return json.loads(base64.urlsafe_b64decode(token.split('.')[1]+'==='))
def digest(value):return hashlib.sha256(value.encode()).hexdigest()


def seed(mysql,secret):
    global SECRET
    SECRET=secret;tokens=identity_seed(mysql,secret)
    mysql.sql("""INSERT INTO user(id,openid,status) VALUES(3,'synthetic-disabled-session-owner',2);
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(4,1,'TECHNICIAN','synthetic-disabled-session-tech','DISABLED');
INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(2,'fulfillment-cases','synthetic-disabled-session-binding',4);
INSERT INTO operator_account(id,account,password_hash,phone,status) VALUES(1,'synthetic-session-operator','unused','13900000061','ACTIVE');""")
    SESSIONS.clear();ROTATED.clear()
    def remember(label,token):
        raw=secrets.token_urlsafe(32);payload=claims(token);session=payload['jti']
        tokens[label+'_token']=token;tokens[label+'_refresh']=raw
        SESSIONS[label]=dict(id=session,original_hash=digest(raw),claims=payload)
        mysql.sql(f"UPDATE auth_session SET refresh_hash='{digest(raw)}' WHERE id='{session}';")
    for label in ('owner','other','merchant','other_merchant','technician'):remember(label,tokens[label+'_token'])
    for label,subject,kind,role,app,shop,binding in [
        ('operator',1,'operator_account','OPERATOR','operator-account',None,None),
        ('expired',1,'user','OWNER','fulfillment-cases',None,None),('revoked',1,'user','OWNER','fulfillment-cases',None,None),
        ('wrong_app',1,'user','OWNER','synthetic-other-app',None,None),('disabled_owner',3,'user','OWNER','fulfillment-cases',None,None),
        ('disabled_tech',4,'staff_account','TECHNICIAN','fulfillment-cases',1,2),('race',1,'user','OWNER','fulfillment-cases',None,None)]:
        session=str(uuid.uuid4());p=dict(iss='vehicle-health-manager',sub=str(subject),iat=int(time.time()),exp=int(time.time())+3600,jti=session,subject_type=kind,role=role,app_id=app)
        if shop:p['merchant_id']=shop
        if binding:p['binding_id']=binding
        nullable=lambda x:'NULL' if x is None else str(x)
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES('{session}','{kind}',{subject},'{role}','{app}',{nullable(shop)},{nullable(binding)},'{digest(session)}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        remember(label,signed(p,secret))
    mysql.sql("UPDATE auth_session SET expires_at='2020-01-01 00:00:00' WHERE id='"+SESSIONS['expired']['id']+"';UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='"+SESSIONS['revoked']['id']+"';")
    for label in ('expired','revoked','wrong_app','disabled_owner','disabled_tech','operator','other','other_merchant'):
        SESSIONS[label]['before']=mysql.text("SELECT * FROM auth_session WHERE id='"+SESSIONS[label]['id']+"';")
    tokens['bad_token']='synthetic.invalid.signature';tokens['isolated_auth_sessions_cases']='true'
    TOKENS.clear();TOKENS.update(tokens);return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]
def prepare_backend(backend):
    global BACKEND
    BACKEND=backend


def response_json(execution):return json.loads(bytes(execution['response']['stream']['data']).decode())


def validate_rotation(data,original_token,original_refresh,secret):
    if set(data)!={'access_token','refresh_token','token_type','expires_in','user'} or data['token_type']!='Bearer' or type(data['expires_in']) is not int or data['expires_in']!=900:raise ValueError('Refresh response shape/lifetime changed')
    token=data['access_token'];raw,signature=token.rsplit('.',1)
    if json.loads(base64.urlsafe_b64decode(token.split('.')[0]+'===' )).get('alg')!='HS256':raise ValueError('Unexpected refresh JWT algorithm')
    expected=base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    if not hmac.compare_digest(signature,expected):raise ValueError('Refreshed JWT signature differs')
    old,new=claims(original_token),claims(token)
    fields=('iss','sub','jti','subject_type','role','app_id','merchant_id','binding_id')
    if any(old.get(k)!=new.get(k) for k in fields) or new.get('exp',0)-new.get('iat',0)!=900:raise ValueError('Refreshed JWT identity or lifetime differs')
    user=data['user'];role={'OWNER':'owner','MERCHANT':'merchant','STAFF':'staff','TECHNICIAN':'technician'}.get(old['role'])
    fields={'id','role','phone_bound'} if role=='owner' else {'id','role','merchant_id'}
    if set(user)!=fields or type(user.get('id')) is not int or user['id']!=int(old['sub']) or user.get('role')!=role or (role=='owner' and type(user.get('phone_bound')) is not bool) or (role!='owner' and user.get('merchant_id')!=old.get('merchant_id')):raise ValueError('Refreshed user projection differs')
    fresh=data['refresh_token']
    if not isinstance(fresh,str) or len(fresh)!=43 or fresh==original_refresh or any(c not in 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_' for c in fresh):raise ValueError('Refresh credential was not rotated')
    return new


def render(raw,tokens):
    for k,v in tokens.items():
        if isinstance(v,str):raw=raw.replace('{{'+k+'}}',v)
    return raw


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    rows={e['item']['name']:e for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']}
    bound=dict(tokens)
    for label in ('owner','merchant','technician'):
        data=response_json(rows['refresh_'+label]).get('data',{})
        validate_rotation(data,tokens[label+'_token'],tokens[label+'_refresh'],SECRET)
        bound[label+'_new_token']=data['access_token'];bound[label+'_next_refresh']=data['refresh_token'];ROTATED[label]=digest(data['refresh_token'])
    result=audit_owner_cases(path,collection,manifest,bound,expected_operations=OPERATION_COUNT)
    for item in collection['item']:
        expected=item['request'];actual=rows[item['name']]['request']
        expected_body=expected.get('body');actual_body=actual.get('body')
        if expected_body is not None:expected_body=dict(expected_body,raw=render(expected_body['raw'],bound))
        header=lambda r:sorted((h['key'].lower(),h['value']) for h in r.get('header',[]) if not h.get('disabled') and h['key'].lower() in ('content-type','idempotency-key'))
        if expected_body!=actual_body or header(expected)!=header(actual):raise ValueError('Refresh credential, logout body or key was substituted')
    result['auth_request_bindings_verified']=True;return result


def http(method,path,body=None,token=None):
    headers={};payload=None
    if body is not None:headers['Content-Type']='application/json';payload=json.dumps(body).encode()
    if token:headers['Authorization']='Bearer '+token
    try:response=urlopen(Request('http://127.0.0.1:'+str(BACKEND.port)+path,data=payload,headers=headers,method=method),timeout=20)
    except HTTPError as error:response=error
    with response:
        if 'no-store' not in response.headers.get('Cache-Control',''):raise ValueError('Auth response is cacheable')
        return response.status,json.loads(response.read())


def verify_database(mysql):
    gate=threading.Barrier(2)
    def attempt():gate.wait(timeout=10);return http('POST','/api/auth/refresh',{'refresh_token':TOKENS['race_refresh']})
    with ThreadPoolExecutor(max_workers=2) as executor:outcomes=list(executor.map(lambda _:attempt(),range(2)))
    one=sorted(s for s,_ in outcomes)==[200,401]
    if not one:raise ValueError('Concurrent refresh did not have exactly one winner')
    winner=next(b['data'] for status,b in outcomes if status==200)
    validate_rotation(winner,TOKENS['race_token'],TOKENS['race_refresh'],SECRET);ROTATED['race']=digest(winner['refresh_token'])
    race_states=[http('GET','/api/vehicle/list',token=winner['access_token'])[0],http('POST','/api/auth/logout',token=winner['access_token'])[0],
        http('POST','/api/auth/refresh',{'refresh_token':winner['refresh_token']})[0],http('GET','/api/vehicle/list',token=TOKENS['race_token'])[0],http('GET','/api/vehicle/list',token=winner['access_token'])[0]]
    byid=lambda label:"SELECT * FROM auth_session WHERE id='"+SESSIONS[label]['id']+"';"
    failed=('expired','revoked','wrong_app','disabled_owner','disabled_tech')
    all_revoked=('owner','merchant','technician','operator','race','revoked')
    checks={
        'same_session_count':mysql.text('SELECT COUNT(*) FROM auth_session;')=='12',
        'four_rotations':set(ROTATED)=={'owner','merchant','technician','race'} and all(mysql.text("SELECT refresh_hash FROM auth_session WHERE id='"+SESSIONS[k]['id']+"';")==v for k,v in ROTATED.items()),
        'failed_refresh_hashes_unchanged':all(mysql.text("SELECT refresh_hash FROM auth_session WHERE id='"+SESSIONS[k]['id']+"';")==SESSIONS[k]['original_hash'] for k in (*failed,'operator')),
        'failed_refresh_rows_unchanged':all(mysql.text(byid(k))==SESSIONS[k]['before'] for k in failed),
        'revoked_sessions_expired_credentials':all(mysql.text("SELECT COUNT(*) FROM auth_session WHERE id='"+SESSIONS[k]['id']+"' AND revoked_at IS NOT NULL;")=='1' for k in all_revoked),
        'rejected_old_refresh':all(ROTATED[k]!=SESSIONS[k]['original_hash'] for k in ROTATED),
        'no_new_identity':mysql.text('SELECT (SELECT COUNT(*) FROM user)+(SELECT COUNT(*) FROM staff_account)+(SELECT COUNT(*) FROM operator_account);')=='8',
        'no_business_writes':mysql.text('SELECT (SELECT COUNT(*) FROM audit_log)+(SELECT COUNT(*) FROM idempotency_record)+(SELECT COUNT(*) FROM `order`)+(SELECT COUNT(*) FROM payment)+(SELECT COUNT(*) FROM coupon_issue);')=='0',
        'no_plain_refresh_storage':all(mysql.text("SELECT refresh_hash FROM auth_session WHERE id='"+SESSIONS[k]['id']+"';")!=TOKENS[k+'_refresh'] for k in SESSIONS),
        'race_one_success':one,'race_winner_bound':True,'race_logout_invalidates':race_states==[200,200,401,401,401],
        'untouched_other_session':all(mysql.text(byid(k))==SESSIONS[k]['before'] for k in ('other','other_merchant')),
    }
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Auth rotation/revocation invariant failed: '+str(checks))
    return dict(checks=checks,concurrent_refresh_requests=2,additional_http_requests=7,scope='Synthetic prior login/identities; real refresh/logout, JWT checks, MySQL rollback and concurrent rotation; no supplier login or SMS')


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Auth session cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
