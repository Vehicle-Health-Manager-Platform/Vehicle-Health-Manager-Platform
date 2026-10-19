"""Declarative owner API scenarios; synthetic identities are created only by the isolated runner."""
import base64
import hashlib
import hmac
import json
import time
import uuid
from generate_api_acceptance import ROOT, SCHEMA, operations

OUTPUT = ROOT / 'docs/testing/postman/owner-cases.postman_collection.json'
MANIFEST = ROOT / 'docs/testing/postman/owner-case-manifest.json'
KINDS = {'normal', 'boundary', 'error', 'unauthorized'}


def cases():
    items = []
    def add(name, method, path, status, kind, body=None, role='owner', extra=(), key=None, no_key=False):
        headers = []
        if body is not None: headers.append({'key': 'Content-Type', 'value': 'application/json'})
        if method == 'POST' and not no_key and path != '/api/vehicle/match-plate' and not path.startswith('/api/vehicle/match-plate?'):
            headers.append({'key': 'Idempotency-Key', 'value': key or str(uuid.uuid5(uuid.NAMESPACE_URL, 'autocare-owner:'+name))})
        request = {'method': method, 'url': '{{base_url}}'+path, 'header': headers,
                   'auth': {'type': 'noauth'} if role == 'anonymous' else {'type': 'bearer', 'bearer': [
                       {'key': 'token', 'value': '{{'+role+'_token}}', 'type': 'string'}]}}
        if body is not None: request['body'] = {'mode': 'raw', 'raw': json.dumps(body, ensure_ascii=False)}
        scripts = [
            f'pm.test("status", () => pm.response.to.have.status({status}));',
            'pm.test("envelope", () => {' + ('pm.expect(pm.response.json().code).to.eql(0);' if status == 200 else
                f'pm.expect(pm.response.json().code).to.eql({status*100});' if status in (401,403) else 'pm.expect(pm.response.json().code).to.not.eql(0);') + '});',
            'pm.test("no-store", () => pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store"));',
        ]
        scripts += ['pm.test("business '+str(i)+'", () => { '+script+' });' for i,script in enumerate(extra)]
        items.append({'name': name, 'request': request, 'event': [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': scripts}}],
                      'case_kind': kind, 'expected_status': status, 'role': role, 'assertions': len(scripts)})

    for route, query in [('brand',''), ('series','brand_id=1&'), ('model','series_id=1&')]:
        path='/api/'+route+'/list'
        add(route+'_normal','GET',path+'?'+query+'page=1',200,'normal',extra=['pm.expect(pm.response.json().data.list).to.have.lengthOf(1);'])
        add(route+'_empty_page','GET',path+'?'+query+'page=2',200,'boundary',extra=['pm.expect(pm.response.json().data.list).to.have.lengthOf(0);'])
        add(route+'_bad_size','GET',path+'?'+query+'page_size=101',400,'error')
        add(route+'_anonymous','GET',path+'?'+query+'page=1',401,'unauthorized',role='anonymous')
        add(route+'_wrong_role','GET',path+'?'+query+'page=1',403,'unauthorized',role='merchant')

    vehicle={'add_type':4,'model_id':1,'current_mileage':0,'plate_no':'粤A12345','vin':'LTEST123456789012'}
    vehicle_key='11111111-1111-4111-8111-111111111111'
    add('vehicle_create','POST','/api/vehicle/add',200,'normal',vehicle,key=vehicle_key,
        extra=['pm.expect(pm.response.json().data.vehicle_id).to.eql(2); pm.collectionVariables.set("vehicle_receipt",JSON.stringify(pm.response.json()));'])
    add('vehicle_replay','POST','/api/vehicle/add',200,'boundary',vehicle,key=vehicle_key,
        extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("vehicle_receipt")));'])
    add('vehicle_changed_intent','POST','/api/vehicle/add',400,'error',dict(vehicle,current_mileage=1),key=vehicle_key)
    add('vehicle_duplicate','POST','/api/vehicle/add',409,'error',vehicle)
    add('vehicle_negative_mileage','POST','/api/vehicle/add',400,'boundary',dict(vehicle,current_mileage=-1))
    add('vehicle_integer_overflow','POST','/api/vehicle/add',400,'boundary',dict(vehicle,current_mileage=2147483648))
    add('vehicle_invalid_vin','POST','/api/vehicle/add',400,'error',dict(vehicle,vin='INVALID'))
    add('vehicle_unknown_model','POST','/api/vehicle/add',404,'error',dict(vehicle,model_id=999999))
    add('vehicle_missing_key','POST','/api/vehicle/add',400,'error',vehicle,no_key=True)
    add('vehicle_create_anonymous','POST','/api/vehicle/add',401,'unauthorized',vehicle,role='anonymous')
    add('vehicle_create_wrong_role','POST','/api/vehicle/add',403,'unauthorized',vehicle,role='merchant')
    add('vehicles_normal','GET','/api/vehicle/list',200,'normal',extra=[
        'pm.expect(pm.response.json().data.total).to.eql(1); pm.expect(pm.response.json().data.list[0].vehicle_id).to.eql(2);',
        'const text=JSON.stringify(pm.response.json().data); pm.expect(text).not.to.include("粤A12345"); pm.expect(text).not.to.include("LTEST123456789012");'])
    add('vehicles_other_owner','GET','/api/vehicle/list',200,'unauthorized',role='other',extra=[
        'pm.expect(pm.response.json().data.total).to.eql(1); pm.expect(pm.response.json().data.list[0].vehicle_id).to.eql(1);'])
    add('vehicles_page_boundary','GET','/api/vehicle/list?page_size=100',200,'boundary')
    add('vehicles_invalid_page','GET','/api/vehicle/list?page=0',400,'error')
    add('vehicles_anonymous','GET','/api/vehicle/list',401,'unauthorized',role='anonymous')
    add('vehicles_wrong_role','GET','/api/vehicle/list',403,'unauthorized',role='merchant')
    match='/api/vehicle/match-plate'
    add('plate_owned','POST',match,200,'normal',{'plate_no':'粤A12345'},extra=['pm.expect(pm.response.json().data.matches[0].vehicle_id).to.eql(2);'])
    add('plate_other_owner','POST',match,200,'unauthorized',{'plate_no':'粤A12345'},role='other',extra=['pm.expect(pm.response.json().data.matches).to.have.lengthOf(0);'])
    add('plate_no_match','POST',match,200,'boundary',{'plate_no':'粤C99999'},extra=['pm.expect(pm.response.json().data.matches).to.have.lengthOf(0);'])
    add('plate_invalid','POST',match,400,'error',{'plate_no':'INVALID'})
    add('plate_identity_override','POST',match,400,'error',{'plate_no':'粤A12345','user_id':1})
    add('plate_query_override','POST',match+'?user_id=1',400,'error',{'plate_no':'粤A12345'})
    add('plate_anonymous','POST',match,401,'unauthorized',{'plate_no':'粤A12345'},role='anonymous')
    add('plate_wrong_role','POST',match,403,'unauthorized',{'plate_no':'粤A12345'},role='merchant')
    archive={'vehicle_id':2,'archive_type':1,'recorded_date':'2026-10-19','mileage':0,'title':'修'*80,'notes':'检'*1000,'file_ids':[]}
    archive_key='22222222-2222-4222-8222-222222222222'
    add('archive_create','POST','/api/archive/add',200,'normal',archive,key=archive_key,
        extra=['pm.expect(pm.response.json().data.archive_id).to.eql(2); pm.collectionVariables.set("archive_receipt",JSON.stringify(pm.response.json()));'])
    add('archive_replay','POST','/api/archive/add',200,'boundary',archive,key=archive_key,
        extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("archive_receipt")));'])
    add('archive_changed_intent','POST','/api/archive/add',400,'error',dict(archive,notes='changed'),key=archive_key)
    add('archive_other_vehicle','POST','/api/archive/add',404,'unauthorized',dict(archive,vehicle_id=1),extra=['pm.expect(pm.response.text()).not.to.include("FOREIGN_PRIVATE_NOTES");'])
    add('archive_other_file','POST','/api/archive/add',404,'unauthorized',dict(archive,file_ids=[1]))
    add('archive_title_overflow','POST','/api/archive/add',400,'boundary',dict(archive,title='修'*81))
    add('archive_notes_overflow','POST','/api/archive/add',400,'boundary',dict(archive,notes='检'*1001))
    add('archive_negative_mileage','POST','/api/archive/add',400,'boundary',dict(archive,mileage=-1))
    add('archive_invalid_date','POST','/api/archive/add',400,'error',dict(archive,recorded_date='2026-02-30'))
    add('archive_invalid_type','POST','/api/archive/add',400,'error',dict(archive,archive_type=11))
    add('archive_unsupported_voice','POST','/api/archive/add',400,'error',dict(archive,input_type=2))
    add('archive_missing_key','POST','/api/archive/add',400,'error',archive,no_key=True)
    add('archive_anonymous','POST','/api/archive/add',401,'unauthorized',archive,role='anonymous')
    add('archive_wrong_role','POST','/api/archive/add',403,'unauthorized',archive,role='merchant')
    add('archives_normal','GET','/api/archive/list?vehicle_id=2',200,'normal',extra=[
        'const d=pm.response.json().data; pm.expect(d.total).to.eql(1); pm.expect(d.list[0].archive_id).to.eql(2); pm.expect(d.list[0].title.length).to.eql(80); pm.expect(d.list[0].notes.length).to.eql(1000);',
        'pm.expect(pm.response.text()).not.to.include("FOREIGN_PRIVATE_NOTES");'])
    add('archives_empty_page','GET','/api/archive/list?vehicle_id=2&page=2',200,'boundary',extra=['pm.expect(pm.response.json().data.list).to.have.lengthOf(0);'])
    add('archives_missing_vehicle','GET','/api/archive/list',400,'error')
    add('archives_other_owner','GET','/api/archive/list?vehicle_id=2',404,'unauthorized',role='other',extra=['pm.expect(pm.response.text()).not.to.include("FOREIGN_PRIVATE_NOTES");'])
    add('archives_other_vehicle','GET','/api/archive/list?vehicle_id=1',404,'unauthorized')
    add('archives_anonymous','GET','/api/archive/list?vehicle_id=2',401,'unauthorized',role='anonymous')
    add('archives_wrong_role','GET','/api/archive/list?vehicle_id=2',403,'unauthorized',role='merchant')
    return items


def assets():
    from urllib.parse import urlsplit
    spec=json.loads((ROOT/'docs/api/openapi.json').read_text(encoding='utf-8'))
    ops={(method,path): op['operationId'] for method,path,op,_ in operations(spec)}
    items=cases(); manifest=[]
    for item in items:
        route=urlsplit(item['request']['url'].removeprefix('{{base_url}}')).path
        manifest.append({'name':item['name'],'method':item['request']['method'],'path':route,
                         'operation_id':ops[(item['request']['method'],route)],'kind':item['case_kind'],
                         'status':item['expected_status'],'assertions':item['assertions'],'role':item['role']})
    collection={'info':{'name':'Autocare owner vehicle/archive isolated cases','schema':SCHEMA},
        'variable':[{'key':'base_url','value':'http://127.0.0.1:1'},{'key':'isolated_owner_cases','value':'false'}],
        'event':[{'listen':'prerequest','script':{'type':'text/javascript','exec':[
            'if (pm.variables.get("isolated_owner_cases") !== "true") { pm.execution.skipRequest(); throw new Error("Use the isolated owner case runner"); }']}}],
        'item':[{k:v for k,v in item.items() if k not in {'case_kind','expected_status','role','assertions'}} for item in items]}
    return collection, {'version':1,'cases':len(manifest),'full_api_acceptance_passed':False,'items':manifest}


def seed(mysql, secret):
    sessions={role:str(uuid.uuid4()) for role in ('owner','other','merchant')}
    mysql.sql("""INSERT INTO user(id,openid,status) VALUES(1,'owner-case-a',1),(2,'owner-case-b',1);
INSERT INTO brand(id,name,status) VALUES(1,'synthetic brand',1);
INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'synthetic series',1);
INSERT INTO model(id,series_id,year,config_name,status) VALUES(1,1,'2026','synthetic model',1);
INSERT INTO vehicle(id,user_id,model_id,current_mileage,plate_no) VALUES(1,2,1,12345,'粤B23456');
INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES(1,1,1,3,JSON_OBJECT('title','private','notes','FOREIGN_PRIVATE_NOTES'),UTC_TIMESTAMP());
INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(1,'user',2,'synthetic-owner-case/foreign','image/png',32,'CLEAN');
INSERT INTO vehicle_archive_file(archive_id,file_id,position) VALUES(1,1,0);
INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic shop','synthetic address',1);
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-owner-case-merchant','ACTIVE');""")
    result={'isolated_owner_cases':'true'}
    for role,session in sessions.items():
        owner=role!='merchant'; subject=2 if role=='other' else 1
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,
                'jti':session,'subject_type':'user' if owner else 'staff_account','role':'OWNER' if owner else 'MERCHANT',
                'app_id':'owner-cases' if owner else 'merchant-account'}
        if not owner: claims['merchant_id']=1
        mysql.sql("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES('"+session+"','"+claims['subject_type']+"',"+str(subject)+",'"+claims['role']+"','"+claims['app_id']+"',"+('NULL' if owner else '1')+",'"+hashlib.sha256(session.encode()).hexdigest()+"',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        encode=lambda value:base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=').decode()
        message=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        signature=base64.urlsafe_b64encode(hmac.new(secret.encode(),message.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
        result[role+'_token']=message+'.'+signature
    return result


def verify_database(mysql):
    expected={'vehicles':2,'owner_vehicles':1,'archives':2,'owner_archives':1,'keys':2,'audits':2}
    sql="SELECT JSON_OBJECT('vehicles',(SELECT COUNT(*) FROM vehicle),'owner_vehicles',(SELECT COUNT(*) FROM vehicle WHERE user_id=1),'archives',(SELECT COUNT(*) FROM vehicle_archive),'owner_archives',(SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=2),'keys',(SELECT COUNT(*) FROM idempotency_record),'audits',(SELECT COUNT(*) FROM audit_log));"
    actual=json.loads(mysql.text(sql))
    if actual!=expected: raise ValueError('Owner case write/idempotency/audit totals differ from expected')
    if mysql.text('SELECT current_mileage FROM vehicle WHERE id=1;')!='12345': raise ValueError('Foreign vehicle changed')
    if mysql.text("SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$.notes')) FROM vehicle_archive WHERE id=1;")!='FOREIGN_PRIVATE_NOTES': raise ValueError('Foreign archive changed')
    return actual


if __name__=='__main__':
    collection,manifest=assets()
    OUTPUT.write_text(json.dumps(collection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Owner API cases:',manifest['cases'])
