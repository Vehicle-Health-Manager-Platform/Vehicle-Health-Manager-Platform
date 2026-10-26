"""Real isolated fulfillment reads and persisted three-role notification fanout."""
import copy
import json
import time
from coupon_api_cases import Cases
import fulfillment_api_cases as fulfillment
from fulfillment_api_cases import ROOT, assemble

OUTPUT=ROOT/'docs/testing/postman/workflow-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/workflow-case-manifest.json'
OPERATION_COUNT=26
KINDS={'ORDER_PAID','ORDER_CHECK_IN','ORDER_ASSIGN','ORDER_TECH_ACCEPT','ORDER_SERVICE_FINISH','ORDER_COMPLETE'}
NOTIFICATION_CHECKS={'six_unique_order_events','outbox_delivered','exact_recipient_fanout','three_read_notifications','three_read_audits','no_foreign_notifications'}


def cases():
    groups={}
    def reads(anchor,name,path,role,boundary,error,extra=(),foreign=None):
        c=Cases();c.read(name,path,role,extra,boundary,error)
        if foreign:c.add(name+'_foreign','GET',path,404,'unauthorized',role=foreign)
        groups.setdefault(anchor,[]).extend(c.items)
    reads('pickup_foreign_merchant','pickup_context','/api/check/pickup/context?order_id=1','merchant',
          ('/api/check/pickup/context?order_id=999999',404),('/api/check/pickup/context?order_id=0',400),
          ['pm.expect(pm.response.json().data.mileage_baseline.mileage).to.eql(100);'],'other_merchant')
    # Owner and merchant can both read pickup sheets. Technician is the denied role.
    c=Cases();c.read('pickup_sheet','/api/check/pickup/1','owner',
        boundary=('/api/check/pickup/999999',404),error=('/api/check/pickup/0',400))
    c.items[-1]['role']='technician'
    c.items[-1]['request']['auth']['bearer'][0]['value']='{{technician_token}}'
    c.add('pickup_sheet_foreign','GET','/api/check/pickup/1',404,'unauthorized',role='other')
    c.add('pickup_sheet_merchant','GET','/api/check/pickup/1',200,'normal',role='merchant')
    groups['assignment_before_confirmation']=c.items
    reads('report_before_protection','technician_order','/api/tech/orders/1','technician',
          ('/api/tech/orders/999999',404),('/api/tech/orders/0',400))
    reads('signature','technician_work','/api/tech/orders/1/work','technician',
          ('/api/tech/orders/999999/work',404),('/api/tech/orders/0/work',400),
          ['pm.expect(pm.response.text()).to.include("合成检查清洁");'])
    items=[]
    for item in fulfillment.cases():
        items.extend(groups.get(item['name'],[]));items.append(item)
    c=Cases()
    for route,role in [('owner','owner'),('merchant','merchant'),('tech','technician')]:
        name=route+'_notifications';path='/api/'+route+'/notifications'
        script='const d=pm.response.json().data; const n=d.notifications.items.find(n=>n.kind==="ORDER_COMPLETE" && n.order_id===1); pm.expect(n).to.exist; pm.expect(n.read_at).to.eql(null); pm.expect(d.unread_count).to.eql('+('4' if route=='tech' else '6')+'); pm.collectionVariables.set("'+route+'_notice_id",n.notification_id);'
        first=len(c.items)
        c.read(name,path,role,extra=[script],boundary=(path+'?page=1000000',200),error=(path+'?page=0',400))
        if route!='tech':c.add(name+'_foreign','GET',path,200,'unauthorized',role='other' if route=='owner' else 'other_merchant',extra=['pm.expect(pm.response.json().data.unread_count).to.eql(0); pm.expect(pm.response.json().data.notifications.total).to.eql(0);'])
        # Let the real scheduled outbox finish; no notification or outbox SQL fixtures.
        c.items[first]['event'].insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':['setTimeout(()=>{},1000);']}})
        read_path=path+'/{{'+route+'_notice_id}}/read'
        c.write(route+'_notice_read',read_path,{},role,method='PUT',bad={'injected':True},extra=[
            'pm.expect(pm.response.json().data.read_at).to.be.a("string");'])
        c.add(route+'_notice_read_new_key','PUT',read_path,200,'boundary',{},role)
        c.add(route+'_notice_read_missing','PUT',path+'/999999/read',404,'boundary',{},role)
        c.add(route+'_notice_read_invalid','PUT',path+'/0/read',400,'error',{},role)
        # A valid notification belonging to another recipient role must remain invisible.
        if route!='owner':c.add(route+'_notice_read_foreign','PUT',path+'/{{owner_notice_id}}/read',404,'unauthorized',{},role)
        c.add(route+'_notifications_after_read','GET',path,200,'normal',role=role,extra=[
            'const d=pm.response.json().data; pm.expect(d.unread_count).to.eql('+('3' if route=='tech' else '5')+'); pm.expect(d.notifications.items.find(n=>n.notification_id===Number(pm.collectionVariables.get("'+route+'_notice_id"))).read_at).to.be.a("string");'])
    return items+c.items


def assets():return assemble(cases(),'Autocare isolated workflow and notification inbox','isolated_workflow_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=fulfillment.seed(mysql,secret);result['isolated_workflow_cases']='true';return result


def environment(tokens):return fulfillment.environment(tokens)+[('SERVICE_NOTIFICATION_DELAY_MS','250')]


def response_json(execution):
    stream=execution['response']['stream']
    return json.loads(bytes(stream['data']).decode('utf-8'))


def audit(path,collection,manifest,tokens):
    run=json.loads(path.read_text(encoding='utf-8'))['run']
    rows={e['item']['name']:e for e in run['executions']}
    replacements={}
    for route in ('owner','merchant','tech'):
        data=response_json(rows[route+'_notifications'])['data']
        expected_unread=4 if route=='tech' else 6
        notices=data['notifications']['items']
        matches=[n for n in notices if n.get('kind')=='ORDER_COMPLETE' and n.get('order_id')==1]
        if len(matches)!=1 or type(matches[0].get('notification_id')) is not int or matches[0]['notification_id']<=0 or matches[0].get('read_at') is not None or data['unread_count']!=expected_unread:
            raise ValueError('Notification ID must come from the actual unread completed-order inbox')
        replacements['{{'+route+'_notice_id}}']=str(matches[0]['notification_id'])
    if len(set(replacements.values()))!=3:raise ValueError('Recipients must have distinct notifications')
    collection=copy.deepcopy(collection);manifest=copy.deepcopy(manifest)
    for definition in collection['item']:
        request=definition['request']
        for variable,value in replacements.items():request['url']=request['url'].replace(variable,value)
        if request['method']=='PUT':
            actual=rows[definition['name']]['request']
            headers=lambda r:[(h['key'].lower(),h['value']) for h in r['header'] if not h.get('disabled') and h['key'].lower() in ('idempotency-key','content-type')]
            if sorted(headers(actual))!=sorted(headers(request)) or actual.get('body',{}).get('raw')!=request['body']['raw']:
                raise ValueError('Notification read key or strict JSON body was substituted')
    for case in manifest['items']:
        for variable,value in replacements.items():case['path']=case['path'].replace(variable,value)
    result=fulfillment.audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    for route in ('owner','merchant','tech'):
        receipt=response_json(rows[route+'_notice_read'])['data']
        if receipt.get('notification_id')!=int(replacements['{{'+route+'_notice_id}}']) or not isinstance(receipt.get('read_at'),str) or not receipt['read_at']:
            raise ValueError('Read receipt differs from actual inbox notification')
        if any(response_json(rows[route+suffix])['data']!=receipt for suffix in ('_notice_read_replay','_notice_read_new_key')):
            raise ValueError('Read replay or new key changed the original read timestamp')
    result['inbox_ids_and_read_receipts_verified']=True
    return result


def verify_database(mysql):
    result=fulfillment.verify_database(mysql)
    deadline=time.monotonic()+20
    while mysql.text("SELECT COUNT(*) FROM service_notification_outbox WHERE status='PENDING';")!='0':
        if time.monotonic()>=deadline:raise ValueError('Notification outbox did not finish')
        time.sleep(.1)
    actual=mysql.text('SELECT kind FROM service_notification_outbox ORDER BY kind;').splitlines()
    fanout=mysql.text("SELECT CONCAT(recipient_type,':',recipient_id,':',COUNT(*)) FROM notification GROUP BY recipient_type,recipient_id ORDER BY recipient_type;").splitlines()
    checks={
        'six_unique_order_events':len(actual)==6 and set(actual)==KINDS,
        'outbox_delivered':mysql.text("SELECT COUNT(*) FROM service_notification_outbox WHERE status='DONE' AND completed_at IS NOT NULL;")=='6',
        'exact_recipient_fanout':fanout==['merchant:1:6','technician:3:4','user:1:6'],
        'three_read_notifications':mysql.text('SELECT COUNT(*) FROM notification WHERE read_at IS NOT NULL;')=='3',
        'three_read_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='NOTIFICATION_READ';")=='3',
        'no_foreign_notifications':mysql.text("SELECT COUNT(*) FROM notification WHERE (recipient_type IN ('user','merchant') AND recipient_id<>1) OR (recipient_type='technician' AND recipient_id<>3);")=='0',
    }
    if set(checks)!=NOTIFICATION_CHECKS or not all(checks.values()):raise ValueError('Notification fanout/read/isolation invariant failed: '+str(checks))
    result['notification_checks']=checks
    return result


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Workflow API cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
