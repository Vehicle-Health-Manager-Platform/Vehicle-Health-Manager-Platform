"""Human diagnosis HTTP journey; synthetic eligibility, real scheduled local notifications."""
from datetime import datetime,timedelta,timezone
import copy,hashlib,json,uuid
from coupon_api_cases import Cases
from community_api_cases import seed as identity_seed
from fulfillment_api_cases import ROOT,assemble
from invitation_api_cases import environment as isolated_environment
from points_api_cases import audit as body_audit,replay_key

OUTPUT=ROOT/'docs/testing/postman/diagnosis-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/diagnosis-case-manifest.json'
OPERATION_COUNT=20
CAPTURES={'owner_notice':'owner_notices','manager_notice':'manager_notices'}


def cases():
    c=Cases();a=c.add;p='/api/admin/diagnosis/policies';o='/api/owner/diagnosis/cases';d='/api/admin/diagnosis/cases';ctx='/api/owner/diagnosis/context'
    private='for(const k of ["owner_id","vehicle_id","sender_id","openid","phone_hash","plate_no","vin","admission_snapshot"])pm.expect(pm.response.text()).not.to.include("\\\""+k+"\\\"");'
    a('unknown_service','GET',ctx,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.service.state).to.eql("UNKNOWN");pm.expect(d.eligibility.state).to.eql("ELIGIBLE");pm.expect(d.eligibility.service_available).to.eql(false);'])
    policy={'mode':'AVAILABLE','source_note':'SYNTHETIC diagnosis service protocol','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    q={'vehicle_id':1,'policy_id':1,'policy_revision':3,'message':'SYNTHETIC_PRIVATE_DIAGNOSIS','consent':True}
    a('unknown_cannot_submit','POST',o,409,'boundary',dict(q,policy_revision=1))
    c.write('policy_create',p,policy,'manager')
    c.write('policy_update',p+'/1',dict(policy,expected_revision=1,mode='LEAVE_MESSAGE'),'manager',method='PUT')
    a('stale_policy_update','PUT',p+'/1',409,'boundary',dict(policy,expected_revision=1),'manager')
    c.write('policy_publish',p+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'manager',method='PUT')
    c.read('policies',p,'manager',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(p+'?page=1000000',200),error=(p+'?page=0',400))
    a('handler_cannot_manage','GET',p,403,'unauthorized',role='handler')
    c.read('open_context',ctx,extra=['const d=pm.response.json().data;pm.expect(d.service.state).to.eql("LEAVE_MESSAGE");pm.expect(d.eligibility.service_available).to.eql(true);'],boundary=(ctx,200),error=(ctx+'?owner_id=2',400))
    a('other_owner_locked','GET',ctx,200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.eligibility.state).to.eql("LOCKED");'])
    c.read('handlers','/api/admin/diagnosis/handlers','manager',extra=['pm.expect(pm.response.json().data.total).to.eql(2);'],boundary=('/api/admin/diagnosis/handlers?page=2',200),error=('/api/admin/diagnosis/handlers?page_size=21',400))
    c.write('case_create',o,q,extra=['pm.expect(pm.response.json().data).to.eql({case_id:1,revision:1,status:"WAITING"});'])
    a('changed_submission_intent','POST',o,400,'error',dict(q,message='changed'),key=replay_key('case_create'))
    a('wrong_vehicle','POST',o,404,'unauthorized',dict(q,vehicle_id=2))
    a('false_consent','POST',o,400,'error',dict(q,consent=False))
    a('forged_eligibility','POST',o,400,'error',dict(q,eligible=True))
    a('stale_admission','POST',o,409,'boundary',dict(q,policy_revision=2))
    a('other_owner_ineligible','POST',o,409,'unauthorized',dict(q,vehicle_id=2),role='other')
    c.read('owner_cases',o,extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(o+'?page=2',200),error=(o+'?page=0',400))
    c.read('owner_detail',o+'/1',extra=['pm.expect(pm.response.json().data.messages.total).to.eql(1);',private],boundary=(o+'/1?page=2',200),error=(o+'/1?page_size=21',400))
    a('other_cannot_read','GET',o+'/1',404,'unauthorized',role='other')
    c.read('queue',d+'?scope=queue','manager',extra=['pm.expect(pm.response.json().data.total).to.eql(1);pm.expect(pm.response.text()).not.to.include("SYNTHETIC_PRIVATE_DIAGNOSIS");pm.expect(pm.response.text()).not.to.include("vehicle");'],boundary=(d+'?scope=queue&page=2',200),error=(d+'?scope=invalid',400))
    a('manager_cannot_read_body','GET',d+'/1',403,'unauthorized',role='manager')
    a('handler_cannot_queue','GET',d+'?scope=queue',403,'unauthorized',role='handler')
    a('unassigned_handler','GET',d+'/1',404,'unauthorized',role='handler')
    c.write('assign_first',d+'/1/assignment',{'expected_revision':1,'operator_id':2},'manager',method='PUT')
    a('stale_assignment','PUT',d+'/1/assignment',409,'boundary',{'expected_revision':1,'operator_id':3},'manager')
    c.read('handler_detail',d+'/1','handler',extra=['pm.expect(pm.response.json().data.messages.total).to.eql(1);',private],boundary=(d+'/1?page=2',200),error=(d+'/1?page=0',400))
    c.write('reply_first',d+'/1/reply',{'expected_revision':2,'message':'SYNTHETIC_PRIVATE_REPLY_A'},'handler')
    follow={k:v for k,v in q.items() if k!='vehicle_id'};follow.update(expected_revision=3,message='SYNTHETIC_PRIVATE_FOLLOWUP')
    c.write('owner_followup',o+'/1/message',follow)
    c.write('transfer',d+'/1/assignment',{'expected_revision':4,'operator_id':3},'manager',method='PUT')
    a('former_handler_no_body','GET',d+'/1',404,'unauthorized',role='handler')
    a('former_handler_old_receipt_denied','POST',d+'/1/reply',404,'unauthorized',{'expected_revision':2,'message':'SYNTHETIC_PRIVATE_REPLY_A'},'handler',key=replay_key('reply_first'))
    a('former_handler_no_notices','GET','/api/admin/diagnosis/notifications',200,'unauthorized',role='handler',extra=['pm.expect(pm.response.json().data.notifications.total).to.eql(0);'])
    c.write('reply_second',d+'/1/reply',{'expected_revision':5,'message':'SYNTHETIC_PRIVATE_REPLY_B'},'second_handler')
    c.write('owner_close',o+'/1/close',{'expected_revision':6})
    a('closed_reply','POST',d+'/1/reply',409,'boundary',{'expected_revision':7,'message':'cannot reopen'},'second_handler')
    a('closed_followup','POST',o+'/1/message',409,'boundary',dict(follow,expected_revision=7))
    a('closed_assignment','PUT',d+'/1/assignment',409,'boundary',{'expected_revision':7,'operator_id':2},'manager')
    c.write('second_case',o,dict(q,message='SYNTHETIC_SECOND_DIAGNOSIS'))
    c.write('manager_close',d+'/2/close',{'expected_revision':1},'manager')
    a('other_cannot_close','POST',o+'/2/close',404,'unauthorized',{'expected_revision':2},'other')
    c.write('policy_stop',p+'/1/status',{'expected_revision':3,'status':'STOPPED'},'manager',method='PUT')
    a('old_publish_receipt','PUT',p+'/1/status',200,'boundary',{'expected_revision':2,'status':'PUBLISHED'},'manager',key=replay_key('policy_publish'))
    a('stopped_stays_unknown','GET',ctx,200,'boundary',extra=['pm.expect(pm.response.json().data.service.state).to.eql("UNKNOWN");pm.expect(pm.response.json().data.eligibility.service_available).to.eql(false);'])
    a('stopped_no_new_case','POST',o,409,'boundary',q)
    c.write('paused_policy',p,dict(policy,mode='PAUSED'),'manager')
    c.write('paused_publish',p+'/2/status',{'expected_revision':1,'status':'PUBLISHED'},'manager',method='PUT')
    a('paused_context','GET',ctx,200,'boundary',extra=['pm.expect(pm.response.json().data.service.state).to.eql("PAUSED");pm.expect(pm.response.json().data.service.accepting).to.eql(false);'])
    a('paused_no_new_case','POST',o,409,'boundary',dict(q,policy_id=2,policy_revision=2))
    a('history_after_pause','GET',o+'/1',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.case.status).to.eql("CLOSED");pm.expect(d.case.revision).to.eql(7);pm.expect(d.messages.total).to.eql(4);',private])
    for label,path,role,total,target in [('owner','/api/owner/diagnosis/notifications','owner',9,'CASE'),('manager','/api/admin/diagnosis/notifications','manager',9,'QUEUE')]:
        capture=label+'_notice'
        check='const d=pm.response.json().data;pm.expect(d.notifications.total).to.eql('+str(total)+');pm.expect(d.unread_count).to.eql('+str(total)+');pm.expect(d.notifications.items.every(x=>x.target==="'+target+'"&&x.read_at===null)).to.eql(true);pm.collectionVariables.set("'+capture+'",d.notifications.items[0].notification_id);'
        c.read(label+'_notices',path,role,extra=[check,'pm.expect(pm.response.text()).not.to.include("SYNTHETIC_");'],boundary=(path+'?page=2',200),error=(path+'?page_size=21',400))
        read=path+'/{{'+capture+'}}/read'
        c.write(label+'_read',read,{},role,method='PUT',bad={'case_id':1})
        a(label+'_read_new_key','PUT',read,200,'boundary',{},role,extra=['pm.expect(pm.response.json().data).to.eql(JSON.parse(pm.collectionVariables.get("receipt_'+label+'_read")).data);'])
        a(label+'_read_count','GET',path,200,'normal',role=role,extra=['pm.expect(pm.response.json().data.unread_count).to.eql(8);'])
    a('foreign_notice_read','PUT','/api/owner/diagnosis/notifications/{{owner_notice}}/read',404,'unauthorized',{},'other')
    a('handler_cannot_read_manager_notice','PUT','/api/admin/diagnosis/notifications/{{manager_notice}}/read',404,'unauthorized',{},'second_handler')
    for name,path,role in [('context_duplicate',ctx+'?owner_id=1&owner_id=2','owner'),('queue_duplicate',d+'?scope=queue&scope=mine','manager'),('notice_duplicate','/api/owner/diagnosis/notifications?page=1&page=2','owner')]:a(name,'GET',path,400,'error',role=role)
    a('write_query','POST',o+'/1/close?revision=7',400,'error',{'expected_revision':7})
    return c.items


def assets():return assemble(cases(),'Autocare human diagnosis queue and local notifications','isolated_diagnosis_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql('UPDATE operator_account SET can_community_manage=0,can_community_answer=0,can_diagnose_manage=(id=1),can_diagnose=(id IN (2,3));')
    now=datetime.now(timezone.utc).replace(microsecond=0);stamp=now.strftime('%Y-%m-%d %H:%M:%S');month=now.astimezone(timezone(timedelta(hours=8))).strftime('%Y-%m')
    mysql.sql(f"""INSERT INTO user(id,openid,status) VALUES(10,'synthetic-qualification-only',1);
INSERT INTO invite_code(id,code,actor_type,actor_id,created_at) VALUES(101,REPEAT('a',32),'user',1,DATE_SUB('{stamp}',INTERVAL 1 MINUTE));
INSERT INTO invite_registration(user_id,record_id,app_id,identity_source,invite_code_id,source_type,source_id,attribution_result,qualification_status,registered_at,qualified_at,reward_month,rule_version) VALUES(10,'{uuid.uuid4()}','fulfillment-cases','VERIFIED_WECHAT',101,'user',1,'ATTRIBUTED','QUALIFIED','{stamp}','{stamp}','{month}','R6A_INVITE_V1');
INSERT INTO invite_phone_evidence(phone_hash,user_id,app_id,verified_at) VALUES('{hashlib.sha256(b'synthetic-qualification-only').hexdigest()}',10,'fulfillment-cases','{stamp}');
INSERT INTO invite_diagnosis_policy(id,revision,status,required_count,source_note,valid_from,valid_to,created_by,created_at,published_at) VALUES(101,2,'PUBLISHED',1,'SYNTHETIC qualification protocol',DATE_SUB('{stamp}',INTERVAL 1 MINUTE),DATE_ADD('{stamp}',INTERVAL 1 DAY),1,'{stamp}','{stamp}');""")
    result['isolated_diagnosis_cases']='true';return result


def environment(tokens):return isolated_environment(tokens)+[('SERVICE_NOTIFICATION_DELAY_MS','100')]


def audit(path,collection,manifest,tokens):
    # Bind dynamic notification IDs to each recipient's actual earlier list response.
    run=json.loads(path.read_text(encoding='utf-8'))['run'];values={}
    for variable,name in CAPTURES.items():
        rows=[e for e in run['executions'] if e['item']['name']==name]
        if len(rows)!=1:raise ValueError('Missing notification capture')
        try:
            payload=json.loads(bytes(rows[0]['response']['stream']['data']).decode('utf-8'))
            items=payload['data']['notifications']['items'];ids=[i['notification_id'] for i in items]
            if len(ids)!=9 or len(set(ids))!=9 or any(type(i)!=int or i<=0 for i in ids):raise ValueError()
            values[variable]=str(ids[0])
        except (KeyError,TypeError,ValueError):raise ValueError('Invalid recipient notification evidence')
    if values['owner_notice']==values['manager_notice']:raise ValueError('Recipient notice IDs alias')
    # Resolve only declared captures; role, full query, method, status, body and key remain audited.
    collection=copy.deepcopy(collection);manifest=copy.deepcopy(manifest)
    for item in collection['item']:
        for key,value in values.items():item['request']['url']=item['request']['url'].replace('{{'+key+'}}',value)
    for item in manifest['items']:
        for key,value in values.items():item['path']=item['path'].replace('{{'+key+'}}',value)
    result=body_audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    result['notification_ids_bound_to_recipient_responses']=True;return result


def verify_database(mysql):
    tables={'cases':'diagnosis_case','messages':'diagnosis_message','events':'diagnosis_case_event','outbox':'diagnosis_notification_outbox','targets':'diagnosis_notification_target','notifications':'notification','policies':'diagnosis_service_policy','rewards':'invite_reward_batch','points':'point_task_event','payments':'payment'}
    actual={k:int(mysql.text('SELECT COUNT(*) FROM `'+v+'`;')) for k,v in tables.items()}
    if actual!={'cases':2,'messages':5,'events':9,'outbox':9,'targets':24,'notifications':24,'policies':2,'rewards':0,'points':0,'payments':0}:raise ValueError('Diagnosis event/message/notification row counts differ: '+str(actual))
    checks={
        'both_cases_closed':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status,':',revision) ORDER BY id) FROM diagnosis_case;")=='1:CLOSED:7,2:CLOSED:2',
        'outbox_delivered_once':mysql.text("SELECT COUNT(*) FROM diagnosis_notification_outbox WHERE status='DONE' AND completed_at IS NOT NULL AND attempts=0;")=='9',
        'notifications_match_captured_targets':mysql.text("SELECT COUNT(*) FROM diagnosis_notification_target t JOIN diagnosis_notification_outbox e ON e.id=t.outbox_id JOIN notification n ON n.event_id=e.event_id AND n.recipient_type=t.recipient_type AND n.recipient_id=t.recipient_id AND n.template_key=e.kind WHERE n.payload IS NULL;")=='24',
        'only_two_read_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='DIAGNOSIS_NOTIFICATION_READ';")=='2',
        'only_own_and_manager_read':mysql.text("SELECT COUNT(*) FROM notification WHERE read_at IS NOT NULL AND ((recipient_type='user' AND recipient_id=1) OR (recipient_type='operator_account' AND recipient_id=1));")=='2',
        'policies_not_revived':mysql.text("SELECT GROUP_CONCAT(CONCAT(status,':',revision,':',mode) ORDER BY id) FROM diagnosis_service_policy;")=='STOPPED:4:LEAVE_MESSAGE,PUBLISHED:2:PAUSED',
        'frozen_admission':mysql.text("SELECT COUNT(*) FROM diagnosis_case WHERE JSON_EXTRACT(admission_snapshot,'$.service.id')=1 AND JSON_EXTRACT(admission_snapshot,'$.service.revision')=3;")=='2',
        'independent_permissions':mysql.text('SELECT COUNT(*) FROM operator_account WHERE can_diagnose_manage=1 AND can_diagnose=0;')=='1',
        'audit_has_no_private_text':mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%SYNTHETIC_PRIVATE%' OR after_state LIKE '%SYNTHETIC_SECOND%';")=='0',
    }
    if not all(checks.values()):raise ValueError('Diagnosis privacy/receipt/notification invariant failed: '+str(checks))
    return {'row_counts':actual,'checks':checks,'scope':'SQL synthetic identity/monthly eligibility only; HTTP diagnosis writes and real scheduled local notification delivery; no external message supplier'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Diagnosis cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
