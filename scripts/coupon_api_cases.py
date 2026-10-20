"""Cash-coupon lifecycle and service redemption through real isolated HTTP, no supplier IO."""
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import uuid
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed, environment as local_environment

OUTPUT=ROOT/'docs/testing/postman/coupon-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/coupon-case-manifest.json'
OPERATION_COUNT=20


class Cases:
    def __init__(self): self.items=[]

    def add(self,name,method,path,status,kind,body=None,role='owner',extra=(),key=None,prerequest=(),mode=None):
        headers=[]
        if body is not None:headers.append({'key':'Content-Type','value':'application/json'})
        if method in ('POST','PUT') and '/callback/' not in path:
            headers.append({'key':'Idempotency-Key','value':key or str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:'+name))})
        request={'method':method,'url':'{{base_url}}'+path,'header':headers,
                 'auth':{'type':'noauth'} if role=='anonymous' else {'type':'bearer','bearer':[
                     {'key':'token','value':'{{'+role+'_token}}','type':'string'}]}}
        if body is not None:request['body']={'mode':'raw','raw':body if isinstance(body,str) else json.dumps(body,ensure_ascii=False)}
        envelope='pm.expect(pm.response.json().code).to.eql('+('"SUCCESS"' if '/callback/' in path else '0')+');' if status==200 else (
            f'pm.expect(pm.response.json().code).to.eql({status*100});' if status in (401,403) else 'pm.expect(pm.response.json().code).not.to.eql(0);')
        tests=[f'pm.test("status",()=>pm.response.to.have.status({status}));',
               'pm.test("envelope",()=>{'+envelope+'});',
               'pm.test("no-store",()=>pm.expect(pm.response.headers.get("Cache-Control")).to.include("no-store"));']
        tests += ['pm.test("business '+str(i)+'",()=>{'+v+'});' for i,v in enumerate(extra)]
        events=[{'listen':'test','script':{'type':'text/javascript','exec':tests}}]
        if prerequest:events.insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':list(prerequest)}})
        self.items.append({'name':name,'request':request,'event':events,'kind':kind,'status':status,'role':role,'assertions':len(tests),
                           **({'callback_mode':mode} if mode else {})})

    def write(self,name,path,body,role='owner',extra=(),method='POST',bad=None):
        key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:'+name));receipt='receipt_'+name
        self.add(name,method,path,200,'normal',body,role,extra=tuple(extra)+(f'pm.collectionVariables.set("{receipt}",JSON.stringify(pm.response.json()));',),key=key)
        self.add(name+'_replay',method,path,200,'boundary',body,role,extra=[
            f'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("{receipt}")));'],key=key)
        self.add(name+'_malformed',method,path,400,'error',{} if bad is None else bad,role)
        self.add(name+'_anonymous',method,path,401,'unauthorized',body,'anonymous')
        self.add(name+'_wrong_role',method,path,403,'unauthorized',body,'owner' if role!='owner' else 'merchant')

    def read(self,name,path,role='owner',extra=(),boundary=None,error=None):
        self.add(name,'GET',path,200,'normal',role=role,extra=extra)
        if boundary:self.add(name+'_boundary','GET',boundary[0],boundary[1],'boundary',role=role)
        if error:self.add(name+'_error','GET',error[0],error[1],'error',role=role)
        self.add(name+'_anonymous','GET',path,401,'unauthorized',role='anonymous')
        self.add(name+'_wrong_role','GET',path,403,'unauthorized',role='merchant' if role=='owner' else 'owner')

    def callback(self,name,kind,mode='valid',refund=False,state='SUCCEEDED',event=None):
        event=event or str(uuid.uuid5(uuid.NAMESPACE_URL,'coupon-notice:'+('refund' if refund else 'payment')+':'+state))
        storage='notice_'+event
        data={'event_id':event,'payment_id':1,'status':state,'amount':'7.34','currency':'CNY','occurred_at':'DYNAMIC'}
        if refund:data.update(refund_id=1,refund_no='DYNAMIC',channel_refund_no='synthetic-coupon-refund')
        else:data.update(order_no='DYNAMIC',channel_payment_no='synthetic-coupon-payment')
        script=[
            'const C=require("crypto-js"); const storage="'+storage+'"; const mode="'+mode+'";',
            'if(!pm.collectionVariables.get(storage+"_occurred")) pm.collectionVariables.set(storage+"_occurred",new Date().toISOString());',
            'const body='+json.dumps(data)+'; body.occurred_at=pm.collectionVariables.get(storage+"_occurred");',
            'body.'+('refund_no=pm.collectionVariables.get("refund_no");' if refund else 'order_no=pm.collectionVariables.get("paid_order_no");'),
            'if(mode==="bad_body") body.amount="invalid";',
            'const raw=JSON.stringify(body); pm.variables.set("notice_body",raw);',
            'let timestamp=pm.collectionVariables.get(storage+"_timestamp");if(!timestamp){timestamp=String(Math.floor(Date.now()/1000));pm.collectionVariables.set(storage+"_timestamp",timestamp);}',
            'if(mode==="expired") timestamp="1";',
            'const nonce="55555555-5555-4555-8555-555555555555";',
            'let signature=C.HmacSHA256(timestamp+"\\n"+nonce+"\\n"+raw+"\\n",pm.environment.get("payment_secret")).toString(C.enc.Hex);',
            'if(mode==="bad_signature") signature="0".repeat(64);',
            'pm.request.headers.upsert({key:"X-Test-Timestamp",value:timestamp});pm.request.headers.upsert({key:"X-Test-Nonce",value:nonce});pm.request.headers.upsert({key:"X-Test-Signature",value:signature});',
        ]
        status=401 if mode in ('bad_signature','expired') else 400 if mode=='bad_body' else 200
        self.add(name,'POST','/api/'+('refunds' if refund else 'payments')+'/callback/LOCAL_TEST',status,kind,
                 '{{notice_body}}','anonymous',prerequest=script,mode=mode)
        self.items[-1]['notice_status']=state


def cases():
    c=Cases();add=c.add
    terms={'name':'合成金额券','type':'DISCOUNT','project_id':1,'amount':'5.00','min_spend':'10.00','total_quantity':3,
           'valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('cash_draft','/api/merchant/coupons',{'terms':terms},'merchant',extra=['pm.expect(pm.response.json().data.coupon_id).to.eql(1);'])
    c.write('cash_publish','/api/merchant/coupons/1/status',{'expected_revision':1,'status':'PUBLISHED'},'merchant',method='PUT')
    add('cash_publish_stale','PUT','/api/merchant/coupons/1/status',409,'error',{'expected_revision':1,'status':'STOPPED'},'merchant')
    add('cash_publish_foreign','PUT','/api/merchant/coupons/1/status',404,'unauthorized',{'expected_revision':2,'status':'STOPPED'},'other_merchant')
    c.write('cash_receive','/api/coupon/receive',{'coupon_id':1,'publication_revision':2},extra=['pm.expect(pm.response.json().data.user_coupon_id).to.eql(1);'])
    add('cash_receive_lifetime_limit','POST','/api/coupon/receive',409,'boundary',{'coupon_id':1,'publication_revision':2})
    c.read('mine','/api/coupon/mine',extra=[
        'const i=pm.response.json().data.items.find(i=>i.user_coupon_id===1);pm.expect(i.status).to.eql("AVAILABLE");pm.collectionVariables.set("original_valid_to",i.terms.valid_to);'],
        boundary=('/api/coupon/mine?page=2',200),error=('/api/coupon/mine?user_id=2',400))
    add('mine_other_owner','GET','/api/coupon/mine',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    slot={'standard_project_id':1,'starts_at':'{{slot_start}}','ends_at':'{{slot_end}}','capacity':2}
    c.write('slot','/api/merchant/slots',slot,'merchant')
    booking={'merchant_project_id':1,'quote_version_id':1,'vehicle_id':1,'slot_id':1,'user_coupon_id':1,'publication_revision':2,'rule_version':'R5B_CASH_V1'}
    preview={k:v for k,v in booking.items() if k not in ('user_coupon_id','publication_revision','rule_version')}
    preview.update(selected_user_coupon_id=1,page=1)
    preview_assert='const d=pm.response.json().data;pm.expect(d.can_create).to.eql(true);pm.expect(d.selected.cash_amount).to.eql("7.34");pm.expect(d.selected.discount_amount).to.eql("5.00");'
    add('coupon_preview','POST','/api/order/coupon-preview',200,'normal',preview,extra=[preview_assert])
    add('coupon_preview_last_page','POST','/api/order/coupon-preview',200,'boundary',dict(preview,page=1000000),extra=[preview_assert])
    add('coupon_preview_financial_override','POST','/api/order/coupon-preview',400,'error',dict(preview,cash_amount='0.01'))
    add('coupon_preview_anonymous','POST','/api/order/coupon-preview',401,'unauthorized',preview,'anonymous')
    add('coupon_preview_wrong_role','POST','/api/order/coupon-preview',403,'unauthorized',preview,'merchant')
    add('coupon_preview_foreign','POST','/api/order/coupon-preview',404,'unauthorized',dict(preview,vehicle_id=2),'other')
    c.write('first_booking','/api/order/coupon-create',booking,extra=['pm.expect(pm.response.json().data.amount_due).to.eql("7.34");pm.expect(pm.response.json().data.order_id).to.eql(1);'])
    add('coupon_stale_publication','POST','/api/order/coupon-create',409,'error',dict(booking,publication_revision=3))
    add('coupon_changed_intent','POST','/api/order/coupon-create',400,'error',dict(booking,vehicle_id=2),key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:first_booking')))
    add('coupon_already_reserved','POST','/api/order/coupon-create',409,'boundary',booking)
    add('coupon_foreign_create','POST','/api/order/coupon-create',404,'unauthorized',dict(booking,vehicle_id=2),'other')
    c.read('order_reserved','/api/order/1',extra=['pm.expect(pm.response.json().data.coupon_summary.state).to.eql("RESERVED");'],
           boundary=('/api/order/999999',404),error=('/api/order/0',400))
    c.write('cancel_first','/api/order/cancel',{'order_id':1})
    add('first_released','GET','/api/order/1',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("CLOSED");pm.expect(d.coupon_summary.state).to.eql("RELEASED");'])
    c.write('paid_booking','/api/order/coupon-create',booking,extra=[
        'const d=pm.response.json().data;pm.expect(d.order_id).to.eql(2);pm.expect(d.amount_due).to.eql("7.34");pm.collectionVariables.set("paid_order_no",d.order_no);'])
    c.write('payment','/api/payments/create',{'order_id':2,'channel':'LOCAL_TEST'},extra=['pm.expect(pm.response.json().data.test_mode).to.eql(true);'])
    for mode,kind in [('bad_signature','unauthorized'),('expired','boundary'),('bad_body','error')]:c.callback('payment_'+mode,kind,mode)
    c.callback('payment_paid','normal');c.callback('payment_notice_replay','boundary')
    add('coupon_consumed','GET','/api/order/2',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("PAID");pm.expect(d.coupon_summary.state).to.eql("CONSUMED");'])
    used='pm.expect(pm.response.json().data.items.find(i=>i.user_coupon_id===1).status).to.eql("USED");'
    add('coupon_used','GET','/api/coupon/mine',200,'normal',extra=[used])
    c.write('refund_request','/api/order/2/refund',{'reason':'合成全额退款'},extra=[
        'const d=pm.response.json().data.refund;pm.expect(d.refund_id).to.eql(1);pm.expect(d.status).to.eql("REQUESTED");pm.expect(d.amount).to.eql("7.34");pm.collectionVariables.set("refund_no",d.refund_no);'])
    add('refund_request_foreign','POST','/api/order/2/refund',404,'unauthorized',{'reason':'合成全额退款'},'other')
    add('coupon_not_returned_requested','GET','/api/coupon/mine',200,'normal',extra=[used])
    c.read('refund_detail','/api/order/2/refund',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("REQUESTED");'],
           boundary=('/api/order/999999/refund',404),error=('/api/order/0/refund',400))
    add('refund_decision_foreign','POST','/api/merchant/orders/2/refund/decision',404,'unauthorized',{'revision':1,'decision':'ACCEPT'},'other_merchant')
    c.write('refund_decision','/api/merchant/orders/2/refund/decision',{'revision':1,'decision':'ACCEPT'},'merchant',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("PENDING");'])
    add('coupon_not_returned_pending','GET','/api/coupon/mine',200,'normal',extra=[used])
    for mode,kind in [('bad_signature','unauthorized'),('expired','boundary'),('bad_body','error')]:c.callback('refund_'+mode,kind,mode,refund=True)
    c.callback('refund_failed','normal',refund=True,state='FAILED');c.callback('refund_failed_replay','boundary',refund=True,state='FAILED')
    add('refund_failed_detail','GET','/api/order/2/refund',200,'normal',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("FAILED");'])
    add('coupon_not_returned_failed','GET','/api/coupon/mine',200,'normal',extra=[used])
    c.callback('refund_succeeded','normal',refund=True);c.callback('refund_succeeded_replay','boundary',refund=True)
    add('refund_success_detail','GET','/api/order/2/refund',200,'normal',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("SUCCEEDED");'])
    add('coupon_returned','GET','/api/coupon/mine',200,'normal',extra=[
        'const i=pm.response.json().data.items.find(i=>i.user_coupon_id===1);pm.expect(i.status).to.eql("AVAILABLE");pm.expect(i.terms.valid_to).to.eql(pm.collectionVariables.get("original_valid_to"));'])
    add('returned_contract','GET','/api/order/2',200,'normal',extra=['pm.expect(pm.response.json().data.coupon_summary.state).to.eql("RETURNED");'])
    c.write('returned_booking','/api/order/coupon-create',booking,extra=['pm.expect(pm.response.json().data.order_id).to.eql(3);'])
    c.write('cancel_returned','/api/order/cancel',{'order_id':3})

    service=dict(terms,name='合成服务券',type='SERVICE',amount='0.00',min_spend='0.00')
    c.write('service_draft','/api/merchant/coupons',{'terms':service},'merchant',extra=['pm.expect(pm.response.json().data.coupon_id).to.eql(2);'])
    c.write('service_publish','/api/merchant/coupons/2/status',{'expected_revision':1,'status':'PUBLISHED'},'merchant',method='PUT')
    c.write('service_receive','/api/coupon/receive',{'coupon_id':2,'publication_revision':2},extra=['pm.expect(pm.response.json().data.user_coupon_id).to.eql(2);'])
    c.read('service_detail','/api/coupon/2/service-redemption',extra=['pm.expect(pm.response.json().data).not.to.have.property("code");'],
           boundary=('/api/coupon/999999/service-redemption',404),error=('/api/coupon/0/service-redemption',400))
    add('service_detail_foreign','GET','/api/coupon/2/service-redemption',404,'unauthorized',role='other')
    add('cash_cannot_authorize_service','POST','/api/coupon/1/service-authorization',409,'error',{'expected_revision':0})
    c.write('service_authorize','/api/coupon/2/service-authorization',{'expected_revision':0},extra=[
        'const d=pm.response.json().data;pm.expect(d.code).to.match(/^[a-f0-9]{32}$/);pm.expect(d.authorization_revision).to.eql(1);pm.collectionVariables.set("service_code",d.code);'])
    add('service_stale_revision','POST','/api/coupon/2/service-authorization',409,'boundary',{'expected_revision':0})
    auth={'user_coupon_id':2,'authorization_revision':1,'code':'{{service_code}}'}
    add('service_preview','POST','/api/merchant/coupons/service-preview',200,'normal',auth,'merchant',extra=['pm.expect(pm.response.json().data.can_redeem).to.eql(true);'])
    add('service_preview_wrong_code','POST','/api/merchant/coupons/service-preview',422,'boundary',dict(auth,code='0'*32),'merchant')
    add('service_preview_malformed','POST','/api/merchant/coupons/service-preview',400,'error',{},'merchant')
    add('service_preview_foreign_shop','POST','/api/merchant/coupons/service-preview',404,'unauthorized',auth,'other_merchant')
    add('service_preview_anonymous','POST','/api/merchant/coupons/service-preview',401,'unauthorized',auth,'anonymous')
    add('service_preview_wrong_role','POST','/api/merchant/coupons/service-preview',403,'unauthorized',auth)
    add('service_redeem_foreign_shop','POST','/api/merchant/coupons/service-redeem',404,'unauthorized',auth,'other_merchant')
    c.write('service_redeem','/api/merchant/coupons/service-redeem',auth,'merchant',extra=['pm.expect(pm.response.json().data.commission_amount).to.eql("0.00");'])
    add('service_redeem_new_key','POST','/api/merchant/coupons/service-redeem',200,'boundary',auth,'merchant',extra=['pm.expect(pm.response.json().data.commission_amount).to.eql("0.00");'])
    c.read('service_redemptions','/api/merchant/coupons/service-redemptions',role='merchant',extra=[
        'pm.expect(pm.response.json().data.total).to.eql(1);pm.expect(pm.response.text()).not.to.include(pm.collectionVariables.get("service_code"));'],
        boundary=('/api/merchant/coupons/service-redemptions?page=2',200),error=('/api/merchant/coupons/service-redemptions?page_size=101',400))
    add('service_redemptions_other_shop','GET','/api/merchant/coupons/service-redemptions',200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    return c.items


def assets():
    return assemble(cases(),'Autocare isolated cash coupon lifecycle and service redemption','isolated_coupon_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    now=datetime.now(timezone.utc).replace(microsecond=0)
    result.update(isolated_coupon_cases='true',valid_from=(now-timedelta(minutes=1)).isoformat().replace('+00:00','Z'),
                  valid_to=(now+timedelta(days=2)).isoformat().replace('+00:00','Z'))
    return result


def environment(tokens):
    return [(k,v) for k,v in local_environment(tokens) if not k.startswith('SERVICE_ARCHIVE_')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    definitions={x['name']:x for x in manifest['items']};pairs={}
    for execution in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        case=definitions[execution['item']['name']]
        if 'callback_mode' not in case:continue
        request=execution['request'];headers={h['key'].lower():h['value'] for h in request['header'] if not h.get('disabled')}
        timestamp,nonce,signature=(headers[k] for k in ('x-test-timestamp','x-test-nonce','x-test-signature'))
        raw=request['body']['raw'];mode=case['callback_mode']
        wanted=hmac.new(tokens['payment_secret'].encode(),(timestamp+'\n'+nonce+'\n'+raw+'\n').encode(),hashlib.sha256).hexdigest()
        if (mode=='bad_signature')==hmac.compare_digest(signature,wanted):raise ValueError('Coupon notice signature mode differs')
        body=json.loads(raw)
        if mode=='expired' and timestamp!='1':raise ValueError('Expired coupon notice was not expired')
        if mode=='bad_body' and body.get('amount')!='invalid':raise ValueError('Invalid coupon notice was not invalid')
        if mode=='valid':
            if body.get('status')!=case['notice_status']:raise ValueError('Coupon notice outcome differs from declared case')
            if body.get('payment_id')!=1 or body.get('amount')!='7.34' or body.get('currency')!='CNY':raise ValueError('Coupon cash amount/payment intent differs')
            if '/refunds/' in case['path'] and body.get('refund_id')!=1:raise ValueError('Coupon refund intent differs')
            pairs.setdefault((case['path'],body['event_id']),[]).append((raw,timestamp,nonce,signature))
    if len(pairs)!=3 or any(len(p)!=2 or p[0]!=p[1] for p in pairs.values()):raise ValueError('Payment/failed refund/success refund must each replay identical signed bytes')
    result['signed_notice_replay_pairs_verified']=3
    return result


def verify_database(mysql):
    tables={'coupons':'coupon','issued':'coupon_issue','instances':'user_coupon','stock_events':'coupon_stock_event',
            'orders':'order','allocations':'order_coupon_allocation','coupon_events':'coupon_transaction_event',
            'payments':'payment','payment_events':'payment_event','refunds':'refund','refund_events':'refund_event',
            'service_redemptions':'coupon_service_redemption','archives':'vehicle_archive'}
    expected=dict(coupons=2,issued=2,instances=2,stock_events=2,orders=3,allocations=3,coupon_events=8,
                  payments=1,payment_events=1,refunds=1,refund_events=2,service_redemptions=1,archives=0)
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    if actual!=expected:raise ValueError('Coupon lifecycle rows are missing or duplicated')
    checks={
        'cash_available_service_used':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status) ORDER BY id) FROM user_coupon;")=='1:AVAILABLE,2:USED',
        'contracts_preserve_history':mysql.text("SELECT GROUP_CONCAT(CONCAT(order_id,':',state) ORDER BY order_id) FROM order_coupon_allocation;")=='1:RELEASED,2:RETURNED,3:RELEASED',
        'cash_refunded':mysql.text("SELECT CONCAT(status,':',amount) FROM refund WHERE id=1;")=='SUCCEEDED:7.34',
        'single_consume_return':mysql.text("SELECT COUNT(*) FROM coupon_transaction_event WHERE kind IN ('CONSUME','REFUND_RETURN');")=='2',
        'no_validity_extension':mysql.text("SELECT COUNT(*) FROM order_coupon_allocation a JOIN coupon_publication p ON p.coupon_id=a.coupon_id AND p.revision=a.publication_revision WHERE JSON_UNQUOTE(JSON_EXTRACT(a.terms_json,'$.valid_to'))<>JSON_UNQUOTE(JSON_EXTRACT(p.terms_json,'$.valid_to'));")=='0',
        'service_zero_commission':mysql.text('SELECT commission_amount FROM coupon_service_redemption;')=='0.00',
        'authorization_code_not_audited':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='COUPON_SERVICE_AUTHORIZE' AND (after_state LIKE '%\"code\"%' OR before_state LIKE '%\"code\"%');")=='0',
        'no_foreign_transactions':mysql.text('SELECT COUNT(*) FROM `order` WHERE user_id=2 OR merchant_id=2;')=='0',
        'no_reserved_slots':mysql.text('SELECT reserved_count FROM appointment_slot WHERE id=1;')=='0',
    }
    if not all(checks.values()):raise ValueError('Coupon lifecycle state/validity/privacy invariant failed')
    return {'row_counts':actual,'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Coupon API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
