"""Invite reads and policy writes; SQL synthetic qualification fixtures, never real WeChat proof."""
from datetime import datetime,timedelta,timezone
import hashlib,json,uuid
from coupon_api_cases import Cases
from community_api_cases import seed as identity_seed
from fulfillment_api_cases import ROOT,assemble
from points_api_cases import audit as body_audit,replay_key

OUTPUT=ROOT/'docs/testing/postman/invitation-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/invitation-case-manifest.json'
OPERATION_COUNT=14
POLICY='/api/admin/invite/diagnosis-policies'


def cases():
    c=Cases();a=c.add;p=POLICY;eligibility='/api/invite/diagnosis-eligibility'
    private='for(const k of ["openid","phone_hash","invite_code_id","source_id","refresh_hash"])pm.expect(pm.response.text()).not.to.include(k);'
    for label,path,role,kind in [('owner','/api/invite/qrcode','owner','OWNER'),('merchant','/api/merchant/invite/code','merchant','STAFF')]:
        c.read(label+'_code',path,role,extra=['const d=pm.response.json().data.code;pm.expect(d.value).to.match(/^[0-9a-f]{32}$/);pm.expect(d.source_type).to.eql("'+kind+'");pm.expect(d.landing_path).to.eql("/pages/welfare/invite-entry?code="+d.value);'],boundary=(path,200),error=(path+'?user_id=2',400))
    for label,path,role in [('owner','/api/invite/code','owner'),('merchant','/api/merchant/invite/code','merchant')]:
        c.write(label+'_code_create',path,{},role,bad={'user_id':2},extra=['pm.expect(pm.response.json().data.code.value).to.match(/^[0-9a-f]{32}$/);'])
        a(label+'_existing_code_new_key','POST',path,200,'boundary',{},role,extra=['pm.expect(pm.response.json().data).to.eql(JSON.parse(pm.collectionVariables.get("receipt_'+label+'_code_create")).data);'])
    for label,path,read,role in [('other_owner','/api/invite/code','/api/invite/qrcode','other'),('other_shop','/api/merchant/invite/code','/api/merchant/invite/code','other_merchant')]:
        a(label+'_empty_code','GET',read,200,'boundary',role=role,extra=['pm.expect(pm.response.json().data.code).to.eql(null);'])
        a(label+'_new_code','POST',path,200,'normal',{},role,extra=['pm.expect(pm.response.json().data.code.value).to.match(/^[0-9a-f]{32}$/);'])
    a('technician_cannot_create_merchant_code','POST','/api/merchant/invite/code',403,'unauthorized',{},'technician')
    c.read('owner_records','/api/invite/records',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(16);pm.expect(d.items).to.have.lengthOf(16);pm.expect(d.items.every(x=>x.source_type==="OWNER")).to.eql(true);',private],boundary=('/api/invite/records?page=1000000',200),error=('/api/invite/records?page=0',400))
    c.read('merchant_records','/api/merchant/invite/records','merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(3);pm.expect(pm.response.json().data.items.every(x=>x.source_type==="STAFF")).to.eql(true);',private],boundary=('/api/merchant/invite/records?page=1000000',200),error=('/api/merchant/invite/records?user_id=2',400))
    a('other_records_empty','GET','/api/invite/records',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    a('other_shop_records_empty','GET','/api/merchant/invite/records',200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c.read('owner_progress','/api/invite/progress',extra=['const d=pm.response.json().data;pm.expect(d.qualified_count).to.eql(11);pm.expect(d.completed_cycles).to.eql(1);pm.expect(d.tier).to.eql(2);pm.expect(d.tier_count).to.eql(0);pm.expect(d.next_threshold).to.eql(13);pm.expect(d.month).to.eql(pm.environment.get("month"));pm.expect(d.timezone).to.eql("Asia/Shanghai");for(const k of ["pending_total","unknown_total","test_total","rejected_total"])pm.expect(d[k]).to.eql(1);pm.expect(d.verified_identity_enabled).to.eql(false);'],boundary=('/api/invite/progress',200),error=('/api/invite/progress?month=2026-01',400))
    c.read('merchant_progress','/api/merchant/invite/progress','merchant',extra=['const d=pm.response.json().data;pm.expect(d.qualified_count).to.eql(3);pm.expect(d.tier).to.eql(3);pm.expect(d.next_threshold).to.eql(6);'],boundary=('/api/merchant/invite/progress',200),error=('/api/merchant/invite/progress?merchant_id=2',400))
    a('other_progress_zero','GET','/api/invite/progress',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.qualified_count).to.eql(0);'])
    a('other_shop_progress_zero','GET','/api/merchant/invite/progress',200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.qualified_count).to.eql(0);'])
    c.read('legacy_registration','/api/invite/registration',extra=['pm.expect(pm.response.json().data.registration).to.eql(null);'],boundary=('/api/invite/registration',200),error=('/api/invite/registration?user_id=2',400))
    a('test_registration_stays_test','GET','/api/invite/registration',200,'boundary',role='other',extra=['const r=pm.response.json().data.registration;pm.expect(r.identity_source).to.eql("TEST");pm.expect(r.qualification_status).to.eql("TEST");',private])
    c.read('empty_policies',p,'invite',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],boundary=(p+'?page=1000000',200),error=(p+'?page=0',400))
    a('reviewer_cannot_invite_policy','GET',p,403,'unauthorized',role='reviewer')
    c.read('unknown_eligibility',eligibility,extra=['const d=pm.response.json().data;pm.expect(d.qualified_count).to.eql(11);pm.expect(d.state).to.eql("UNKNOWN");pm.expect(d.service_available).to.eql(false);'],boundary=(eligibility,200),error=(eligibility+'?month=2026-01',400))
    body={'required_count':13,'source_note':'SYNTHETIC monthly qualification protocol fixture','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('policy_create',p,body,'invite')
    changed=dict(body,required_count=12,expected_revision=1)
    c.write('policy_update',p+'/1',changed,'invite',method='PUT')
    a('stale_policy_update','PUT',p+'/1',409,'boundary',changed,'invite')
    a('missing_policy_update','PUT',p+'/999999',404,'boundary',changed,'invite')
    for name,bad in [('zero_threshold',dict(body,required_count=0)),('overflow_threshold',dict(body,required_count=1000001)),('fraction_threshold',dict(body,required_count=1.5)),('client_eligibility',dict(body,eligible=True)),('bad_window',dict(body,valid_from='{{valid_to}}'))]:
        a(name,'POST',p,400,'error',bad,'invite')
    c.write('policy_publish',p+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'invite',method='PUT')
    a('published_policy_immutable','PUT',p+'/1',409,'boundary',dict(changed,expected_revision=3),'invite')
    a('threshold_locked','GET',eligibility,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.qualified_count).to.eql(11);pm.expect(d.state).to.eql("LOCKED");pm.expect(d.policy.required_count).to.eql(12);pm.expect(d.usable_until).to.eql(null);'])
    c.write('policy_stop',p+'/1/status',{'expected_revision':3,'status':'STOPPED'},'invite',method='PUT')
    a('old_publish_receipt','PUT',p+'/1/status',200,'boundary',{'expected_revision':2,'status':'PUBLISHED'},'invite',key=replay_key('policy_publish'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy_publish")));'])
    a('old_receipt_not_resurrected','GET',eligibility,200,'boundary',extra=['pm.expect(pm.response.json().data.state).to.eql("UNKNOWN");'])
    a('stopped_cannot_republish','PUT',p+'/1/status',409,'boundary',{'expected_revision':4,'status':'PUBLISHED'},'invite')
    c.write('replacement_policy',p,dict(body,required_count=11),'invite')
    c.write('replacement_publish',p+'/2/status',{'expected_revision':1,'status':'PUBLISHED'},'invite',method='PUT')
    a('eligible_not_service_available','GET',eligibility,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.state).to.eql("ELIGIBLE");pm.expect(d.qualified_count).to.eql(11);pm.expect(d.service_available).to.eql(false);pm.expect(new Date(d.usable_until).getTime()).to.eql(new Date(pm.environment.get("valid_to")).getTime());'])
    a('other_owner_locked','GET',eligibility,200,'unauthorized',role='other',extra=['const d=pm.response.json().data;pm.expect(d.state).to.eql("LOCKED");pm.expect(d.qualified_count).to.eql(0);'])
    c.write('future_policy',p,dict(body,required_count=1,valid_from='{{future_from}}'),'invite')
    a('unique_active_policy','PUT',p+'/3/status',409,'boundary',{'expected_revision':1,'status':'PUBLISHED'},'invite')
    c.write('replacement_stop',p+'/2/status',{'expected_revision':2,'status':'STOPPED'},'invite',method='PUT')
    c.write('future_publish',p+'/3/status',{'expected_revision':1,'status':'PUBLISHED'},'invite',method='PUT')
    a('future_not_started','GET',eligibility,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.state).to.eql("NOT_STARTED");pm.expect(d.service_available).to.eql(false);pm.expect(d.usable_until).to.eql(null);'])
    a('final_policies','GET',p,200,'normal',role='invite',extra=['const i=pm.response.json().data.items;pm.expect(i.map(x=>x.status)).to.eql(["PUBLISHED","STOPPED","STOPPED"]);pm.expect(i.map(x=>x.revision)).to.eql([2,3,4]);'])
    for name,path,role in [('records_duplicate_page','/api/invite/records?page=1&page=2','owner'),('merchant_records_duplicate','/api/merchant/invite/records?page=1&page=2','merchant'),('policy_duplicate_page',p+'?page=1&page=2','invite'),('registration_duplicate_selector','/api/invite/registration?user_id=1&user_id=2','owner')]:a(name,'GET',path,400,'error',role=role)
    a('code_write_query','POST','/api/invite/code?user_id=2',400,'error',{})
    a('policy_write_query','PUT',p+'/3/status?revision=2',400,'error',{'expected_revision':2,'status':'STOPPED'},'invite')
    return c.items


def assets():return assemble(cases(),'Autocare invitation relations and monthly diagnosis qualification','isolated_invitation_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql('UPDATE operator_account SET can_knowledge=0,can_invite=1 WHERE id=4;')
    result['invite_token']=result.pop('knowledge_token')
    now=datetime.now(timezone.utc).replace(microsecond=0);local=now.astimezone(timezone(timedelta(hours=8)))
    first=local.replace(day=1,hour=0,minute=0,second=0,microsecond=0);prior=first-timedelta(seconds=1)
    result.update(isolated_invitation_cases='true',month=local.strftime('%Y-%m'),future_from=(now+timedelta(hours=1)).isoformat().replace('+00:00','Z'))
    sqltime=lambda d:d.astimezone(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')
    created=sqltime(prior-timedelta(days=1))
    mysql.sql(f"INSERT INTO invite_code(id,code,actor_type,actor_id,merchant_id,created_at) VALUES(101,'{'a'*32}','user',1,NULL,'{created}'),(102,'{'b'*32}','staff_account',1,1,'{created}');")
    rows=[(u,'user',1,None,'VERIFIED_WECHAT','QUALIFIED',now) for u in range(10,21)]
    rows += [(21,'user',1,None,'VERIFIED_WECHAT','QUALIFIED',prior),(22,'user',1,None,'VERIFIED_WECHAT','PENDING',now),(23,'user',1,None,'UNKNOWN','UNKNOWN',now),(2,'user',1,None,'TEST','TEST',now),(24,'user',1,None,'VERIFIED_WECHAT','REJECTED',now)]
    rows += [(u,'staff_account',1,1,'VERIFIED_WECHAT','QUALIFIED',now) for u in range(30,33)]
    for user,kind,source,shop,identity,state,at in rows:
        if user!=2:mysql.sql(f"INSERT INTO user(id,openid,status) VALUES({user},'synthetic-private-invitee-{user}',1);")
        qualified=state=='QUALIFIED';stamp=sqltime(at);month=at.astimezone(timezone(timedelta(hours=8))).strftime('%Y-%m')
        evidence=f"'{stamp}','{month}'" if qualified else 'NULL,NULL'
        mysql.sql(f"INSERT INTO invite_registration(user_id,record_id,app_id,identity_source,invite_code_id,source_type,source_id,merchant_id,attribution_result,qualification_status,registered_at,qualified_at,reward_month,rule_version) VALUES({user},'{uuid.uuid4()}','fulfillment-cases','{identity}',{101 if kind=='user' else 102},'{kind}',{source},{shop if shop else 'NULL'},'ATTRIBUTED','{state}','{stamp}',{evidence},'R6A_INVITE_V1');")
        if qualified:mysql.sql(f"INSERT INTO invite_phone_evidence(phone_hash,user_id,app_id,verified_at) VALUES('{hashlib.sha256(('synthetic-phone-'+str(user)).encode()).hexdigest()}',{user},'fulfillment-cases','{stamp}');")
    return result


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('INVITE_VERIFIED_IDENTITY_ENABLED','false'),('INVITE_REWARD_ENABLED','false'),('KNOWLEDGE_AI_ENABLED','false'),('POINTS_EARNING_ENABLED','false'),('POINTS_EXCHANGE_ENABLED','false')]


def audit(path,collection,manifest,tokens):return body_audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)


def verify_database(mysql):
    tables={'codes':'invite_code','registrations':'invite_registration','phone_fixtures':'invite_phone_evidence','policies':'invite_diagnosis_policy','reward_batches':'invite_reward_batch','coupon_issues':'coupon_issue','point_events':'point_task_event','payments':'payment'}
    actual={k:int(mysql.text('SELECT COUNT(*) FROM `'+v+'`;')) for k,v in tables.items()}
    if actual!={'codes':4,'registrations':19,'phone_fixtures':15,'policies':3,'reward_batches':0,'coupon_issues':0,'point_events':0,'payments':0}:raise ValueError('Invitation fixture/policy/code rows differ: '+str(actual))
    checks={
        'codes_unique_per_actor':mysql.text('SELECT COUNT(DISTINCT actor_type,actor_id) FROM invite_code;')=='4',
        'only_new_actors_audited':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='INVITE_CODE_CREATE';")=='2',
        'old_policy_not_revived':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status,':',revision) ORDER BY id) FROM invite_diagnosis_policy;")=='1:STOPPED:4,2:STOPPED:3,3:PUBLISHED:2',
        'test_identity_not_promoted':mysql.text("SELECT CONCAT(identity_source,':',qualification_status) FROM invite_registration WHERE user_id=2;")=='TEST:TEST',
        'two_original_source_codes_unchanged':mysql.text("SELECT COUNT(*) FROM invite_code WHERE (id=101 AND code=REPEAT('a',32) AND actor_type='user' AND actor_id=1) OR (id=102 AND code=REPEAT('b',32) AND actor_type='staff_account' AND actor_id=1 AND merchant_id=1);")=='2',
        'independent_invite_permission':mysql.text('SELECT COUNT(*) FROM operator_account WHERE can_invite=1;')=='1',
        'no_private_identity_in_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%synthetic-private-invitee%' OR after_state LIKE '%phone_hash%';")=='0',
    }
    if not all(checks.values()):raise ValueError('Invitation code/identity/policy/privacy invariant failed')
    return {'row_counts':actual,'checks':checks,'scope':'SQL synthetic attribution/phone/qualification fixtures; HTTP code and monthly policy writes; no registration provider or reward delivery'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Invitation monthly cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
