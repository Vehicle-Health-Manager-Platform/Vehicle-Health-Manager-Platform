"""Isolated public Q&A consent, review and withdrawal; synthetic provenance only."""
import copy
import json
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from community_api_cases import seed as community_seed

OUTPUT=ROOT/'docs/testing/postman/publication-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/publication-case-manifest.json'
OPERATION_COUNT=14


def cases():
    c=Cases();a=c.add
    private='/api/owner/community/questions/cases';admin='/api/admin/community/questions'
    pubs=private+'/1/publications';preview=private+'/1/publication-preview'
    review='/api/admin/community/question-publications';withdraw='/api/owner/community/question-publications'
    public='/api/community/questions';detail=public+'/{{public_reference}}?vehicle_id=1'
    policy={'mode':'LEAVE_MESSAGE','source_note':'SYNTHETIC_POLICY_PRIVATE','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('policy_create',admin+'/policies',policy,'manager')
    c.write('policy_publish',admin+'/policies/1/status',{'expected_revision':1,'status':'PUBLISHED'},'manager',method='PUT')
    question={'vehicle_id':1,'policy_id':1,'policy_revision':2,'category':'FAULT','message':'SYNTHETIC question 13812345678 粤A12345','consent':True}
    c.write('question_create',private,question)
    c.write('assign',admin+'/cases/1/assignment',{'expected_revision':1,'operator_id':2},'manager',method='PUT')
    c.write('reply',admin+'/cases/1/reply',{'expected_revision':2,'message':'SYNTHETIC answer mail@example.com https://example.com/private'},'handler')
    pair={'question_message_id':1,'answer_message_id':2}
    a('preview','POST',preview,200,'normal',pair,extra=[
        'const d=pm.response.json().data;pm.collectionVariables.set("preview_hash",d.preview_hash);pm.expect(d.preview.redaction_version).to.eql("QA_TEXT_REDACTION_V1");pm.expect(d.preview.question_text).to.include("[已隐藏]");pm.expect(d.preview.answer_text).to.include("[已隐藏]");',
        'const t=pm.response.text();for(const s of ["13812345678","粤A12345","mail@example.com","https://example.com/private"])pm.expect(t).not.to.include(s);'])
    a('preview_wrong_pair','POST',preview,404,'boundary',dict(pair,question_message_id=2))
    a('preview_invalid','POST',preview,400,'error',{})
    a('preview_anonymous','POST',preview,401,'unauthorized',pair,'anonymous')
    a('preview_foreign','POST',preview,404,'unauthorized',pair,'other')
    a('preview_client_identity','POST',preview,400,'error',dict(pair,identity_source='VERIFIED_WECHAT'))
    consent=dict(pair,preview_hash='{{preview_hash}}',consent_version='R8C_PUBLIC_QA_V1',consent=True)
    a('wrong_preview_hash','POST',pubs,409,'boundary',dict(consent,preview_hash='0'*64))
    a('missing_consent','POST',pubs,400,'error',dict(consent,consent=False))
    c.write('submit',pubs,consent,extra=['pm.expect(pm.response.json().data.status).to.eql("PENDING");pm.expect(pm.response.json().data.application_id).to.eql(1);'])
    a('duplicate_active_application','POST',pubs,409,'boundary',consent)
    c.read('owner_applications',pubs,extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(pubs+'?page=2',200),error=(pubs+'?owner_id=2',400))
    a('foreign_applications','GET',pubs,404,'unauthorized',role='other')
    c.read('review_queue',review,'reviewer',extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].source_available).to.eql(true);for(const s of ["owner_id","case_id","message_id","source_hash","13812345678","SYNTHETIC_POLICY_PRIVATE"])pm.expect(pm.response.text()).not.to.include(s);'],
        boundary=(review+'?page=2',200),error=(review+'?page_size=21',400))
    a('manager_cannot_review','GET',review,403,'unauthorized',role='manager')
    a('public_pending_hidden','GET',public+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    moderate={'expected_revision':1,'decision':'APPROVE','reason_code':None}
    c.write('approve',review+'/1/moderate',moderate,'reviewer',extra=['pm.expect(pm.response.json().data.status).to.eql("APPROVED");'])
    a('approve_stale','POST',review+'/1/moderate',409,'boundary',dict(moderate,decision='REJECT',reason_code='PRIVACY'),'reviewer')
    c.read('public_list',public+'?vehicle_id=1',extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.have.lengthOf(1);const q=d.items[0];pm.collectionVariables.set("public_reference",q.public_id);pm.expect(Object.keys(q).sort()).to.eql(["public_id","model_id","category","question_text","answer_text","published_at"].sort());'],
        boundary=(public+'?vehicle_id=1&cursor=invalid',400),error=(public+'?vehicle_id=1&owner_id=1',400))
    a('public_foreign_vehicle','GET',public+'?vehicle_id=2',404,'unauthorized')
    a('other_same_model','GET',public+'?vehicle_id=2',200,'boundary',role='other',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(1);'])
    c.read('public_detail',detail,extra=['const q=pm.response.json().data.question;pm.expect(q.public_id).to.eql(pm.collectionVariables.get("public_reference"));pm.expect(Object.keys(q).sort()).to.eql(["public_id","model_id","category","question_text","answer_text","published_at"].sort());'],
        boundary=(public+'/00000000-0000-4000-8000-000000000000?vehicle_id=1',404),error=(public+'/invalid?vehicle_id=1',400))
    follow={'expected_revision':3,'policy_id':1,'policy_revision':2,'message':'SYNTHETIC_UNCONSENTED_FOLLOWUP','consent':True}
    c.write('followup',private+'/1/message',follow)
    a('public_scope_frozen','GET',detail,200,'boundary',extra=['pm.expect(pm.response.text()).not.to.include("SYNTHETIC_UNCONSENTED_FOLLOWUP");'])
    a('foreign_withdraw','POST',withdraw+'/1/withdraw',404,'unauthorized',{'expected_revision':2},'other')
    c.write('withdraw_first',withdraw+'/1/withdraw',{'expected_revision':2},extra=['pm.expect(pm.response.json().data.status).to.eql("WITHDRAWN");'])
    a('old_detail_withdrawn','GET',detail,404,'boundary')
    a('public_empty_after_withdraw','GET',public+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    a('creation_receipt_after_withdraw','POST',pubs,200,'boundary',consent,key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:submit')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_submit")));'])
    c.write('resubmit',pubs,consent,extra=['pm.expect(pm.response.json().data.application_id).to.eql(2);'])
    c.write('approve_second',review+'/2/moderate',moderate,'reviewer')
    a('new_uuid_after_reauthorization','GET',public+'?vehicle_id=1',200,'boundary',extra=['const i=pm.response.json().data.items;pm.expect(i).to.have.lengthOf(1);pm.expect(i[0].public_id).not.to.eql(pm.collectionVariables.get("public_reference"));'])
    a('old_uuid_never_revived','GET',detail,404,'boundary')
    c.write('withdraw_second',withdraw+'/2/withdraw',{'expected_revision':2})
    c.write('third_submit',pubs,consent)
    c.write('reject',review+'/3/moderate',dict(moderate,decision='REJECT',reason_code='PRIVACY'),'reviewer',extra=['pm.expect(pm.response.json().data.status).to.eql("REJECTED");'])
    c.write('withdraw_rejected',withdraw+'/3/withdraw',{'expected_revision':2})
    a('final_public_empty','GET',public+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    # Owner 2 retains UNKNOWN provenance. A normal private case must not become public.
    c.write('unknown_question',private,dict(question,vehicle_id=2,message='SYNTHETIC_UNKNOWN_SOURCE'),'other')
    c.items[-1]['request']['auth']['bearer'][0]['value']='{{merchant_token}}'
    c.items[-1]['role']='merchant'
    c.write('unknown_assign',admin+'/cases/2/assignment',{'expected_revision':1,'operator_id':2},'manager',method='PUT')
    c.write('unknown_reply',admin+'/cases/2/reply',{'expected_revision':2,'message':'SYNTHETIC_UNKNOWN_REPLY'},'handler')
    a('unknown_source_denied','POST',private+'/2/publication-preview',409,'boundary',{'question_message_id':4,'answer_message_id':5},'other')
    return c.items


def assets():return assemble(cases(),'Autocare synthetic public Q&A consent review withdrawal','isolated_publication_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=community_seed(mysql,secret)
    # Protocol fixture only: no external WeChat verification is claimed or invoked.
    mysql.sql("UPDATE auth_session SET point_identity_source='VERIFIED_WECHAT' WHERE subject_type='user' AND subject_id=1 AND role='OWNER' AND app_id='fulfillment-cases';")
    result['isolated_publication_cases']='true'
    return result


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('COMMUNITY_PUBLICATION_ENABLED','true'),('KNOWLEDGE_AI_ENABLED','false')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    run=json.loads(path.read_text(encoding='utf-8'))['run']
    lists=[e for e in run['executions'] if e['item']['name']=='public_list']
    if len(lists)!=1:raise ValueError('Missing unique public list evidence')
    body=json.loads(bytes(lists[0]['response']['stream']['data']).decode('utf-8'))
    if body.get('code')!=0 or len(body['data']['items'])!=1:raise ValueError('Missing approved public reference')
    reference=body['data']['items'][0]['public_id']
    if str(uuid.UUID(reference))!=reference:raise ValueError('Public reference is not canonical UUID')
    resolved=copy.deepcopy(collection);inventory=copy.deepcopy(manifest)
    for item in resolved['item']:item['request']['url']=item['request']['url'].replace('{{public_reference}}',reference)
    for item in inventory['items']:item['path']=item['path'].replace('{{public_reference}}',reference)
    result=audit_owner_cases(path,resolved,inventory,tokens,expected_operations=OPERATION_COUNT)
    result['detail_reference_bound_to_actual_public_list']=True
    return result


def verify_database(mysql):
    tables={'cases':'community_qa_case','messages':'community_qa_message','case_events':'community_qa_case_event','publications':'community_qa_publication','publication_events':'community_qa_publication_event','point_events':'point_task_event','payments':'payment'}
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    expected={'cases':2,'messages':5,'case_events':7,'publications':3,'publication_events':9,'point_events':0,'payments':0}
    if actual!=expected:raise ValueError('Public Q&A rows are missing or duplicated: '+str(actual))
    checks={
        'all_applications_withdrawn':mysql.text("SELECT COUNT(*) FROM community_qa_publication WHERE status='WITHDRAWN' AND revision=3;")=='3',
        'two_distinct_public_ids':mysql.text('SELECT COUNT(DISTINCT public_id) FROM community_qa_publication;')=='2',
        'no_sensitive_audit_text':mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%13812345678%' OR after_state LIKE '%mail@example.com%' OR after_state LIKE '%SYNTHETIC_UNCONSENTED_FOLLOWUP%';")=='0',
        'unknown_identity_not_promoted':mysql.text("SELECT COUNT(*) FROM auth_session WHERE subject_type='user' AND subject_id=2 AND point_identity_source='VERIFIED_WECHAT';")=='0',
    }
    if not all(checks.values()):raise ValueError('Public Q&A consent/privacy invariant failed')
    return {'row_counts':actual,'checks':checks,'identity_scope':'synthetic protocol fixture; no real WeChat verification'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Public Q&A API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
