"""Eight scoped operations: six financial gaps plus two existing refund write supports."""
import base64
import hashlib
import hmac
import json
import time
import uuid

from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed, environment as local_environment

OUTPUT=ROOT/'docs/testing/postman/finance-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/finance-case-manifest.json'
OPERATION_COUNT=8


def cases():
    c=Cases();a=c.add
    c.read('payment_detail','/api/payments/1',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("PENDING");pm.expect(d.test_mode).to.eql(true);pm.expect(d.amount).to.eql("12.34");'],boundary=('/api/payments/999999',404),error=('/api/payments/0',400))
    a('payment_other_owner','GET','/api/payments/1',404,'unauthorized',role='other')
    a('payment_detail_foreign','GET','/api/payments/6',404,'unauthorized')
    a('payment_recover','POST','/api/payments/1/reconcile',200,'normal',{},extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("SUCCEEDED");pm.expect(d.paid_at).to.be.a("string");pm.expect(d.requires_review).to.eql(false);'])
    a('payment_terminal_repeat','POST','/api/payments/1/reconcile',200,'boundary',{},extra=['pm.expect(pm.response.json().data.status).to.eql("SUCCEEDED");'])
    a('payment_recover_injected_status','POST','/api/payments/1/reconcile',400,'error',{'status':'SUCCEEDED'})
    a('payment_recover_anonymous','POST','/api/payments/1/reconcile',401,'unauthorized',{},'anonymous')
    a('payment_recover_wrong_role','POST','/api/payments/1/reconcile',403,'unauthorized',{},'merchant')
    a('payment_recover_foreign','POST','/api/payments/6/reconcile',404,'unauthorized',{})
    a('payment_bad_receipt','POST','/api/payments/4/reconcile',200,'boundary',{},extra=['pm.expect(pm.response.json().data.status).to.eql("PENDING");pm.expect(pm.response.json().data.paid_at).to.eql(null);'])
    a('payment_query_lease','POST','/api/payments/4/reconcile',200,'boundary',{},extra=['pm.expect(pm.response.json().data.status).to.eql("PENDING");'])
    a('payment_formal_unconfigured','POST','/api/payments/5/reconcile',503,'error',{})
    a('payment_missing_recover','POST','/api/payments/999999/reconcile',404,'boundary',{})
    a('payment_bad_id','POST','/api/payments/0/reconcile',400,'error',{})

    c.read('refund_merchant_detail','/api/merchant/orders/2/refund','merchant',extra=['pm.expect(pm.response.json().data.refund).to.eql(null);'],boundary=('/api/merchant/orders/999999/refund',404),error=('/api/merchant/orders/0/refund',400))
    a('refund_detail_other_shop','GET','/api/merchant/orders/2/refund',404,'unauthorized',role='other_merchant')
    a('refund_detail_staff','GET','/api/merchant/orders/2/refund',403,'unauthorized',role='staff')
    c.write('refund_apply','/api/order/2/refund',{'reason':'合成退款申请'},extra=['pm.expect(pm.response.json().data.refund.status).to.eql("REQUESTED");pm.expect(pm.response.json().data.refund.revision).to.eql(1);'])
    a('refund_apply_foreign','POST','/api/order/2/refund',404,'unauthorized',{'reason':'合成退款申请'},'other')
    a('withdraw_stale','POST','/api/order/2/refund/withdraw',409,'error',{'revision':2})
    c.write('withdraw','/api/order/2/refund/withdraw',{'revision':1},extra=['const d=pm.response.json().data;pm.expect(d.refund.status).to.eql("WITHDRAWN");pm.expect(d.refund.revision).to.eql(2);pm.expect(d.order_status).to.eql("PAID");pm.expect(d.can_request).to.eql(true);'])
    a('withdraw_other_owner','POST','/api/order/2/refund/withdraw',404,'unauthorized',{'revision':2},'other')
    a('withdraw_new_key_after_withdraw','POST','/api/order/2/refund/withdraw',409,'boundary',{'revision':1})
    a('refund_reapply','POST','/api/order/2/refund',200,'normal',{'reason':'确认全额取消'},extra=['pm.expect(pm.response.json().data.refund.revision).to.eql(3);'])
    a('withdraw_old_version','POST','/api/order/2/refund/withdraw',409,'boundary',{'revision':1})
    a('refund_current_detail','GET','/api/merchant/orders/2/refund',200,'normal',role='merchant',extra=['pm.expect(pm.response.json().data.refund.revision).to.eql(3);'])
    a('decision_bad_revision','POST','/api/merchant/orders/2/refund/decision',409,'error',{'revision':1,'decision':'ACCEPT'},'merchant')
    a('decision_wrong_role','POST','/api/merchant/orders/2/refund/decision',403,'unauthorized',{'revision':3,'decision':'ACCEPT'})
    a('decision_anonymous','POST','/api/merchant/orders/2/refund/decision',401,'unauthorized',{'revision':3,'decision':'ACCEPT'},'anonymous')
    a('decision_other_shop','POST','/api/merchant/orders/2/refund/decision',404,'unauthorized',{'revision':3,'decision':'ACCEPT'},'other_merchant')
    c.write('refund_accept','/api/merchant/orders/2/refund/decision',{'revision':3,'decision':'ACCEPT'},'merchant',extra=['const d=pm.response.json().data;pm.expect(d.order_status).to.eql("CLOSED");pm.expect(d.refund.status).to.eql("PENDING");pm.expect(d.refund.test_mode).to.eql(true);'])
    a('withdraw_approved','POST','/api/order/2/refund/withdraw',409,'boundary',{'revision':4})
    a('refund_reconcile_client_state','POST','/api/merchant/orders/2/refund/reconcile',400,'error',{'status':'SUCCEEDED'},'merchant')
    a('refund_reconcile_anonymous','POST','/api/merchant/orders/2/refund/reconcile',401,'unauthorized',{},'anonymous')
    a('refund_reconcile_owner','POST','/api/merchant/orders/2/refund/reconcile',403,'unauthorized',{})
    a('refund_reconcile_staff','POST','/api/merchant/orders/2/refund/reconcile',403,'unauthorized',{},'staff')
    a('refund_reconcile_foreign','POST','/api/merchant/orders/2/refund/reconcile',404,'unauthorized',{},'other_merchant')
    a('refund_reconcile_missing','POST','/api/merchant/orders/999999/refund/reconcile',404,'boundary',{},'merchant')
    a('refund_reconcile','POST','/api/merchant/orders/2/refund/reconcile',200,'normal',{},'merchant',extra=['const d=pm.response.json().data;pm.expect(d.order_status).to.eql("CLOSED");pm.expect(d.refund.status).to.eql("SUCCEEDED");pm.expect(d.refund.amount).to.eql("12.34");pm.expect(d.refund.refunded_at).to.be.a("string");'])
    a('refund_terminal_repeat','POST','/api/merchant/orders/2/refund/reconcile',200,'boundary',{},'merchant',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("SUCCEEDED");'])
    a('refund_failed_recover','POST','/api/merchant/orders/3/refund/reconcile',200,'normal',{},'merchant',extra=['pm.expect(pm.response.json().data.refund.status).to.eql("SUCCEEDED");pm.expect(pm.response.json().data.refund.amount).to.eql("20.00");'])
    a('refund_failed_repeat','POST','/api/merchant/orders/3/refund/reconcile',200,'boundary',{},'merchant')
    c.read('commissions','/api/merchant/commissions','merchant',extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.summary.real.net).to.eql("0.00");pm.expect(d.summary.test.accrued).to.eql("0.60");pm.expect(d.summary.test.reversed).to.eql("-0.60");pm.expect(d.summary.test.net).to.eql("0.00");pm.expect(d.items.every(x=>x.test_mode===true)).to.eql(true);pm.expect(d.items.some(x=>x.kind==="REFUND_REVERSAL"&&x.amount==="-0.60")).to.eql(true);'],boundary=('/api/merchant/commissions?page=2&page_size=50',200),error=('/api/merchant/commissions?page=0',400))
    a('commissions_staff','GET','/api/merchant/commissions',403,'unauthorized',role='staff')
    a('commissions_other_shop','GET','/api/merchant/commissions',200,'unauthorized',role='other_merchant',extra=['const d=pm.response.json().data;pm.expect(d.merchant_id).to.eql(2);pm.expect(d.total).to.eql(0);pm.expect(d.summary.test.net).to.eql("0.00");'])
    for suffix,query in [('unknown','owner_id=1'),('duplicate','page=1&page=2'),('size','page_size=51')]:
        a('commissions_'+suffix,'GET','/api/merchant/commissions?'+query,400,'error',role='merchant')
    # Reconciliation intentionally has no client-controlled transaction, status, amount or idempotency key.
    for item in c.items:
        if item['request']['url'].endswith('/reconcile'):
            item['request']['header']=[h for h in item['request']['header'] if h['key']!='Idempotency-Key']
    return c.items


def assets():
    return assemble(cases(),'Autocare isolated financial recovery LOCAL_TEST','isolated_finance_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret)
    mysql.sql("""INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity,reserved_count) VALUES
(1,1,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 25 HOUR),10,4),
(2,2,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 25 HOUR),10,1);""")
    for i in range(1,7):
        owner,shop,slot=(2,2,2) if i==6 else (1,1,1)
        amount='20.00' if i==3 else '12.34'
        status='PAID' if i==2 else 'CLOSED' if i==3 else 'PENDING_PAYMENT'
        close="'REFUND_CANCELLED'" if i==3 else 'NULL'
        paid=i in (2,3)
        mysql.sql(f"INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,slot_id,amount,pay_amount,status,close_reason,appointment_at,expires_at) VALUES({i},'SYNTHFINANCE000{i}',{owner},{owner},{shop},1,{slot},{amount},{amount},'{status}',{close},DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        transaction=f"'synthetic-finance-paid{i}'" if paid else 'NULL'
        date='UTC_TIMESTAMP()' if paid else 'NULL'
        mysql.sql(f"INSERT INTO payment(id,order_id,channel,payment_no,channel_payment_no,amount,currency,status,paid_at,expires_at) VALUES({i},{i},'{('WECHAT' if i==5 else 'LOCAL_TEST')}','P{str(i)*32}',{transaction},{amount},'CNY','{('SUCCEEDED' if paid else 'PENDING')}',{date},DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        if paid:mysql.sql(f"INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST','{uuid.uuid5(uuid.NAMESPACE_URL,'finance-paid:'+str(i))}',{i},REPEAT('a',64),'PAID');")
    mysql.sql("""INSERT INTO refund(id,order_id,payment_id,refund_no,amount,currency,status,revision,from_status,reason,owner_consented_at,merchant_consented_at,channel_refund_no)
VALUES(10,3,3,CONCAT('R',REPEAT('3',32)),20.00,'CNY','FAILED',2,'PAID','synthetic failed refund',UTC_TIMESTAMP(),UTC_TIMESTAMP(),'synthetic-finance-refund3');
INSERT INTO refund_event(channel,event_id,refund_id,event_hash,outcome) VALUES('LOCAL_TEST','33333333-3333-4333-8333-333333333333',10,REPEAT('b',64),'FAILED');
INSERT INTO order_commission_snapshot(order_id,merchant_id,policy_id,booked_at,base_amount,rate_percent,waived_until,state,exemption,commission_amount,rule_version)
SELECT 3,1,1,created_at,20.00,3.00,DATE_SUB(created_at,INTERVAL 1 DAY),'READY','NONE',0.60,'R2C_V1' FROM `order` WHERE id=3;
INSERT INTO merchant_commission_entry(id,merchant_id,order_id,payment_id,event_key,kind,base_amount,rate_percent,exemption,amount,occurred_at,test_mode,rule_version)
VALUES(1,1,3,3,'COMPLETION','ACCRUAL',20.00,3.00,'NONE',0.60,UTC_TIMESTAMP(),1,'R2C_V1');
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(4,1,'STAFF','synthetic-finance-staff','ACTIVE');""")
    session=str(uuid.uuid4())
    mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES('{session}','staff_account',4,'STAFF','merchant-account',1,'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
    claims={'iss':'vehicle-health-manager','sub':'4','iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':'staff_account','role':'STAFF','app_id':'merchant-account','merchant_id':1}
    encode=lambda v:base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=').decode()
    raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
    tokens['staff_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    tokens['isolated_finance_cases']='true'
    return tokens


def environment(tokens):
    return [(k,v) for k,v in local_environment(tokens) if k not in ('SERVICE_ARCHIVE_ENABLED','SERVICE_ARCHIVE_INTERVAL_MS')]+[
        ('PAYMENT_LOCAL_TEST_QUERY_URL','http://127.0.0.1:18091'),('PAYMENT_QUERY_RECOVERY_ENABLED','false'),('REFUND_RECOVERY_ENABLED','false')]


def prepare_backend(backend):
    from run_performance_baseline import Docker
    backend.guard()
    Docker.run(['exec','--detach','--env','JAVA_TOOL_OPTIONS=-Xms32m -Xmx128m',backend.id,'java','/workspace/scripts/FinanceTestSupplier.java'])
    for _ in range(12):
        result=Docker.run(['exec','--env','JAVA_TOOL_OPTIONS=-Xms32m -Xmx128m',backend.id,'java','/workspace/scripts/FinanceTestSupplier.java','--health'],allow_failure=True)
        if result.returncode==0 and json.loads(result.stdout).get('status')=='UP':return
        time.sleep(.5)
    raise ValueError('Isolated financial supplier did not start')


def verify_supplier(backend):
    from run_performance_baseline import Docker
    backend.guard()
    stats=json.loads(Docker.run(['exec','--env','JAVA_TOOL_OPTIONS=-Xms32m -Xmx128m',backend.id,'java','/workspace/scripts/FinanceTestSupplier.java','--stats']).stdout)
    expected={'payment_1_queries':1,'payment_4_queries':1,'fresh_refund_queries':1,'fresh_refund_posts':1,'failed_refund_queries':1,'failed_refund_posts':0,'invalid_submissions':0}
    if stats!=expected:raise ValueError('Supplier query/submit count or signed intent differs')
    return {'passed':True,'counts':stats,'boundary':'loopback synthetic signed protocol; not WeChat or real funds'}


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    requests={i['name']:i['request'] for i in collection['item']}
    for x in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        expected=requests[x['item']['name']];actual=x['request']
        if expected.get('body') and actual.get('body',{}).get('raw')!=expected['body']['raw']:raise ValueError('Financial body changed')
        keys=lambda request:[h['value'] for h in request.get('header',[]) if not h.get('disabled') and h['key'].lower()=='idempotency-key']
        if keys(actual)!=keys(expected):raise ValueError('Financial idempotency key changed')
    return result


def verify_database(mysql):
    checks={
        'payment_recovered_once':mysql.text("SELECT CONCAT(status,':',query_attempts,':',query_token IS NULL) FROM payment WHERE id=1;")=='SUCCEEDED:1:1',
        'invalid_amount_not_paid':mysql.text("SELECT CONCAT(status,':',query_attempts,':',last_query_error) FROM payment WHERE id=4;")=='PENDING:1:INVALID_RESULT',
        'one_recovered_payment_event':mysql.text("SELECT COUNT(*) FROM payment_event WHERE payment_id=1 AND outcome='PAID';")=='1',
        'fresh_refund_success':mysql.text("SELECT CONCAT(status,':',revision,':',amount,':',attempts) FROM refund WHERE order_id=2;")=='SUCCEEDED:4:12.34:1',
        'failed_refund_success':mysql.text("SELECT CONCAT(status,':',amount,':',attempts) FROM refund WHERE order_id=3;")=='SUCCEEDED:20.00:1',
        'unique_refunds':mysql.text('SELECT COUNT(*) FROM refund;')=='2',
        'two_success_events':mysql.text("SELECT COUNT(*) FROM refund_event WHERE outcome='SUCCEEDED';")=='2',
        'commission_reversed_once':mysql.text("SELECT CONCAT(COUNT(*),':',SUM(amount)) FROM merchant_commission_entry WHERE kind='REFUND_REVERSAL';")=='1:-0.60',
        'uncompleted_refund_no_charge':mysql.text("SELECT CONCAT(COUNT(*),':',SUM(amount)) FROM merchant_commission_entry WHERE kind='REFUND_NO_CHARGE';")=='1:0.00',
        'commission_test_only':mysql.text('SELECT CONCAT(COUNT(*),\':\',SUM(test_mode=1)) FROM merchant_commission_entry;')=='3:3',
        'foreign_payment_unchanged':mysql.text('SELECT CONCAT(status,\':\',query_attempts) FROM payment WHERE id=6;')=='PENDING:0',
        'foreign_shop_no_commission':mysql.text('SELECT COUNT(*) FROM merchant_commission_entry WHERE merchant_id=2;')=='0',
        'lease_cleared':mysql.text('SELECT COUNT(*) FROM refund WHERE dispatch_token IS NOT NULL;')=='0',
        'appointment_capacity_released':mysql.text('SELECT reserved_count FROM appointment_slot WHERE id=1;')=='3',
    }
    if not all(checks.values()):raise ValueError('Financial database invariant failed: '+','.join(k for k,v in checks.items() if not v))
    return {'checks':checks,'boundary':'Synthetic identity/order/payment/failed-refund/accrual fixtures; recovery and current refund writes via HTTP'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Finance cases:',manifest['cases'],'operations:',manifest['operations'])
