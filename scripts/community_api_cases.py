"""Private Q&A and synthetic licensed KEYWORD retrieval, without external AI or public identity claims."""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/community-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/community-case-manifest.json'
OPERATION_COUNT=23


def cases():
    c=Cases();a=c.add
    base='/api/owner/community/questions';admin='/api/admin/community/questions'
    a('context_unconfigured','GET',base+'/context',200,'boundary',extra=['pm.expect(pm.response.json().data.accepting).to.eql(false);'])
    policy={'mode':'AVAILABLE','source_note':'SYNTHETIC_POLICY_MANAGER_ONLY','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('policy_create',admin+'/policies',policy,'manager',extra=['pm.expect(pm.response.json().data.id).to.eql(1);'])
    c.read('policy_list',admin+'/policies','manager',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],
        boundary=(admin+'/policies?page=2',200),error=(admin+'/policies?page_size=21',400))
    a('handler_cannot_manage_policy','GET',admin+'/policies',403,'unauthorized',role='handler')
    c.write('policy_update',admin+'/policies/1',dict(policy,expected_revision=1,mode='LEAVE_MESSAGE'),'manager',method='PUT')
    c.write('policy_publish',admin+'/policies/1/status',{'expected_revision':2,'status':'PUBLISHED'},'manager',method='PUT')
    c.read('context_open',base+'/context',extra=[
        'const d=pm.response.json().data;pm.expect(d.accepting).to.eql(true);pm.expect(d.policy.revision).to.eql(3);pm.expect(pm.response.text()).not.to.include("SYNTHETIC_POLICY_MANAGER_ONLY");'],error=(base+'/context?owner_id=2',400))
    c.read('handlers',admin+'/handlers','manager',extra=['pm.expect(pm.response.json().data.total).to.eql(2);'],
        boundary=(admin+'/handlers?page=2',200),error=(admin+'/handlers?page_size=21',400))
    question={'vehicle_id':1,'policy_id':1,'policy_revision':3,'category':'FAULT','message':'SYNTHETIC_PRIVATE_QUESTION','consent':True}
    c.write('question_create',base+'/cases',question,extra=['pm.expect(pm.response.json().data.case_id).to.eql(1);pm.expect(pm.response.json().data.status).to.eql("WAITING");'])
    a('question_changed_intent','POST',base+'/cases',400,'error',dict(question,message='changed'),key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:question_create')))
    a('question_foreign_vehicle','POST',base+'/cases',404,'unauthorized',dict(question,vehicle_id=2))
    a('question_without_consent','POST',base+'/cases',400,'boundary',dict(question,consent=False))
    c.read('owner_cases',base+'/cases',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],
        boundary=(base+'/cases?page=2',200),error=(base+'/cases?owner_id=2',400))
    c.read('owner_detail',base+'/cases/1',extra=[
        'pm.expect(pm.response.json().data.messages.total).to.eql(1);pm.expect(pm.response.text()).not.to.include("plate_no");pm.expect(pm.response.text()).not.to.include("sender_id");'],
        boundary=(base+'/cases/1?page=2',200),error=(base+'/cases/1?page_size=21',400))
    a('owner_detail_foreign','GET',base+'/cases/1',404,'unauthorized',role='other')
    c.read('queue',admin+'/cases?scope=queue','manager',extra=[
        'pm.expect(pm.response.json().data.total).to.eql(1);pm.expect(pm.response.text()).not.to.include("SYNTHETIC_PRIVATE_QUESTION");pm.expect(pm.response.text()).not.to.include("vehicle_id");'],
        boundary=(admin+'/cases?scope=queue&page=2',200),error=(admin+'/cases?scope=invalid',400))
    a('handler_cannot_read_queue','GET',admin+'/cases?scope=queue',403,'unauthorized',role='handler')
    a('manager_cannot_read_private_detail','GET',admin+'/cases/1',403,'unauthorized',role='manager')
    a('unassigned_handler_cannot_read','GET',admin+'/cases/1',404,'unauthorized',role='second_handler')
    c.write('assign_first',admin+'/cases/1/assignment',{'expected_revision':1,'operator_id':2},'manager',method='PUT')
    c.read('handler_detail',admin+'/cases/1','handler',extra=['pm.expect(pm.response.json().data.messages.total).to.eql(1);'],
        boundary=(admin+'/cases/1?page=2',200),error=(admin+'/cases/1?page_size=21',400))
    a('handler_mine','GET',admin+'/cases',200,'normal',role='handler',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'])
    a('manager_cannot_read_mine','GET',admin+'/cases',403,'unauthorized',role='manager')
    c.write('reply_first',admin+'/cases/1/reply',{'expected_revision':2,'message':'SYNTHETIC_PRIVATE_REPLY_FIRST'},'handler',extra=['pm.expect(pm.response.json().data.revision).to.eql(3);'])
    follow={'expected_revision':3,'policy_id':1,'policy_revision':3,'message':'SYNTHETIC_PRIVATE_FOLLOWUP','consent':True}
    c.write('owner_followup',base+'/cases/1/message',follow,extra=['pm.expect(pm.response.json().data.revision).to.eql(4);'])
    a('followup_stale_revision','POST',base+'/cases/1/message',409,'boundary',follow)
    a('other_owner_cannot_follow','POST',base+'/cases/1/message',404,'unauthorized',follow,'other')
    c.write('reassign_second',admin+'/cases/1/assignment',{'expected_revision':4,'operator_id':3},'manager',method='PUT')
    a('former_handler_cannot_read','GET',admin+'/cases/1',404,'unauthorized',role='handler')
    a('former_handler_cannot_replay_reply','POST',admin+'/cases/1/reply',404,'unauthorized',{'expected_revision':2,'message':'SYNTHETIC_PRIVATE_REPLY_FIRST'},'handler',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:reply_first')))
    c.write('reply_second',admin+'/cases/1/reply',{'expected_revision':5,'message':'SYNTHETIC_PRIVATE_REPLY_SECOND'},'second_handler')
    c.write('owner_close',base+'/cases/1/close',{'expected_revision':6})
    a('closed_owner_case','GET',base+'/cases/1',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.case.status).to.eql("CLOSED");pm.expect(d.messages.total).to.eql(4);'])
    c.write('second_question',base+'/cases',dict(question,message='SYNTHETIC_PRIVATE_SECOND'),extra=['pm.expect(pm.response.json().data.case_id).to.eql(2);'])
    c.write('assign_second_case',admin+'/cases/2/assignment',{'expected_revision':1,'operator_id':2},'manager',method='PUT')
    c.write('handler_close',admin+'/cases/2/close',{'expected_revision':2},'handler')
    c.write('policy_stop',admin+'/policies/1/status',{'expected_revision':3,'status':'STOPPED'},'manager',method='PUT')
    a('context_stopped','GET',base+'/context',200,'boundary',extra=['pm.expect(pm.response.json().data.accepting).to.eql(false);'])
    a('new_question_after_stop','POST',base+'/cases',409,'boundary',dict(question,message='SYNTHETIC_PRIVATE_BLOCKED'))
    a('original_create_receipt_after_close','POST',base+'/cases',200,'boundary',question,key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:question_create')),
        extra=['pm.expect(pm.response.json().data).to.eql(JSON.parse(pm.collectionVariables.get("receipt_question_create")).data);pm.expect(pm.response.json().data.status).to.eql("WAITING");'])

    kb='/api/admin/knowledge';search='/api/knowledge/search?vehicle_id=1&q=coolant'
    document={'title':'SYNTHETIC coolant reference','publisher':'Synthetic test author','document_version':'test-v1',
        'source_reference':'synthetic-original-text-not-a-manual','license_reference':'SYNTHETIC_LICENSE_MANAGER_ONLY',
        'license_confirmed':False,'model_id':1,'valid_from':'{{valid_from}}','valid_to':'{{valid_to}}',
        'search_allowed':True,'excerpt_allowed':True,'ai_allowed':False,'body':'SYNTHETIC_KNOWLEDGE coolant reference text for testing only.'}
    c.read('knowledge_models',kb+'/models','knowledge',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(kb+'/models?page=2',200),error=(kb+'/models?page_size=21',400))
    a('community_manager_not_knowledge','GET',kb+'/models',403,'unauthorized',role='manager')
    a('reviewer_not_knowledge','GET',kb+'/models',403,'unauthorized',role='reviewer')
    c.write('knowledge_create',kb+'/documents',document,'knowledge',extra=['pm.expect(pm.response.json().data.id).to.eql(1);'])
    c.read('knowledge_admin',kb+'/documents','knowledge',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(kb+'/documents?page=2',200),error=(kb+'/documents?page_size=21',400))
    a('knowledge_cannot_manage_questions','GET',admin+'/policies',403,'unauthorized',role='knowledge')
    a('knowledge_cannot_read_private_question','GET',admin+'/cases/1',403,'unauthorized',role='knowledge')
    a('knowledge_publish_without_license','POST',kb+'/documents/1/status',409,'boundary',{'expected_revision':1,'status':'PUBLISHED'},'knowledge')
    a('knowledge_search_draft_hidden','GET',search,200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    c.write('knowledge_update',kb+'/documents/1',dict(document,expected_revision=1,license_confirmed=True),'knowledge',method='PUT')
    c.write('knowledge_publish',kb+'/documents/1/status',{'expected_revision':2,'status':'PUBLISHED'},'knowledge')
    a('knowledge_update_frozen','PUT',kb+'/documents/1',409,'boundary',dict(document,expected_revision=3,license_confirmed=True),'knowledge')
    c.read('knowledge_search',search,extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("AVAILABLE");pm.expect(d.method).to.eql("KEYWORD");pm.expect(d.items).to.have.lengthOf(1);pm.expect(d.items[0].ai_allowed).to.eql(false);pm.collectionVariables.set("knowledge_reference",d.items[0].reference);',
        'pm.expect(pm.response.text()).not.to.include("SYNTHETIC_LICENSE_MANAGER_ONLY");pm.expect(pm.response.text()).not.to.include("license_reference");'],
        boundary=('/api/knowledge/search?vehicle_id=1&q=zzzz_nonexistent',200),error=('/api/knowledge/search?vehicle_id=1&q=',400))
    a('knowledge_search_foreign_vehicle','GET','/api/knowledge/search?vehicle_id=2&q=coolant',404,'unauthorized')
    a('other_owner_own_model_can_search','GET','/api/knowledge/search?vehicle_id=2&q=coolant',200,'normal',role='other',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(1);'])
    detail='/api/knowledge/documents/{{knowledge_reference}}?vehicle_id=1'
    c.read('knowledge_detail',detail,extra=[
        'pm.expect(pm.response.json().data.document.body).to.include("SYNTHETIC_KNOWLEDGE");pm.expect(pm.response.text()).not.to.include("SYNTHETIC_LICENSE_MANAGER_ONLY");'],
        boundary=('/api/knowledge/documents/00000000-0000-4000-8000-000000000000?vehicle_id=1',404),error=('/api/knowledge/documents/invalid?vehicle_id=1',400))
    a('knowledge_detail_foreign_vehicle','GET',detail,404,'unauthorized',role='other')
    c.write('knowledge_stop',kb+'/documents/1/status',{'expected_revision':3,'status':'STOPPED'},'knowledge')
    a('knowledge_stopped_reference_hidden','GET',detail,404,'boundary')
    a('knowledge_stopped_search_empty','GET',search,200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    a('knowledge_stopped_cannot_republish','POST',kb+'/documents/1/status',409,'boundary',{'expected_revision':4,'status':'PUBLISHED'},'knowledge')
    return c.items


def assets():return assemble(cases(),'Autocare isolated private questions and licensed KEYWORD retrieval','isolated_community_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql("""INSERT INTO operator_account(id,account,password_hash,phone,status,can_community_manage,can_community_answer,can_knowledge,can_review) VALUES
(1,'synthetic-community-manager','not-a-login-password','13800000001','ACTIVE',1,0,0,0),
(2,'synthetic-community-handler','not-a-login-password','13800000002','ACTIVE',0,1,0,0),
(3,'synthetic-community-handler-2','not-a-login-password','13800000003','ACTIVE',0,1,0,0),
(4,'synthetic-knowledge','not-a-login-password','13800000004','ACTIVE',0,0,1,0),
(5,'synthetic-reviewer','not-a-login-password','13800000005','ACTIVE',0,0,0,1);""")
    now=datetime.now(timezone.utc).replace(microsecond=0)
    result.update(isolated_community_cases='true',valid_from=(now-timedelta(minutes=1)).isoformat().replace('+00:00','Z'),valid_to=(now+timedelta(days=1)).isoformat().replace('+00:00','Z'))
    for role,subject in [('manager',1),('handler',2),('second_handler',3),('knowledge',4),('reviewer',5)]:
        session=str(uuid.uuid4())
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES('{session}','operator_account',{subject},'OPERATOR','operator-account','{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':'operator_account','role':'OPERATOR','app_id':'operator-account'}
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        result[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    return result


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('KNOWLEDGE_ENABLED','true'),('KNOWLEDGE_AI_ENABLED','false'),('COMMUNITY_PUBLICATION_ENABLED','false')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    run=json.loads(path.read_text(encoding='utf-8'))['run']
    searches=[e for e in run['executions'] if e['item']['name']=='knowledge_search']
    if len(searches)!=1:raise ValueError('Missing unique knowledge search reference evidence')
    body=json.loads(bytes(searches[0]['response']['stream']['data']).decode('utf-8'))
    data=body['data']
    if body.get('code')!=0 or data.get('method')!='KEYWORD' or len(data.get('items',[]))!=1:raise ValueError('No successful KEYWORD source reference')
    reference=data['items'][0]['reference']
    if str(uuid.UUID(reference))!=reference:raise ValueError('Knowledge reference is not a canonical UUID')
    resolved=copy.deepcopy(collection);inventory=copy.deepcopy(manifest)
    for item in resolved['item']:item['request']['url']=item['request']['url'].replace('{{knowledge_reference}}',reference)
    for item in inventory['items']:item['path']=item['path'].replace('{{knowledge_reference}}',reference)
    result=audit_owner_cases(path,resolved,inventory,tokens,expected_operations=OPERATION_COUNT)
    result['detail_reference_bound_to_actual_search']=True
    return result


def verify_database(mysql):
    tables={'policies':'community_qa_service_policy','cases':'community_qa_case','messages':'community_qa_message','case_events':'community_qa_case_event',
        'documents':'knowledge_document','document_events':'knowledge_document_event','publications':'community_qa_publication','diagnoses':'diagnosis_case','payments':'payment'}
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    expected={'policies':1,'cases':2,'messages':5,'case_events':10,'documents':1,'document_events':4,'publications':0,'diagnoses':0,'payments':0}
    if actual!=expected:raise ValueError('Community/knowledge rows are missing or duplicated')
    checks={
        'cases_closed_at_expected_revisions':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status,':',revision) ORDER BY id) FROM community_qa_case;")=='1:CLOSED:7,2:CLOSED:3',
        'policy_stopped':mysql.text("SELECT CONCAT(status,':',revision) FROM community_qa_service_policy WHERE id=1;")=='STOPPED:4',
        'document_stopped':mysql.text("SELECT CONCAT(status,':',revision) FROM knowledge_document WHERE id=1;")=='STOPPED:4',
        'no_private_text_in_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%SYNTHETIC_PRIVATE_%' OR before_state LIKE '%SYNTHETIC_PRIVATE_%';")=='0',
        'no_foreign_questions':mysql.text('SELECT COUNT(*) FROM community_qa_case WHERE owner_id=2;')=='0',
        'ai_use_not_permitted':mysql.text("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.ai_allowed')) FROM knowledge_document WHERE id=1;")=='false',
        'independent_operator_permissions':mysql.text('SELECT COUNT(*) FROM operator_account WHERE can_community_manage+can_community_answer+can_knowledge+can_review<>1;')=='0',
    }
    if not all(checks.values()):raise ValueError('Community privacy/revision/knowledge invariant failed')
    return {'row_counts':actual,'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Community/knowledge API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
