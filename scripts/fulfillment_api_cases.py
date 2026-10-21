"""Reproducible Newman journey on an owned empty database; LOCAL_TEST is never real payment."""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import re
import secrets
import time
import uuid
from urllib.parse import urlsplit
from generate_api_acceptance import ROOT, SCHEMA, operations

OUTPUT = ROOT / 'docs/testing/postman/fulfillment-cases.postman_collection.json'
MANIFEST = ROOT / 'docs/testing/postman/fulfillment-case-manifest.json'
OPERATION_COUNT = 16


def cases():
    items = []

    def add(name, method, path, status, kind, body=None, role='owner', extra=(), key=None, prerequest=()):
        headers = []
        if body is not None: headers.append({'key':'Content-Type','value':'application/json'})
        if method == 'POST' and 'callback/' not in path:
            headers.append({'key':'Idempotency-Key','value':key or str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-fulfillment:'+name))})
        request = {'method':method,'url':'{{base_url}}'+path,'header':headers,
                   'auth':{'type':'noauth'} if role=='anonymous' else {'type':'bearer','bearer':[
                       {'key':'token','value':'{{'+role+'_token}}','type':'string'}]}}
        if body is not None:
            request['body']={'mode':'raw','raw':body if isinstance(body,str) else json.dumps(body,ensure_ascii=False)}
        success = 'pm.expect(pm.response.json().code).to.eql("SUCCESS");' if 'callback/' in path else 'pm.expect(pm.response.json().code).to.eql(0);'
        envelope = success if status==200 else (f'pm.expect(pm.response.json().code).to.eql({status*100});' if status in (401,403) else 'pm.expect(pm.response.json().code).not.to.eql(0);')
        tests=[f'pm.test("status",()=>pm.response.to.have.status({status}));',
               'pm.test("envelope",()=>{'+envelope+'});',
               'pm.test("no-store",()=>pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store"));']
        tests += ['pm.test("business '+str(i)+'",()=>{'+v+'});' for i,v in enumerate(extra)]
        events=[{'listen':'test','script':{'type':'text/javascript','exec':tests}}]
        if prerequest: events.insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':list(prerequest)}})
        items.append({'name':name,'request':request,'event':events,'kind':kind,'status':status,'role':role,'assertions':len(tests)})

    def write(name, path, body, role, extra=(), bad=None):
        key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-fulfillment:replay:'+name))
        receipt='receipt_'+name
        add(name,'POST',path,200,'normal',body,role,extra=tuple(extra)+(f'pm.collectionVariables.set("{receipt}",JSON.stringify(pm.response.json()));',),key=key)
        add(name+'_replay','POST',path,200,'boundary',body,role,
            extra=[f'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("{receipt}")));'],key=key)
        add(name+'_malformed','POST',path,400,'error',{} if bad is None else bad,role)
        add(name+'_anonymous','POST',path,401,'unauthorized',body,'anonymous')
        wrong='owner' if role!='owner' else 'merchant'
        add(name+'_wrong_role','POST',path,403,'unauthorized',body,wrong)

    def read(name,path,role,extra=()):
        add(name,'GET',path,200,'normal',role=role,extra=extra)
        add(name+'_missing','GET',path.rsplit('/',1)[0]+'/999999',404,'boundary',role=role)
        add(name+'_invalid','GET',path.rsplit('/',1)[0]+'/0',400,'error',role=role)
        add(name+'_anonymous','GET',path,401,'unauthorized',role='anonymous')
        add(name+'_wrong_role','GET',path,403,'unauthorized',role='merchant' if role=='owner' else 'owner')

    slot={'standard_project_id':1,'starts_at':'{{slot_start}}','ends_at':'{{slot_end}}','capacity':2}
    write('slot_publish','/api/merchant/slots',slot,'merchant',extra=['pm.expect(pm.response.json().data.slot_id).to.eql(1);'])
    add('slot_capacity_overflow','POST','/api/merchant/slots',400,'boundary',dict(slot,capacity=101),'merchant')
    read('quote','/api/order/quote/1','owner',extra=['pm.expect(pm.response.json().data.price).to.eql("12.34");'])
    booking={'merchant_project_id':1,'quote_version_id':1,'vehicle_id':1,'slot_id':1}
    add('booking_foreign_vehicle','POST','/api/order/create',404,'unauthorized',dict(booking,vehicle_id=2))
    add('booking_stale_quote','POST','/api/order/create',409,'error',dict(booking,quote_version_id=999999))
    write('booking','/api/order/create',booking,'owner',extra=[
        'const d=pm.response.json().data; pm.expect(d.order_id).to.eql(1); pm.expect(d.amount_due).to.eql("12.34"); pm.expect(d.status).to.eql("PENDING_PAYMENT"); pm.collectionVariables.set("order_no",d.order_no);'])
    add('booking_changed_intent','POST','/api/order/create',400,'error',dict(booking,vehicle_id=2),key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-fulfillment:replay:booking')))
    read('owner_order','/api/order/1','owner',extra=['pm.expect(pm.response.json().data.status).to.eql("PENDING_PAYMENT");'])
    add('owner_order_foreign','GET','/api/order/1',404,'unauthorized',role='other')
    read('merchant_order','/api/merchant/orders/1','merchant',extra=['pm.expect(pm.response.json().data.order_id).to.eql(1);'])
    add('merchant_order_foreign','GET','/api/merchant/orders/1',404,'unauthorized',role='other_merchant')
    add('wechat_payment_unconfigured','POST','/api/payments/create',503,'error',{'order_id':1,'channel':'WECHAT'})
    add('payment_foreign_owner','POST','/api/payments/create',404,'unauthorized',{'order_id':1,'channel':'LOCAL_TEST'},role='other')
    write('payment','/api/payments/create',{'order_id':1,'channel':'LOCAL_TEST'},'owner',extra=[
        'const d=pm.response.json().data; pm.expect(d.payment_id).to.eql(1); pm.expect(d.test_mode).to.eql(true);'])

    # Callback request bytes, not a JWT, are signed. Normal/replay use identical event and timestamp.
    def callback(name,status,kind,mode):
        script=[
            'const C=require("crypto-js");',
            'if (!pm.collectionVariables.get("callback_occurred")) pm.collectionVariables.set("callback_occurred",new Date().toISOString());',
            'const body={event_id:"33333333-3333-4333-8333-333333333333",payment_id:1,channel_payment_no:"synthetic-fulfillment-1",status:"SUCCEEDED",amount:"12.34",currency:"CNY",order_no:pm.collectionVariables.get("order_no"),occurred_at:pm.collectionVariables.get("callback_occurred")};',
            'const mode="'+mode+'";',
            'if(mode==="bad_body") body.amount="invalid";',
            'const raw=JSON.stringify(body); pm.variables.set("callback_body",raw);',
            'let timestamp=pm.collectionVariables.get("callback_timestamp"); if(!timestamp){timestamp=String(Math.floor(Date.now()/1000));pm.collectionVariables.set("callback_timestamp",timestamp);}',
            'if(mode==="expired") timestamp="1";',
            'const nonce="44444444-4444-4444-8444-444444444444";',
            'let signature=C.HmacSHA256(timestamp+"\\n"+nonce+"\\n"+raw+"\\n",pm.environment.get("payment_secret")).toString(C.enc.Hex);',
            'if(mode==="bad_signature") signature="0".repeat(64);',
            'pm.request.headers.upsert({key:"X-Test-Timestamp",value:timestamp}); pm.request.headers.upsert({key:"X-Test-Nonce",value:nonce}); pm.request.headers.upsert({key:"X-Test-Signature",value:signature});',
        ]
        add(name,'POST','/api/payments/callback/LOCAL_TEST',status,kind,'{{callback_body}}','anonymous',prerequest=script)
        items[-1]['callback_mode']=mode
    callback('callback_bad_signature',401,'unauthorized','bad_signature')
    callback('callback_expired',401,'boundary','expired')
    callback('callback_malformed',400,'error','bad_body')
    callback('callback_paid',200,'normal','valid')
    callback('callback_replay',200,'boundary','valid')
    add('order_paid','GET','/api/order/1',200,'normal',extra=[
        'const d=pm.response.json().data; pm.expect(d.status).to.eql("PAID"); pm.expect(d.payment_summary.test_mode).to.eql(true); pm.expect(d.appointment_code).to.match(/^[0-9]{6}$/); pm.collectionVariables.set("appointment_code",d.appointment_code);'])
    pickup={'order_id':1,'appointment_code':'{{appointment_code}}','photos':dict(zip(['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR'],range(1,8))),
            'mileage':110,'fuel_level':'HALF','damage_status':'NONE','damages':[],'arrival_reason':'合成测试提前到店'}
    add('pickup_foreign_merchant','POST','/api/check/pickup/submit',404,'unauthorized',pickup,'other_merchant')
    add('pickup_negative_mileage','POST','/api/check/pickup/submit',400,'boundary',dict(pickup,mileage=-1),'merchant')
    write('pickup','/api/check/pickup/submit',pickup,'merchant')
    add('assignment_before_confirmation','POST','/api/merchant/orders/1/assign',409,'error',{'technician_id':3},'merchant')
    add('confirmation_foreign_owner','POST','/api/check/pickup/confirm',404,'unauthorized',{'order_id':1,'decision':'CONFIRM'},'other')
    write('confirmation','/api/check/pickup/confirm',{'order_id':1,'decision':'CONFIRM'},'owner')
    add('assignment_foreign_merchant','POST','/api/merchant/orders/1/assign',404,'unauthorized',{'technician_id':3},'other_merchant')
    write('assignment','/api/merchant/orders/1/assign',{'technician_id':3},'merchant')
    write('accept','/api/tech/orders/1/accept',{},'technician',bad={'injected':True},extra=['pm.expect(pm.response.json().data.order_status).to.eql("IN_SERVICE");'])
    report={'order_id':1,'process_photos':[9],'fault_part_photos':[],'finish_photos':[10],'no_fault_parts':True,
            'repair_plan':'合成检查清洁','fault_analysis':'合成无故障','parts_used':[],'no_parts':True,'work_hours':30}
    add('report_before_protection','POST','/api/tech/report/submit',409,'error',report,'technician',extra=['pm.expect(pm.response.json().code).to.eql(43002);'])
    protection={'order_id':1,'items':['SEAT_COVER','STEERING_COVER'],'photo_file_id':8}
    add('protection_foreign_merchant','POST','/api/check/protection/upload',404,'unauthorized',protection,'other_merchant')
    write('protection','/api/check/protection/upload',protection,'merchant')
    add('report_foreign_file','POST','/api/tech/report/submit',422,'unauthorized',dict(report,process_photos=[12]),'technician')
    add('report_negative_hours','POST','/api/tech/report/submit',400,'boundary',dict(report,work_hours=-1),'technician')
    write('report','/api/tech/report/submit',report,'technician')
    write('signature','/api/tech/sign',{'order_id':1,'signature_file_id':11},'technician',extra=['pm.expect(pm.response.json().data.order_status).to.eql("PENDING_VERIFY");'])
    add('signature_foreign_file','POST','/api/tech/sign',404,'unauthorized',{'order_id':1,'signature_file_id':12},'technician')
    redeem={'code':'{{appointment_code}}'}
    add('redemption_foreign_merchant','POST','/api/merchant/orders/1/redeem',404,'unauthorized',redeem,'other_merchant')
    write('redemption','/api/merchant/orders/1/redeem',redeem,'merchant')
    add('order_completed','GET','/api/order/1',200,'normal',extra=['pm.expect(pm.response.json().data.status).to.eql("COMPLETED");'])
    add('archives','GET','/api/archive/list?vehicle_id=1',200,'normal',extra=[
        'pm.expect(pm.response.json().data.total).to.eql(1); pm.expect(pm.response.text()).to.include("test_mode");'])
    add('archives_empty_page','GET','/api/archive/list?vehicle_id=1&page=2',200,'boundary',extra=['pm.expect(pm.response.json().data.list).to.have.lengthOf(0);'])
    add('archives_missing_vehicle','GET','/api/archive/list',400,'error')
    add('archives_foreign_owner','GET','/api/archive/list?vehicle_id=1',404,'unauthorized',role='other')
    add('archives_anonymous','GET','/api/archive/list?vehicle_id=1',401,'unauthorized',role='anonymous')
    add('archives_wrong_role','GET','/api/archive/list?vehicle_id=1',403,'unauthorized',role='merchant')
    return items


def assemble(items, name, flag, operation_count):
    names=[item['name'] for item in items]
    if len(names)!=len(set(names)):raise ValueError('Duplicate business case name')
    spec=json.loads((ROOT/'docs/api/openapi.json').read_text(encoding='utf-8'))
    ops=operations(spec)
    manifest=[]
    for item in items:
        path=urlsplit(item['request']['url'].removeprefix('{{base_url}}')).path
        exact=[op['operationId'] for method,route,op,_ in ops if method==item['request']['method'] and route==path]
        candidates=exact or [op['operationId'] for method,route,op,_ in ops if method==item['request']['method']
                    and re.fullmatch(re.sub(r'\{[^}]+\}',r'[^/]+',route),path)]
        if len(candidates)!=1: raise ValueError('Ambiguous or missing fulfillment operation '+path)
        manifest.append({'name':item['name'],'method':item['request']['method'],'path':path,'operation_id':candidates[0],
                         'kind':item['kind'],'status':item['status'],'role':item['role'],'assertions':item['assertions'],
                         **{key:item[key] for key in ('callback_mode','notice_status') if key in item}})
    collection={'info':{'name':name,'schema':SCHEMA},
                'variable':[{'key':'base_url','value':'http://127.0.0.1:1'},{'key':flag,'value':'false'}],
                'event':[{'listen':'prerequest','script':{'type':'text/javascript','exec':[
                    'if(pm.variables.get("'+flag+'")!=="true"){pm.execution.skipRequest();throw new Error("Use the isolated fulfillment runner");}']}}],
                'item':[{k:v for k,v in item.items() if k in {'name','request','event'}} for item in items]}
    return collection, {'version':1,'cases':len(manifest),'operations':operation_count,'full_api_acceptance_passed':False,'items':manifest}


def assets():
    return assemble(cases(),'Autocare isolated reservation to archive LOCAL_TEST','isolated_fulfillment_cases',OPERATION_COUNT)


def seed(mysql, secret):
    mysql.sql("""INSERT INTO user(id,openid,status) VALUES(1,'fulfillment-owner',1),(2,'fulfillment-other',1);
INSERT INTO brand(id,name,status) VALUES(1,'synthetic brand',1);
INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'synthetic series',1);
INSERT INTO model(id,series_id,year,config_name,status) VALUES(1,1,'2026','synthetic model',1);
INSERT INTO vehicle(id,user_id,model_id,current_mileage,plate_no) VALUES(1,1,1,100,'粤A12345'),(2,2,1,12345,'粤B23456');
INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic shop','synthetic address',1),(2,2,'synthetic other shop','synthetic address',1);
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','fulfillment-merchant','ACTIVE'),(2,2,'MERCHANT','fulfillment-other-shop','ACTIVE'),(3,1,'TECHNICIAN','fulfillment-tech','ACTIVE');
INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'fulfillment-cases','synthetic-tech',3);
INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'synthetic service',1,'synthetic inspection',1,100);
INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(1,1,1,12.34,1);
INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(1,1,1,12.34,1,1);""")
    for i in range(1,13):
        mysql.sql(f"INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES({i},'staff_account',{1 if i<=8 else 3 if i<=11 else 2},'synthetic-fulfillment/{i}','image/png',100,'CLEAN');")
    start=(datetime.now(timezone(timedelta(hours=8)))+timedelta(days=1)).replace(hour=10,minute=0,second=0,microsecond=0)
    result={'isolated_fulfillment_cases':'true','slot_start':start.isoformat(),'slot_end':(start+timedelta(hours=1)).isoformat(),
            'payment_secret':secrets.token_hex(32)}
    for role,subject,kind,claim_role,shop,binding in [
        ('owner',1,'user','OWNER',None,None),('other',2,'user','OWNER',None,None),
        ('merchant',1,'staff_account','MERCHANT',1,None),('other_merchant',2,'staff_account','MERCHANT',2,None),
        ('technician',3,'staff_account','TECHNICIAN',1,1)]:
        session=str(uuid.uuid4());app='merchant-account' if claim_role=='MERCHANT' else 'fulfillment-cases'
        sql_value=lambda x:'NULL' if x is None else str(x)
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES('{session}','{kind}',{subject},'{claim_role}','{app}',{sql_value(shop)},{sql_value(binding)},'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,
                'jti':session,'subject_type':kind,'role':claim_role,'app_id':app}
        if shop:claims['merchant_id']=shop
        if binding:claims['binding_id']=binding
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        result[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    return result


def environment(tokens):
    return [('SPRING_PROFILES_ACTIVE','local-payment-test'),('WECHAT_APP_ID','fulfillment-cases'),
            ('PAYMENT_LOCAL_TEST_ENABLED','true'),('PAYMENT_LOCAL_TEST_SECRET',tokens['payment_secret']),
            ('SERVICE_ARCHIVE_ENABLED','true'),('SERVICE_ARCHIVE_INTERVAL_MS','250')]


def audit(path, collection, manifest, tokens, expected_operations=OPERATION_COUNT):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=expected_operations)
    executions=json.loads(path.read_text(encoding='utf-8'))['run']['executions']
    definitions={x['name']:x for x in manifest['items']}
    signed=[]
    for execution in executions:
        case=definitions[execution['item']['name']]
        if 'callback_mode' not in case:continue
        request=execution['request'];headers={h['key'].lower():h['value'] for h in request['header'] if not h.get('disabled')}
        timestamp,nonce,signature=(headers[k] for k in ('x-test-timestamp','x-test-nonce','x-test-signature'))
        raw=request['body']['raw'];mode=case['callback_mode']
        wanted=hmac.new(tokens['payment_secret'].encode(),(timestamp+'\n'+nonce+'\n'+raw+'\n').encode(),hashlib.sha256).hexdigest()
        if (mode=='bad_signature') == hmac.compare_digest(signature,wanted):
            raise ValueError('Callback evidence does not match declared signature mode')
        body=json.loads(raw)
        if mode=='expired' and timestamp!='1':raise ValueError('Expired callback was not actually expired')
        if mode=='bad_body' and body.get('amount')!='invalid':raise ValueError('Malformed callback was not actually malformed')
        if mode=='valid':
            if body.get('payment_id')!=1 or body.get('amount')!='12.34' or body.get('currency')!='CNY':
                raise ValueError('Callback payment intent differs')
            signed.append((raw,timestamp,nonce,signature))
    if len(signed)!=2 or signed[0]!=signed[1]:raise ValueError('Callback replay must use identical signed bytes')
    result['signed_callback_and_exact_replay_verified']=True
    return result


def verify_database(mysql):
    deadline=time.monotonic()+20
    while mysql.text('SELECT status FROM service_archive_job WHERE order_id=1;')!='DONE':
        if time.monotonic()>=deadline:raise ValueError('Service archive job did not finish')
        time.sleep(.1)
    expected={'orders':1,'payments':1,'payment_events':1,'pickups':1,'assignments':1,'protections':1,'reports':1,
              'redemptions':1,'archive_jobs':1,'archives':1}
    tables={'orders':'order','payments':'payment','payment_events':'payment_event','pickups':'pickup_check',
            'assignments':'technician_assignment','protections':'repair_protection','reports':'technician_report',
            'redemptions':'order_redemption','archive_jobs':'service_archive_job','archives':'vehicle_archive'}
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    if actual!=expected:raise ValueError('Fulfillment business rows are missing or duplicated')
    checks={
        'order_completed':mysql.text("SELECT status FROM `order` WHERE id=1;")=='COMPLETED',
        'local_payment_only':mysql.text("SELECT CONCAT(channel,':',status,':',amount) FROM payment WHERE id=1;")=='LOCAL_TEST:SUCCEEDED:12.34',
        'redemption_test_mode':mysql.text('SELECT test_mode FROM order_redemption WHERE order_id=1;')=='1',
        'report_signed':mysql.text('SELECT COUNT(*) FROM technician_report WHERE order_id=1 AND status=1 AND signed_at IS NOT NULL;')=='1',
        'foreign_vehicle_unchanged':mysql.text('SELECT current_mileage FROM vehicle WHERE id=2;')=='12345',
        'no_foreign_orders':mysql.text('SELECT COUNT(*) FROM `order` WHERE user_id=2 OR merchant_id=2;')=='0',
    }
    if not all(checks.values()):raise ValueError('Fulfillment final state/privacy/test-mode invariant failed')
    return {'row_counts':actual,'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:
        path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Fulfillment API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
