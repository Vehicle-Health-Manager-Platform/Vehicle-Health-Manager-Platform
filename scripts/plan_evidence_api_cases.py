"""Explicit model applicability, conservative vehicle evidence and manual plan booking context."""
import base64
import hashlib
import hmac
import json
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT,assemble,seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/plan-evidence-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/plan-evidence-case-manifest.json'
OPERATION_COUNT=7
READ_PATHS={'/api/ai/service-plan','/api/ai/service-plan/vehicle-evidence','/api/order/plan-booking-context'}
PLAN_CHECKS=set('withdrawn_rule_keeps_version three_rule_audits four_receipts one_slot no_orders_or_payments no_observation_inference no_private_source_writes no_foreign_rule registered_vehicle_unchanged'.split())
SOURCE='合成运营适配来源，未验证真实性'


def cases():
    c=Cases();add=c.add
    rule='/api/admin/project-applicability/1/1';ev='/api/ai/service-plan/vehicle-evidence';plan='/api/ai/service-plan';context='/api/order/plan-booking-context'
    def post(name,path,body,extra=(),boundary=None,boundary_extra=()):
        add(name,'POST',path,200,'normal',body,extra=extra)
        add(name+'_boundary','POST',path,200,'boundary',body if boundary is None else boundary,extra=boundary_extra)
        add(name+'_invalid','POST',path,400,'error',{})
        add(name+'_anonymous','POST',path,401,'unauthorized',body,'anonymous')
        add(name+'_wrong_role','POST',path,403,'unauthorized',body,'merchant')
    def evidence(name,verdict,version,source):
        add(name,'POST',ev,200,'normal',{'vehicle_id':1,'project_ids':[1]},extra=[
            'const d=pm.response.json().data;const a=d.applicability[0];pm.expect(a.verdict).to.eql('+json.dumps(verdict)+');pm.expect(a.rule_version).to.eql('+str(version)+');pm.expect(a.source_reference).to.eql('+json.dumps(source,ensure_ascii=False)+');'])
    c.read('rule_initial',rule,'catalog',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(0);pm.expect(d.rule).to.eql(null);'],boundary=('/api/admin/project-applicability/999999/1',404),error=('/api/admin/project-applicability/1/0',400))
    add('rule_read_no_permission','GET',rule,403,'unauthorized',role='plain')
    cat=plan+'/catalog'
    c.read('plan_catalog',cat,extra=['const d=pm.response.json().data;pm.expect(d.items.map(p=>p.project_id)).to.eql([1]);pm.expect(d.total).to.eql(1);'],error=(cat+'?owner_id=2',400))
    add('plan_catalog_repeat','GET',cat,200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(1);'])
    question='合成检查 联系13800000000'
    generation={'question':question,'requested_project_ids':[1],'consent_to_provider':True}
    post('manual_plan',plan,generation,extra=[
        'const d=pm.response.json().data;pm.expect(d.status).to.eql("MANUAL");pm.expect(d.candidate_count).to.eql(1);pm.expect(d.question).to.include("[已隐藏]");pm.expect(d.question).not.to.include("13800000000");pm.expect(d.items).to.have.lengthOf(1);const i=d.items[0];pm.expect(i.project_id).to.eql(1);pm.expect(i.tier).to.eql("REQUESTED");pm.expect(i.reason_code).to.eql("OWNER_SELECTED");pm.expect(i.evidence_quote).to.eql(d.question);pm.expect(i).not.to.have.property("price");'],
        boundary=dict(generation,requested_project_ids=[]),boundary_extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("MANUAL");pm.expect(d.items).to.have.lengthOf(0);'])
    for name,body in [('consent',dict(generation,consent_to_provider=False)),('duplicate',dict(generation,requested_project_ids=[1,1])),('long_question',dict(generation,question='x'*301)),('injected',dict(generation,price='0.01'))]:add('plan_'+name,'POST',plan,400,'error',body)
    add('plan_inactive_project','POST',plan,404,'boundary',dict(generation,requested_project_ids=[2]))
    body={'vehicle_id':1,'project_ids':[1]}
    post('vehicle_unknown',ev,body,extra=[
        'const d=pm.response.json().data;pm.expect(d.vehicle.model_id).to.eql(1);pm.expect(Object.keys(d.vehicle).sort()).to.eql(["model_id","model_name","model_source","power_type","vehicle_id"]);pm.expect(d.applicability[0].verdict).to.eql("UNKNOWN");pm.expect(d.applicability[0].source_reference).to.eql(null);pm.expect(d.observations.list).to.have.lengthOf(6);pm.expect(d.observations.list.every(o=>o.status==="NOT_SET")).to.eql(true);pm.expect(d.experiences.items).to.have.lengthOf(0);'],
        boundary={'vehicle_id':3,'project_ids':[1]},boundary_extra=['const d=pm.response.json().data;pm.expect(d.vehicle.model_id).to.eql(null);pm.expect(d.applicability[0].verdict).to.eql("UNKNOWN");'])
    add('vehicle_foreign','POST',ev,404,'unauthorized',body,'other')
    add('vehicle_duplicate_projects','POST',ev,400,'error',{'vehicle_id':1,'project_ids':[1,1]})
    add('vehicle_query_injection','POST',ev+'?owner_id=2',400,'error',body)
    applicable={'verdict':'APPLICABLE','source_reference':SOURCE,'expected_version':0}
    c.write('rule_applicable',rule,applicable,'catalog',method='PUT',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(1);pm.expect(d.rule.verdict).to.eql("APPLICABLE");'])
    add('rule_write_no_permission','PUT',rule,403,'unauthorized',applicable,'plain')
    add('rule_stale','PUT',rule,409,'error',dict(applicable,source_reference='合成旧版本'),'catalog')
    add('rule_changed_intent','PUT',rule,400,'error',dict(applicable,verdict='NOT_APPLICABLE'),'catalog',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:rule_applicable')))
    evidence('vehicle_applicable','APPLICABLE',1,SOURCE)
    add('same_series_different_model','POST',ev,200,'boundary',{'vehicle_id':4,'project_ids':[1]},extra=['const d=pm.response.json().data;pm.expect(d.vehicle.model_id).to.eql(2);pm.expect(d.applicability[0].verdict).to.eql("UNKNOWN");'])
    c.write('rule_not_applicable',rule,dict(applicable,verdict='NOT_APPLICABLE',expected_version=1),'catalog',method='PUT',extra=['pm.expect(pm.response.json().data.version).to.eql(2);'])
    evidence('vehicle_not_applicable','NOT_APPLICABLE',2,SOURCE)
    withdrawn={'verdict':'WITHDRAW','source_reference':None,'expected_version':2}
    c.write('rule_withdraw',rule,withdrawn,'catalog',method='PUT',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(3);pm.expect(d.rule).to.eql(null);'])
    add('rule_reuse_old_version','PUT',rule,409,'error',applicable,'catalog')
    add('rule_withdraw_with_source','PUT',rule,400,'error',dict(withdrawn,expected_version=3,source_reference=SOURCE),'catalog')
    add('rule_old_receipt','PUT',rule,200,'boundary',applicable,'catalog',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:rule_applicable')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_rule_applicable")));'])
    add('rule_final','GET',rule,200,'normal',role='catalog',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(3);pm.expect(d.rule).to.eql(null);'])
    evidence('vehicle_after_old_receipt','UNKNOWN',0,None)
    slot={'standard_project_id':1,'starts_at':'{{slot_start}}','ends_at':'{{slot_end}}','capacity':2}
    c.write('plan_slot','/api/merchant/slots',slot,'merchant')
    booking={'merchant_project_id':1,'standard_project_id':1,'vehicle_id':1,'date':'{{slot_date}}','page':1,'selected_slot_id':1}
    post('booking_context',context,booking,extra=[
        'const d=pm.response.json().data;pm.expect(d.quote.price).to.eql("12.34");pm.expect(d.quote.quote_version_id).to.eql(1);pm.expect(d.evidence.applicability[0].verdict).to.eql("UNKNOWN");pm.expect(d.slots.total).to.eql(1);pm.expect(d.selected_slot.slot_id).to.eql(1);'],
        boundary=dict(booking,page=2,selected_slot_id=None),boundary_extra=['const d=pm.response.json().data;pm.expect(d.slots.items).to.have.lengthOf(0);pm.expect(d.selected_slot).to.eql(null);'])
    add('booking_foreign_vehicle','POST',context,404,'unauthorized',dict(booking,vehicle_id=2))
    add('booking_project_mismatch','POST',context,404,'error',dict(booking,standard_project_id=2))
    add('booking_bad_page','POST',context,400,'error',dict(booking,page=0))
    add('booking_unknown_selected','POST',context,200,'boundary',dict(booking,selected_slot_id=999999),extra=['pm.expect(pm.response.json().data.selected_slot).to.eql(null);'])
    add('booking_query','POST',context+'?owner_id=2',400,'error',booking)
    for item in c.items:
        if item['request']['url'].removeprefix('{{base_url}}').split('?')[0] in READ_PATHS:
            item['request']['header']=[h for h in item['request']['header'] if h['key']!='Idempotency-Key']
    return c.items


def assets():return assemble(cases(),'Autocare isolated vehicle evidence and manual service plans','isolated_plan_evidence_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret)
    mysql.sql("""UPDATE vehicle SET power_type='EV' WHERE id=1;
INSERT INTO model(id,series_id,year,config_name,status) VALUES(2,1,'2026','synthetic different model',1);
INSERT INTO vehicle(id,user_id,model_id,current_mileage,power_type) VALUES(3,1,NULL,100,'EV'),(4,1,2,100,'EV');
INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status) VALUES(2,'synthetic inactive project',1,'inactive',1,100,0);
INSERT INTO operator_account(id,account,password_hash,phone,status,can_catalog) VALUES(1,'synthetic-plan-catalog','not-a-password','13900000031','ACTIVE',1),(2,'synthetic-plan-plain','not-a-password','13900000032','ACTIVE',0);""")
    for role,subject in [('catalog',1),('plain',2)]:
        session=str(uuid.uuid4())
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES('{session}','operator_account',{subject},'OPERATOR','operator-account','{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':'operator_account','role':'OPERATOR','app_id':'operator-account'}
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        tokens[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    tokens.update(isolated_plan_evidence_cases='true',slot_date=tokens['slot_start'][:10]);return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('AI_SERVICE_PLAN_ENABLED','false'),('DEEPSEEK_API_KEY',''),('DEEPSEEK_BASE_URL','')]


def render(raw,tokens):
    for key in ('slot_start','slot_end','slot_date'):raw=raw.replace('{{'+key+'}}',tokens[key])
    return raw


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    rows={e['item']['name']:e for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']}
    for item in collection['item']:
        expected=item['request'];actual=rows[item['name']]['request']
        headers=lambda r:sorted((h['key'].lower(),h['value']) for h in r['header'] if not h.get('disabled') and h['key'].lower() in ('idempotency-key','content-type'))
        if headers(expected)!=headers(actual) or render(expected.get('body',{}).get('raw',''),tokens)!=actual.get('body',{}).get('raw',''):
            raise ValueError('Plan/evidence version, consent, date, body or key was substituted')
    result['plan_request_intents_verified']=True;return result


def verify_database(mysql):
    checks={
        'withdrawn_rule_keeps_version':mysql.text('SELECT COUNT(*) FROM project_model_applicability WHERE project_id=1 AND model_id=1 AND version=3 AND verdict IS NULL AND source_reference IS NULL AND operator_id=1;')=='1',
        'three_rule_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='PROJECT_MODEL_APPLICABILITY';")=='3',
        'four_receipts':mysql.text('SELECT COUNT(*) FROM idempotency_record;')=='4',
        'one_slot':mysql.text('SELECT COUNT(*) FROM appointment_slot WHERE merchant_id=1 AND project_id=1 AND is_open=1;')=='1',
        'no_orders_or_payments':mysql.text('SELECT (SELECT COUNT(*) FROM `order`)+(SELECT COUNT(*) FROM payment)+(SELECT COUNT(*) FROM refund);')=='0',
        'no_observation_inference':mysql.text('SELECT COUNT(*) FROM vehicle_health_observation;')=='0',
        'no_private_source_writes':mysql.text('SELECT (SELECT COUNT(*) FROM vehicle_archive)+(SELECT COUNT(*) FROM audit_log WHERE action NOT IN (\'PROJECT_MODEL_APPLICABILITY\',\'SLOT_PUBLISH\'));')=='0',
        'no_foreign_rule':mysql.text('SELECT COUNT(*) FROM project_model_applicability WHERE project_id<>1 OR model_id<>1;')=='0',
        'registered_vehicle_unchanged':mysql.text('SELECT current_mileage FROM vehicle WHERE id=2;')=='12345',
    }
    if set(checks)!=PLAN_CHECKS or not all(checks.values()):raise ValueError('Plan evidence/read-only/version invariant failed: '+str(checks))
    return {'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Plan evidence cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
