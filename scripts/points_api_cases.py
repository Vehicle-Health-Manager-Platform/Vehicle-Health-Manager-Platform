"""Synthetic identity fixtures, real rule/check-in/recovery HTTP and isolated verified ledger."""
from datetime import datetime, timedelta, timezone
import json
import uuid
from coupon_api_cases import Cases
from community_api_cases import seed as identity_seed
from fulfillment_api_cases import ROOT, assemble

OUTPUT=ROOT/'docs/testing/postman/points-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/points-case-manifest.json'
OPERATION_COUNT=8
POLICIES='/api/admin/point/task-policies'


def replay_key(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:'+name))


def cases():
    c=Cases();a=c.add;p=POLICIES
    private='for(const k of ["identity_session","submission_key","submission_hash","creation_result","source_hash","refresh_hash"])pm.expect(pm.response.text()).not.to.include(k);'
    base={'task_type':'CHECK_IN','points':5,'source_note':'SYNTHETIC protocol fixture, not production reward policy','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.read('empty_policies',p,'points',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],boundary=(p+'?page=1000000',200),error=(p+'?page=0',400))
    a('reviewer_cannot_manage','GET',p,403,'unauthorized',role='reviewer')
    c.read('empty_tasks','/api/point/tasks',extra=['const d=pm.response.json().data;pm.expect(d.tasks.map(t=>t.state)).to.eql(["UNKNOWN","UNKNOWN","UNKNOWN"]);pm.expect(d.timezone).to.eql("Asia/Shanghai");'],boundary=('/api/point/tasks',200),error=('/api/point/tasks?user_id=2',400))
    c.read('empty_overview','/api/point/overview',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(0);pm.expect(d.legacy_state).to.eql("REVIEW_REQUIRED");pm.expect(d.balance_scope).to.eql("VERIFIED_TASKS_V1");pm.expect(d.earning_available).to.eql(true);',private],boundary=('/api/point/overview',200),error=('/api/point/overview?user_id=2',400))
    a('unverified_overview','GET','/api/point/overview',200,'boundary',role='other',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(0);pm.expect(d.earning_available).to.eql(false);pm.expect(d.legacy_state).to.eql("NONE");'])
    c.write('checkin_policy',p,base,'points',extra=['pm.expect(pm.response.json().data.id).to.eql(1);',private])
    for name,body in [('zero_points',dict(base,points=0)),('overflow_points',dict(base,points=1000001)),('fraction_points',dict(base,points=1.5)),('invalid_task',dict(base,task_type='CLIENT_CLAIM')),('extra_privilege',dict(base,can_points=True)),('invalid_period',dict(base,valid_from='{{valid_to}}'))]:
        a(name,'POST',p,400,'error',body,'points')
    changed=dict(base,points=7,expected_revision=1)
    c.write('update_policy',p+'/1',changed,'points',method='PUT')
    a('stale_update','PUT',p+'/1',409,'boundary',changed,'points')
    a('missing_update','PUT',p+'/999999',404,'boundary',changed,'points')
    c.write('publish_policy',p+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'points',method='PUT')
    a('published_immutable','PUT',p+'/1',409,'boundary',dict(changed,expected_revision=3),'points')
    c.write('future_archive_policy',p,dict(base,task_type='ARCHIVE_FIRST_COMPLETE',points=9,valid_from='{{future_from}}'),'points')
    c.write('publish_archive',p+'/2/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    c.write('ai_policy',p,dict(base,task_type='AI_FIRST_SUCCESS',points=13),'points')
    c.write('publish_ai',p+'/3/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    c.write('replacement_draft',p,dict(base,points=11),'points')
    a('one_published_rule_per_task','PUT',p+'/4/status',409,'boundary',{'expected_revision':1,'status':'PUBLISHED'},'points')
    a('configured_tasks','GET','/api/point/tasks',200,'normal',extra=['const t=pm.response.json().data.tasks;pm.expect(t.map(x=>x.state)).to.eql(["CONFIGURED","NOT_STARTED","CONFIGURED"]);pm.expect(t[0].policy.points).to.eql(7);pm.expect(t[0].policy.revision).to.eql(3);',private])
    a('identity_and_source_states','GET','/api/point/overview',200,'normal',extra=['const t=pm.response.json().data.tasks;pm.expect(t.map(x=>x.state)).to.eql(["READY","NOT_STARTED","SOURCE_UNAVAILABLE"]);'])
    a('unverified_tasks','GET','/api/point/overview',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.tasks[0].state).to.eql("IDENTITY_UNVERIFIED");'])
    check={'day':'{{point_day}}','policy_id':1,'policy_revision':3}
    for name,body,status,role in [('yesterday',dict(check,day='{{yesterday}}'),409,'owner'),('invalid_date',dict(check,day='2026-02-30'),400,'owner'),('wrong_revision',dict(check,policy_revision=2),409,'owner'),('wrong_policy',dict(check,policy_id=2),409,'owner'),('client_points',dict(check,points=99999),400,'owner'),('identity_unverified',check,409,'other')]:
        a(name,'POST','/api/point/check-in',status,'boundary' if status==409 else 'error',body,role)
    c.write('checkin','/api/point/check-in',check,extra=['const d=pm.response.json().data;pm.expect(d.points).to.eql(7);pm.expect(d.event_id).to.eql(1);pm.expect(d.day).to.eql(pm.environment.get("point_day"));',private])
    a('changed_checkin_same_key','POST','/api/point/check-in',400,'error',dict(check,policy_revision=4),key=replay_key('checkin'))
    a('same_day_new_key','POST','/api/point/check-in',200,'boundary',check,extra=['pm.expect(pm.response.json().data.event_id).to.eql(1);pm.expect(pm.response.json().data.points).to.eql(7);'])
    a('credited_overview','GET','/api/point/overview',200,'normal',prerequest=['setTimeout(()=>{},1200);'],extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(7);pm.expect(d.tasks[0].state).to.eql("CREDITED");pm.expect(d.tasks[0].points_awarded).to.eql(7);pm.expect(d.tasks[0].policy.revision).to.eql(3);',private])
    c.read('flow','/api/point/flow',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items).to.have.lengthOf(1);const f=d.items[0];pm.expect(f.delta).to.eql(7);pm.expect(f.balance_after).to.eql(7);pm.expect(f.task_type).to.eql("CHECK_IN");pm.expect(f.policy_revision).to.eql(3);',private],boundary=('/api/point/flow?page=1000000',200),error=('/api/point/flow?page=0',400))
    a('other_flow_empty','GET','/api/point/flow',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);pm.expect(pm.response.json().data.items).to.eql([]);'])
    c.write('stop_policy',p+'/1/status',{'expected_revision':3,'status':'STOPPED'},'points',method='PUT')
    a('old_publish_receipt','PUT',p+'/1/status',200,'boundary',{'expected_revision':2,'status':'PUBLISHED'},'points',key=replay_key('publish_policy'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_publish_policy")));'])
    a('stopped_republish','PUT',p+'/1/status',409,'boundary',{'expected_revision':4,'status':'PUBLISHED'},'points')
    a('stopped_new_checkin','POST','/api/point/check-in',409,'boundary',check)
    a('old_checkin_receipt','POST','/api/point/check-in',200,'boundary',check,key=replay_key('checkin'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_checkin")));'])
    c.write('publish_replacement',p+'/4/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    a('replacement_no_second_reward','POST','/api/point/check-in',200,'boundary',dict(check,policy_id=4,policy_revision=2),extra=['const d=pm.response.json().data;pm.expect(d.points).to.eql(7);pm.expect(d.event_id).to.eql(1);'])
    a('frozen_award_overview','GET','/api/point/overview',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(7);pm.expect(d.tasks[0].policy.id).to.eql(1);pm.expect(d.tasks[0].policy.points).to.eql(7);'])
    a('new_rule_visible','GET','/api/point/tasks',200,'boundary',extra=['const p=pm.response.json().data.tasks[0].policy;pm.expect(p.id).to.eql(4);pm.expect(p.points).to.eql(11);'])
    a('final_policies','GET',p,200,'normal',role='points',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(4);pm.expect(d.items.find(x=>x.id===1).status).to.eql("STOPPED");pm.expect(d.items.find(x=>x.id===1).revision).to.eql(4);',private])
    for name,path in [('duplicate_page',p+'?page=1&page=2'),('flow_duplicate_page','/api/point/flow?page=1&page=2'),('flow_owner_selector','/api/point/flow?user_id=2'),('task_duplicate_selector','/api/point/tasks?user_id=1&user_id=2')]:
        a(name,'GET',path,400,'error',role='points' if path.startswith(p) else 'owner')
    a('write_query','POST','/api/point/check-in?user_id=2',400,'error',check)
    a('policy_query','PUT',p+'/4?revision=2',400,'error',dict(changed,expected_revision=2),'points')
    a('reviewer_create','POST',p,403,'unauthorized',base,'reviewer')
    a('other_final_balance','GET','/api/point/overview',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.balance).to.eql(0);'])
    return c.items


def assets():return assemble(cases(),'Autocare point policies and isolated verified task ledger','isolated_points_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql("UPDATE operator_account SET can_knowledge=0,can_points=1 WHERE id=4; UPDATE user SET point_balance=99999 WHERE id=1; UPDATE auth_session SET point_identity_source='VERIFIED_WECHAT' WHERE subject_type='user' AND subject_id=1 AND app_id='fulfillment-cases';")
    result['points_token']=result.pop('knowledge_token')
    today=datetime.now(timezone(timedelta(hours=8))).date()
    now=datetime.now(timezone.utc).replace(microsecond=0)
    result.update(isolated_points_cases='true',point_day=today.isoformat(),yesterday=(today-timedelta(days=1)).isoformat(),future_from=(now+timedelta(hours=1)).isoformat().replace('+00:00','Z'))
    return result


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('POINTS_EARNING_ENABLED','true'),('POINTS_AI_VERIFIED_ENABLED','false'),('POINTS_EXCHANGE_ENABLED','false'),('POINTS_RECOVERY_DELAY_MS','250'),('KNOWLEDGE_AI_ENABLED','false'),('EXPERIENCE_PUBLICATION_ENABLED','false')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    requests={x['name']:x['request'] for x in collection['item']}
    for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        planned=requests[e['item']['name']];actual=e['request']
        if 'body' not in planned:continue
        raw=planned['body']['raw']
        for key,value in tokens.items():raw=raw.replace('{{'+key+'}}',str(value))
        if json.loads(actual['body']['raw'])!=json.loads(raw):raise ValueError('Point policy/check-in body differs from declared source/revision/day')
        keys=[x['value'] for x in actual.get('header',[]) if x['key'].lower()=='idempotency-key' and not x.get('disabled')]
        wanted=[x['value'] for x in planned.get('header',[]) if x['key'].lower()=='idempotency-key']
        if keys!=wanted:raise ValueError('Point receipt replay used a different idempotency key')
    result['actual_body_and_idempotency_keys_verified']=True
    return result


def verify_database(mysql):
    tables={'policies':'point_task_policy','events':'point_task_event','accounts':'point_verified_account','entries':'point_verified_entry','checkins':'point_checkin_request','legacy_flows':'point_flow','exchanges':'point_exchange_record','payments':'payment'}
    actual={k:int(mysql.text('SELECT COUNT(*) FROM `'+v+'`;')) for k,v in tables.items()}
    if actual!={'policies':4,'events':1,'accounts':1,'entries':1,'checkins':3,'legacy_flows':0,'exchanges':0,'payments':0}:raise ValueError('Point policy/task/ledger rows differ: '+str(actual))
    checks={
        'one_frozen_daily_credit':mysql.text("SELECT CONCAT(user_id,':',task_type,':',policy_id,':',policy_revision,':',points,':',status) FROM point_task_event;")=='1:CHECK_IN:1:3:7:CREDITED',
        'new_rule_does_not_rewrite_snapshot':mysql.text("SELECT CONCAT(JSON_EXTRACT(policy_snapshot,'$.points'),':',JSON_EXTRACT(policy_snapshot,'$.revision')) FROM point_task_event;")=='7:3',
        'one_entry_and_balance':mysql.text("SELECT CONCAT(a.user_id,':',a.balance,':',a.revision,':',e.delta,':',e.balance_after) FROM point_verified_account a JOIN point_verified_entry e ON e.user_id=a.user_id;")=='1:7:1:7:7',
        'legacy_balance_not_imported':mysql.text('SELECT point_balance FROM user WHERE id=1;')=='99999',
        'no_other_owner_reward':mysql.text('SELECT COUNT(*) FROM point_task_event WHERE user_id<>1;')=='0',
        'stopped_rule_not_resurrected':mysql.text("SELECT CONCAT(status,':',revision) FROM point_task_policy WHERE id=1;")=='STOPPED:4',
        'replacement_published':mysql.text("SELECT CONCAT(status,':',revision,':',points) FROM point_task_policy WHERE id=4;")=='PUBLISHED:2:11',
        'independent_points_permission':mysql.text('SELECT COUNT(*) FROM operator_account WHERE can_points=1;')=='1',
        'one_capture_and_credit_audit':mysql.text("SELECT GROUP_CONCAT(CONCAT(action,':',n) ORDER BY action) FROM (SELECT action,COUNT(*) n FROM audit_log WHERE action IN ('POINT_TASK_CAPTURE','POINT_TASK_CREDIT') GROUP BY action) a;")=='POINT_TASK_CAPTURE:1,POINT_TASK_CREDIT:1',
    }
    if not all(checks.values()):raise ValueError('Point reward identity/version/uniqueness invariant failed')
    return {'row_counts':actual,'checks':checks,'identity_scope':'SQL synthetic VERIFIED_WECHAT versus UNKNOWN; no real identity supplier'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Point ledger API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
