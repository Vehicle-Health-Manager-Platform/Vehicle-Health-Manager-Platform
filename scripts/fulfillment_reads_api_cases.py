"""Remaining reservation, assignment, work and redemption views over real isolated HTTP."""
import copy
import json
import uuid
from coupon_api_cases import Cases
import fulfillment_api_cases as fulfillment
from fulfillment_api_cases import ROOT,assemble

OUTPUT=ROOT/'docs/testing/postman/fulfillment-reads-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/fulfillment-reads-case-manifest.json'
OPERATION_COUNT=26
READ_CHECKS=set('single_closed_slot closure_audited_once one_published_slot no_foreign_slots accepted_assignment_preserved completed_order_keeps_slot'.split())


def cases():
    groups={}
    def group(anchor):
        c=Cases();groups.setdefault(anchor,[]).append(c);return c
    c=group('quote')
    slots='/api/merchant/slots?date={{slot_date}}'
    c.read('merchant_slots',slots,'merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(slots+'&page=2',200),error=(slots+'&page=0',400))
    c.add('merchant_slots_other_shop','GET',slots,200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    available='/api/order/slots?merchant_id=1&project_id=1&date={{slot_date}}'
    c.read('available_slots',available,extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(available+'&page=2',200),error=(available+'&page=0',400))
    c.add('available_slots_bad_date','GET','/api/order/slots?merchant_id=1&project_id=1&date=invalid',400,'error')
    c=group('owner_order')
    c.read('owner_orders','/api/order/list',extra=['pm.expect(pm.response.json().data.total).to.eql(1);pm.expect(pm.response.json().data.items[0].status).to.eql("PENDING_PAYMENT");'],boundary=('/api/order/list?page=2',200),error=('/api/order/list?status=INVALID',400))
    c.add('owner_orders_other_owner','GET','/api/order/list',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c=group('assignment_foreign_merchant')
    c.read('eligible_technicians','/api/merchant/technicians','merchant',extra=['pm.expect(pm.response.json().data.items.map(x=>x.technician_id)).to.eql([3]);'],boundary=('/api/merchant/technicians?page=2',200),error=('/api/merchant/technicians?page=0',400))
    c.add('eligible_other_shop','GET','/api/merchant/technicians',200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c.add('assignment_not_created','GET','/api/merchant/orders/1/assignment',200,'boundary',role='merchant',extra=['pm.expect(pm.response.json().data.assignment).to.eql(null);'])
    c=group('accept')
    c.read('assignment_detail','/api/merchant/orders/1/assignment','merchant',extra=['const a=pm.response.json().data.assignment;pm.expect(a.technician_id).to.eql(3);pm.expect(a.status).to.eql("ASSIGNED");'],boundary=('/api/merchant/orders/999999/assignment',404),error=('/api/merchant/orders/0/assignment',400))
    c.add('assignment_detail_other_shop','GET','/api/merchant/orders/1/assignment',404,'unauthorized',role='other_merchant')
    c=group('redemption_foreign_merchant')
    c.read('merchant_work','/api/merchant/orders/1/work','merchant',extra=['const d=pm.response.json().data;pm.expect(d.order_status).to.eql("PENDING_VERIFY");pm.expect(d.report.status).to.eql("SIGNED");'],boundary=('/api/merchant/orders/999999/work',404),error=('/api/merchant/orders/0/work',400))
    c.add('merchant_work_other_shop','GET','/api/merchant/orders/1/work',404,'unauthorized',role='other_merchant')
    c.read('owner_work','/api/order/1/work',extra=['const d=pm.response.json().data;pm.expect(d.order_status).to.eql("PENDING_VERIFY");pm.expect(d.order_id).to.eql(1);pm.expect(d.groups).to.be.an("array");'],boundary=('/api/order/999999/work',404),error=('/api/order/0/work',400))
    c.add('owner_work_other_owner','GET','/api/order/1/work',404,'unauthorized',role='other')
    for role,path in [('owner','/api/order/1/redemption'),('merchant','/api/merchant/orders/1/redemption')]:
        c.add(role+'_no_redemption','GET',path,200,'boundary',role=role,extra=['pm.expect(pm.response.json().data.redemption).to.eql(null);'])
    items=[]
    for item in fulfillment.cases():
        for c in groups.get(item['name'],[]):items.extend(c.items)
        items.append(item)
    c=Cases()
    for role,prefix,foreign in [('owner','/api/order','other'),('merchant','/api/merchant/orders','other_merchant')]:
        path=prefix+'/1/redemption'
        c.read(role+'_redemption',path,role,extra=['const d=pm.response.json().data;pm.expect(d.order_id).to.eql(1);pm.expect(d.redemption.test_mode).to.eql(true);pm.expect(d.redemption.redeemed_at).to.be.a("string");'],boundary=(prefix+'/999999/redemption',404),error=(prefix+'/0/redemption',400))
        c.add(role+'_redemption_foreign','GET',path,404,'unauthorized',role=foreign)
    close='/api/merchant/slots/1/close'
    c.write('slot_close',close,{},'merchant',bad={'injected':True},extra=['pm.expect(pm.response.json().data.open).to.eql(false);'])
    c.add('slot_close_other_shop','POST',close,404,'unauthorized',{},'other_merchant')
    c.add('slot_close_missing','POST','/api/merchant/slots/999999/close',404,'boundary',{},'merchant')
    c.add('slot_close_invalid','POST','/api/merchant/slots/0/close',400,'error',{},'merchant')
    c.add('slot_close_changed_intent','POST',close,400,'error',{'injected':True},'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:slot_close')))
    c.add('closed_slots_unavailable','GET',available,200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c.add('completed_order_list','GET','/api/order/list?status=COMPLETED',200,'normal',extra=['pm.expect(pm.response.json().data.total).to.eql(1);pm.expect(pm.response.json().data.items[0].status).to.eql("COMPLETED");'])
    items+=c.items
    for item in items:
        if item['name'] in ('merchant_slots_boundary','available_slots_boundary','owner_orders_boundary','eligible_technicians_boundary'):
            tests=next(e['script']['exec'] for e in item['event'] if e['listen']=='test')
            tests.append('pm.test("empty next page",()=>pm.expect(pm.response.json().data.items).to.have.lengthOf(0));')
            item['assertions']+=1
    return items


def assets():return assemble(cases(),'Autocare isolated fulfillment views and slot closure','isolated_fulfillment_reads_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=fulfillment.seed(mysql,secret);tokens['isolated_fulfillment_reads_cases']='true';tokens['slot_date']=tokens['slot_start'][:10];return tokens


def environment(tokens):return fulfillment.environment(tokens)


def audit(path,collection,manifest,tokens):
    collection=copy.deepcopy(collection)
    for item in collection['item']:item['request']['url']=item['request']['url'].replace('{{slot_date}}',tokens['slot_date'])
    result=fulfillment.audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    executions={e['item']['name']:e for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']}
    for item in collection['item']:
        if not item['name'].startswith('slot_close'):continue
        expected=item['request'];actual=executions[item['name']]['request']
        headers=lambda r:sorted((h['key'].lower(),h['value']) for h in r['header'] if not h.get('disabled') and h['key'].lower() in ('idempotency-key','content-type'))
        if headers(expected)!=headers(actual) or expected['body']['raw']!=actual.get('body',{}).get('raw'):
            raise ValueError('Slot closure body or original key was substituted')
    result['slot_close_intent_verified']=True;result['slot_date_query_verified']=True;return result


def verify_database(mysql):
    result=fulfillment.verify_database(mysql)
    checks={
        'single_closed_slot':mysql.text('SELECT COUNT(*) FROM appointment_slot WHERE id=1 AND is_open=0 AND closed_at IS NOT NULL;')=='1',
        'closure_audited_once':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='SLOT_CLOSE';")=='1',
        'one_published_slot':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='SLOT_PUBLISH';")=='1',
        'no_foreign_slots':mysql.text('SELECT COUNT(*) FROM appointment_slot WHERE merchant_id<>1 OR id<>1;')=='0',
        'accepted_assignment_preserved':mysql.text("SELECT COUNT(*) FROM technician_assignment WHERE order_id=1 AND technician_id=3 AND status='ACCEPTED' AND accepted_at IS NOT NULL;")=='1',
        'completed_order_keeps_slot':mysql.text("SELECT COUNT(*) FROM `order` WHERE id=1 AND slot_id=1 AND status='COMPLETED';")=='1',
    }
    if set(checks)!=READ_CHECKS or not all(checks.values()):raise ValueError('Fulfillment read/closure final invariants failed: '+str(checks))
    result['read_checks']=checks;return result


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Fulfillment read cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
