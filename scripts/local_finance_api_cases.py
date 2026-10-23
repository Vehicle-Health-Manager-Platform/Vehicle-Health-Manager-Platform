"""One read-only local finance route: real isolated HTTP, synthetic records, no channel IO."""
import base64
import hashlib
import hmac
import json
import os
import socket
import subprocess
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/local-finance-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/local-finance-case-manifest.json'
OPERATION_COUNT=1
ROUTE='/api/admin/reconciliation'
TABLES={name:'id' for name in ('payment','payment_event','payment_exception','refund','refund_event','order',
    'auth_session','operator_account','audit_log','idempotency_record','merchant_commission_entry',
    'order_status_transition','user','merchant','vehicle')}
CHECKS={'unchanged_'+table for table in TABLES}
BASELINE={}


def cases():
    c=Cases()
    def read(name,date='2026-10-20',channel='LOCAL_TEST',status=200,kind='normal',role='operator',extra=(),query=None):
        query=f'date={date}&channel={channel}' if query is None else query
        checks=list(extra)
        if status==200:
            checks += [
                f'const d=pm.response.json().data;pm.expect(d.date).to.eql("{date}");pm.expect(d.channel).to.eql("{channel}");pm.expect(d.test_mode).to.eql({str(channel=="LOCAL_TEST").lower()});',
                'const d=pm.response.json().data;pm.expect(d.timezone).to.eql("Asia/Shanghai");pm.expect(d.currency).to.eql("CNY");pm.expect(d.source_version).to.eql("R9_LOCAL_FINANCE_V1");',
                'pm.expect(pm.response.json().data.channel_statement).to.eql({state:"UNAVAILABLE",net_amount:null,difference:null});',
                'for(const k of ["user_id","order_id","payment_no","channel_payment_no","phone","openid","session","merchant_id"])pm.expect(pm.response.text()).not.to.include("\\""+k+"\\"");',
            ]
        c.add(name,'GET',ROUTE+'?'+query,status,kind,role=role,extra=checks)
    read('local_daily_exact',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments).to.eql({state:"KNOWN",success_count:2,anomaly_count:0,other_count:1,amount:"0.03"});',
        'const d=pm.response.json().data;pm.expect(d.refunds).to.eql({state:"KNOWN",success_count:1,anomaly_count:0,other_count:0,amount:"0.05"});pm.expect(d.local_net).to.eql({state:"KNOWN",amount:"-0.02"});',
        'const d=pm.response.json().data;pm.expect(d.window_from).to.eql("2026-10-19T16:00:00Z");pm.expect(d.window_to).to.eql("2026-10-20T16:00:00Z");pm.expect(d.unclassified_count).to.eql(0);'])
    read('formal_records_separate',channel='WECHAT',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments.amount).to.eql("12.34");pm.expect(d.payments.success_count).to.eql(1);pm.expect(d.refunds.amount).to.eql("0.00");pm.expect(d.local_net.amount).to.eql("12.34");'])
    read('empty_day_zero',date='2026-10-17',kind='boundary',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments.amount).to.eql("0.00");pm.expect(d.refunds.amount).to.eql("0.00");pm.expect(d.local_net.amount).to.eql("0.00");pm.expect(d.payments.success_count).to.eql(0);'])
    read('broken_sources_unknown',date='2026-10-21',kind='boundary',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments.success_count).to.eql(1);pm.expect(d.payments.anomaly_count).to.eql(2);pm.expect(d.unclassified_count).to.eql(2);',
        'const d=pm.response.json().data;for(const k of ["payments","refunds","local_net"]){pm.expect(d[k].state).to.eql("UNKNOWN");pm.expect(d[k].amount).to.eql(null);}'])
    read('unclassified_affects_formal',date='2026-10-21',channel='WECHAT',kind='boundary',extra=[
        'const d=pm.response.json().data;pm.expect(d.unclassified_count).to.eql(2);pm.expect(d.payments.amount).to.eql(null);pm.expect(d.local_net.amount).to.eql(null);'])
    read('bad_refund_unknown',date='2026-10-22',kind='boundary',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments.amount).to.eql("0.00");pm.expect(d.refunds.anomaly_count).to.eql(1);pm.expect(d.refunds.amount).to.eql(null);pm.expect(d.local_net.amount).to.eql(null);'])
    read('future_day_rejected',date='2099-01-01',status=409,kind='boundary')
    for name,query in [
        ('missing_date','channel=LOCAL_TEST'),('missing_channel','date=2026-10-20'),
        ('invalid_day','date=2026-02-30&channel=LOCAL_TEST'),('short_date','date=2026-1-01&channel=LOCAL_TEST'),
        ('unknown_channel','date=2026-10-20&channel=ALL'),('extra_query','date=2026-10-20&channel=LOCAL_TEST&page=1'),
        ('duplicate_channel','date=2026-10-20&channel=LOCAL_TEST&channel=WECHAT'),
        ('duplicate_date','date=2026-10-20&date=2026-10-21&channel=LOCAL_TEST'),
    ]:read(name,status=400,kind='error',query=query)
    for role,status in [('anonymous',401),('owner',403),('merchant',403),('technician',403),
                        ('review_operator',403),('revoked_operator',401),('disabled_operator',401),('expired_operator',401)]:
        read('finance_denied_'+role,status=status,kind='unauthorized',role=role)
    read('local_daily_repeat',extra=[
        'const d=pm.response.json().data;pm.expect(d.payments.amount).to.eql("0.03");pm.expect(d.local_net.amount).to.eql("-0.02");'])
    return c.items


def assets():return assemble(cases(),'Autocare isolated local finance read-only records','isolated_local_finance_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret);tokens['isolated_local_finance_cases']='true'
    for role,identity,permission,status,revoked,expired in [
        ('operator',1,1,'ACTIVE',False,False),('review_operator',2,0,'ACTIVE',False,False),
        ('revoked_operator',3,1,'ACTIVE',True,False),('disabled_operator',4,1,'DISABLED',False,False),
        ('expired_operator',5,1,'ACTIVE',False,True),
    ]:
        session=str(uuid.uuid4())
        mysql.sql(f"INSERT INTO operator_account(id,account,password_hash,phone,status,can_analytics,can_review) VALUES({identity},'synthetic-finance-{identity}','not-a-login-password','1380000000{identity}','{status}',{permission},1);")
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at,revoked_at) VALUES('{session}','operator_account',{identity},'OPERATOR','operator-account','{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL {'-1' if expired else '1'} HOUR),{'UTC_TIMESTAMP()' if revoked else 'NULL'});")
        now=int(time.time());claims={'iss':'vehicle-health-manager','sub':str(identity),'iat':now-7200 if expired else now,
            'exp':now-3600 if expired else now+3600,'jti':session,'subject_type':'operator_account','role':'OPERATOR','app_id':'operator-account'}
        encode=lambda v:base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        tokens[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    def payment(identity,channel,amount,at,status='SUCCEEDED',event=True,paid=True):
        mysql.sql(f"INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status,created_at) VALUES({identity},'SYNTH-FINANCE-{identity}',1,1,1,1,{amount},{amount},'PAID',DATE_SUB('{at}',INTERVAL 60 SECOND));")
        mysql.sql(f"INSERT INTO payment(id,order_id,channel,channel_payment_no,payment_no,amount,status,paid_at,created_at) VALUES({identity},{identity},'{channel}','synthetic-channel-{identity}','synthetic-payment-{identity}',{amount},'{status}',{repr(at) if paid else 'NULL'},DATE_SUB('{at}',INTERVAL 30 SECOND));")
        if event:mysql.sql(f"INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('{channel}','{uuid.uuid4()}',{identity},'{('a'*64)}','PAID');")
    def refund(identity,payment_id,amount,at,currency='CNY'):
        mysql.sql(f"UPDATE `order` SET status='CLOSED',close_reason='REFUND_CANCELLED' WHERE id={payment_id};")
        mysql.sql(f"INSERT INTO refund(id,order_id,payment_id,amount,currency,status,refunded_at,created_at,owner_consented_at,merchant_consented_at,refund_no,channel_refund_no) VALUES({identity},{payment_id},{payment_id},{amount},'{currency}','SUCCEEDED','{at}',DATE_SUB('{at}',INTERVAL 10 SECOND),DATE_SUB('{at}',INTERVAL 8 SECOND),DATE_SUB('{at}',INTERVAL 4 SECOND),'R{identity}','synthetic-refund-{identity}');")
        mysql.sql(f"INSERT INTO refund_event(channel,event_id,refund_id,event_hash,outcome) VALUES('LOCAL_TEST','{uuid.uuid4()}',{identity},'{('b'*64)}','SUCCEEDED');")
    payment(301,'LOCAL_TEST','0.01','2026-10-19 16:00:00')
    payment(302,'LOCAL_TEST','0.02','2026-10-19 16:00:01')
    payment(303,'LOCAL_TEST','0.05','2026-10-19 15:00:00');refund(303,303,'0.05','2026-10-19 16:05:00')
    payment(304,'WECHAT','12.34','2026-10-19 16:00:02')
    payment(305,'LOCAL_TEST','0.99','2026-10-20 16:00:00')
    payment(306,'LOCAL_TEST','1.00','2026-10-19 16:01:05',status='PENDING',event=False,paid=False)
    payment(307,'LOCAL_TEST','10.00','2026-10-20 16:00:40',event=False)
    payment(308,'LOCAL_TEST','20.00','2026-10-20 16:01:00',paid=False)
    payment(309,'UNVERIFIED','30.00','2026-10-20 16:02:00')
    refund(310,999,'1.00','2026-10-20 16:03:00')
    payment(311,'LOCAL_TEST','1.00','2026-10-18 00:00:00');refund(311,311,'1.00','2026-10-21 16:03:00',currency='USD')
    BASELINE.clear();BASELINE.update(snapshot(mysql));return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def backend_port():
    from run_performance_baseline import free_port
    if os.environ.get('AUTOCARE_FINANCE_NATIVE_QA')!='1':return free_port()
    with socket.socket() as probe:probe.bind(('127.0.0.1',18080))
    return 18080


def verify_ui(backend,tokens,work):
    if os.environ.get('AUTOCARE_FINANCE_NATIVE_QA')!='1':
        return {'executed':False,'passed':False,'reason':'WeChat Devtools not opted in; no device acceptance claimed'}
    if backend.port!=18080:raise ValueError('Native finance backend must be isolated loopback port 18080')
    private=work/'native-finance-sessions.private.json';output=work/'native-finance.json'
    private.write_text(json.dumps({key:tokens[key] for key in ('operator_token','review_operator_token')}),encoding='utf-8')
    try:
        result=subprocess.run(['node',str(ROOT/'scripts/native-finance-e2e.cjs'),str(private),str(output)],
                              cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=240)
        if result.returncode:raise ValueError('Native finance checks failed; inspect sanitized native-finance.json')
        report=json.loads(output.read_text(encoding='utf-8'))
        if report.get('passed') is not True or report.get('session_cleared') is not True:
            raise ValueError('Native finance evidence or identity cleanup incomplete')
        return report
    finally:private.unlink(missing_ok=True)


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    raw=json.loads(path.read_text(encoding='utf-8'))
    for item in collection['item']:
        request=item['request']
        if request.get('method')!='GET' or 'body' in request or any(h['key'].lower()=='idempotency-key' for h in request.get('header',[])):
            raise ValueError('Local finance collection contains a write intent')
    for row in raw['run']['executions']:
        request=row['request']
        if request.get('method')!='GET' or request.get('body') or any(h['key'].lower()=='idempotency-key' for h in request.get('header',[])):
            raise ValueError('Local finance executed a write intent')
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    result['read_only_intents_verified']=True
    return result


def snapshot(mysql):return {table:hashlib.sha256(mysql.text('SELECT * FROM `'+table+'` ORDER BY '+key+';').encode()).hexdigest() for table,key in TABLES.items()}


def verify_database(mysql):
    after=snapshot(mysql);checks={'unchanged_'+table:table in BASELINE and after[table]==BASELINE[table] for table in TABLES}
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Local finance query changed source content')
    return {'checks':checks,'scope':'SQL synthetic identities, local payment/refund facts and events; no official identity or funds, no channel statements'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Local finance API cases:',manifest['cases'],'assertions:',sum(row['assertions'] for row in manifest['items']))
