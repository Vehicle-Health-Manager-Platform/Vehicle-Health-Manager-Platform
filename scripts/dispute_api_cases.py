"""Drive pickup dispute handling, owner review and resumed fulfillment over real HTTP."""
import json
import time
import uuid
from coupon_api_cases import Cases
import fulfillment_api_cases as fulfillment
from fulfillment_api_cases import ROOT,assemble

OUTPUT=ROOT/'docs/testing/postman/dispute-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/dispute-case-manifest.json'
OPERATION_COUNT=18
DISPUTE_CHECKS=set('resolved_once four_append_only_records original_received_state one_open_and_resume_transition resolution_confirmed five_dispute_audits eleven_unique_events exact_notification_fanout no_foreign_dispute'.split())


def cases():
    c=Cases();add=c.add
    confirm='/api/check/pickup/confirm';handle='/api/merchant/orders/1/dispute/handle';review='/api/check/pickup/dispute/review'
    opening={'order_id':1,'decision':'DISPUTE','reason':'合成接车异议'}
    c.write('dispute_open',confirm,opening,extra=['pm.expect(pm.response.json().data.status).to.eql("DISPUTED");'])
    add('dispute_open_other_owner','POST',confirm,404,'unauthorized',opening,'other')
    accept={'order_id':1,'decision':'ACCEPT'}
    add('review_before_handling','POST',review,409,'error',accept,extra=['pm.expect(pm.response.json().code).to.eql(43008);'])
    add('assignment_disputed','POST','/api/merchant/orders/1/assign',409,'boundary',{'technician_id':3},'merchant',extra=['pm.expect(pm.response.json().code).to.eql(43007);'])
    add('disputed_order','GET','/api/order/1',200,'normal',extra=['pm.expect(pm.response.json().data.status).to.eql("DISPUTED");'])
    c.write('handle_first',handle,{'note':'x'},'merchant',extra=[
        'const d=pm.response.json().data; pm.expect(d.dispute_status).to.eql("OPEN"); pm.expect(d.order_status).to.eql("DISPUTED"); pm.expect(d.record_count).to.eql(1); pm.expect(d.last_action).to.eql("HANDLE"); pm.expect(d).not.to.have.property("actor_id");'])
    for name,body in [('blank',{'note':' '}),('too_long',{'note':'x'*501}),('injected',{'note':'x','status':'RESOLVED'})]:
        add('handle_'+name,'POST',handle,400,'error',body,'merchant')
    add('handle_other_shop','POST',handle,404,'unauthorized',{'note':'foreign'},'other_merchant')
    add('handle_missing_order','POST','/api/merchant/orders/999999/dispute/handle',404,'boundary',{'note':'missing'},'merchant')
    add('handle_invalid_id','POST','/api/merchant/orders/0/dispute/handle',400,'error',{'note':'invalid'},'merchant')
    add('handle_changed_intent','POST',handle,400,'error',{'note':'different'},'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:handle_first')))
    rejected={'order_id':1,'decision':'REJECT','note':'合成车主要求再次核对'}
    c.write('review_reject',review,rejected,extra=[
        'const d=pm.response.json().data; pm.expect(d.dispute_status).to.eql("OPEN"); pm.expect(d.order_status).to.eql("DISPUTED"); pm.expect(d.owner_confirm).to.eql(2); pm.expect(d.record_count).to.eql(2); pm.expect(d.last_action).to.eql("REJECT");'])
    for name,body in [('reject_without_note',{'order_id':1,'decision':'REJECT'}),('decision',{'order_id':1,'decision':'CONFIRM'}),('too_long',dict(accept,note='x'*501)),('injected',dict(accept,status='RECEIVED'))]:
        add('review_'+name,'POST',review,400,'error',body)
    add('review_other_owner','POST',review,404,'unauthorized',accept,'other')
    add('review_missing_order','POST',review,404,'boundary',dict(accept,order_id=999999))
    add('review_invalid_id','POST',review,400,'error',dict(accept,order_id=0))
    add('review_changed_intent','POST',review,400,'error',accept,key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:review_reject')))
    add('assignment_after_rejection','POST','/api/merchant/orders/1/assign',409,'boundary',{'technician_id':3},'merchant',extra=['pm.expect(pm.response.json().code).to.eql(43007);'])
    c.write('handle_second',handle,{'note':'x'*500},'merchant',extra=['pm.expect(pm.response.json().data.record_count).to.eql(3);'])
    c.write('review_accept',review,accept,extra=[
        'const d=pm.response.json().data; pm.expect(d.dispute_status).to.eql("RESOLVED"); pm.expect(d.order_status).to.eql("RECEIVED"); pm.expect(d.owner_confirm).to.eql(3); pm.expect(d.record_count).to.eql(4); pm.expect(d.last_action).to.eql("ACCEPT");'])
    add('review_already_resolved','POST',review,409,'boundary',accept)
    add('handle_already_resolved','POST',handle,409,'boundary',{'note':'late'},'merchant')
    add('resumed_order','GET','/api/order/1',200,'normal',extra=['pm.expect(pm.response.json().data.status).to.eql("RECEIVED");'])
    items=[]
    for item in fulfillment.cases():
        if item['name']=='confirmation':items.extend(c.items)
        if item['name']=='confirmation' or item['name'].startswith('confirmation_'):continue
        items.append(item)
    return items


def assets():return assemble(cases(),'Autocare isolated dispute and resumed fulfillment','isolated_dispute_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=fulfillment.seed(mysql,secret);result['isolated_dispute_cases']='true';return result


def environment(tokens):return fulfillment.environment(tokens)+[('SERVICE_NOTIFICATION_DELAY_MS','250')]


def audit(path,collection,manifest,tokens):
    result=fulfillment.audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    executions={e['item']['name']:e for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']}
    for item in collection['item']:
        if not (item['name'].startswith(('dispute_open','handle_','review_'))):continue
        expected=item['request'];actual=executions[item['name']]['request']
        headers=lambda request:sorted((h['key'].lower(),h['value']) for h in request['header'] if not h.get('disabled') and h['key'].lower() in ('idempotency-key','content-type'))
        if headers(expected)!=headers(actual) or expected.get('body',{}).get('raw')!=actual.get('body',{}).get('raw'):
            raise ValueError('Dispute request body or original idempotency key was substituted')
    result['dispute_write_intents_verified']=True
    return result


def verify_database(mysql):
    result=fulfillment.verify_database(mysql)
    deadline=time.monotonic()+20
    while mysql.text("SELECT COUNT(*) FROM service_notification_outbox WHERE status='PENDING';")!='0':
        if time.monotonic()>=deadline:raise ValueError('Dispute outbox did not finish')
        time.sleep(.1)
    checks={
        'resolved_once':mysql.text("SELECT COUNT(*) FROM order_dispute WHERE order_id=1 AND status='RESOLVED' AND resolved_by=1 AND resolved_at IS NOT NULL;")=='1',
        'four_append_only_records':mysql.text("SELECT GROUP_CONCAT(CONCAT(action,':',actor_type,':',actor_id) ORDER BY id SEPARATOR ',') FROM order_dispute_record;")=='HANDLE:staff_account:1,REJECT:user:1,HANDLE:staff_account:1,ACCEPT:user:1',
        'original_received_state':mysql.text('SELECT from_status FROM order_dispute WHERE order_id=1;')=='RECEIVED',
        'one_open_and_resume_transition':mysql.text("SELECT GROUP_CONCAT(CONCAT(from_status,':',to_status) ORDER BY id SEPARATOR ',') FROM order_status_transition WHERE from_status='DISPUTED' OR to_status='DISPUTED';")=='RECEIVED:DISPUTED,DISPUTED:RECEIVED',
        'resolution_confirmed':mysql.text('SELECT owner_confirm FROM pickup_check WHERE order_id=1;')=='3',
        'five_dispute_audits':mysql.text("SELECT GROUP_CONCAT(CONCAT(action,':',n) ORDER BY action SEPARATOR ',') FROM (SELECT action,COUNT(*) n FROM audit_log WHERE action IN ('ORDER_PICKUP_DISPUTE','ORDER_DISPUTE_HANDLE','ORDER_DISPUTE_REJECT','ORDER_DISPUTE_RESOLVE') GROUP BY action) counts;")=='ORDER_DISPUTE_HANDLE:2,ORDER_DISPUTE_REJECT:1,ORDER_DISPUTE_RESOLVE:1,ORDER_PICKUP_DISPUTE:1',
        'eleven_unique_events':mysql.text("SELECT CONCAT(COUNT(*),':',COUNT(DISTINCT kind,source_key),':',SUM(status='DONE')) FROM service_notification_outbox;")=='11:11:11',
        'exact_notification_fanout':mysql.text("SELECT GROUP_CONCAT(CONCAT(recipient_type,':',recipient_id,':',n) ORDER BY recipient_type SEPARATOR ',') FROM (SELECT recipient_type,recipient_id,COUNT(*) n FROM notification GROUP BY recipient_type,recipient_id) counts;")=='merchant:1:11,technician:3:4,user:1:11',
        'no_foreign_dispute':mysql.text('SELECT COUNT(*) FROM order_dispute WHERE merchant_id<>1 OR order_id<>1 OR opened_by<>1;')=='0',
    }
    if set(checks)!=DISPUTE_CHECKS or not all(checks.values()):raise ValueError('Dispute recovery invariants failed: '+str(checks))
    result['dispute_checks']=checks;return result


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Dispute API cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
