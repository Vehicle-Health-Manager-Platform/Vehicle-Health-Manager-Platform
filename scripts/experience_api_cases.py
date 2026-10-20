"""Synthetic source protocol fixtures; no real WeChat verification or supplier payment."""
import copy
import json
import time
import uuid
from coupon_api_cases import Cases
from community_api_cases import seed as community_seed
from fulfillment_api_cases import ROOT, assemble

OUTPUT=ROOT/'docs/testing/postman/experience-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/experience-case-manifest.json'
OPERATION_COUNT=14


def cases():
    c=Cases();a=c.add
    cards='/api/experience-cards';review='/api/admin/experience-cards';public='/api/community/experiences'
    sources='/api/admin/community/curation-sources';curations='/api/admin/community/curations';hot='/api/community/hot?vehicle_id=1'
    detail=public+'/{{experience_reference}}?vehicle_id=1'
    consent={'agree':True,'consent_version':'experience-v1'}
    private_guard='for(const x of ["SYNTHETIC_PRIVATE_REPORT","SYNTHETIC_PRIVATE_REVIEW","file_ids","object_key","13812345678"])pm.expect(pm.response.text()).not.to.include(x);'
    public_guard=private_guard+'for(const x of ["order_id","archive_id","user_id","card_id","source_note","source_hash"])pm.expect(pm.response.text()).not.to.include(x);'
    c.read('cards',cards+'?vehicle_id=1',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.every(x=>x.status==="DRAFT")).to.eql(true);pm.expect(d.items.find(x=>x.card_id===2).test_mode).to.eql(true);',private_guard],boundary=(cards+'?vehicle_id=1&page=2',200),error=(cards+'?vehicle_id=1&page_size=51',400))
    a('foreign_cards','GET',cards+'?vehicle_id=1',404,'unauthorized',role='other')
    a('test_card_cannot_consent','POST',cards+'/2/consent',409,'boundary',consent,extra=['pm.expect(pm.response.json().code).to.eql(45001);'])
    a('foreign_consent','POST',cards+'/1/consent',404,'unauthorized',consent,'other')
    c.write('consent',cards+'/1/consent',consent,extra=['const c=pm.response.json().data.card;pm.expect(c.status).to.eql("PENDING_REVIEW");pm.expect(c.revision).to.eql(1);'])
    c.read('pending',review,'reviewer',extra=['pm.expect(pm.response.json().data.total).to.eql(1);',private_guard],boundary=(review+'?page=2',200),error=(review+'?page_size=51',400))
    a('curator_not_reviewer','GET',review,403,'unauthorized',role='manager')
    a('unapproved_hidden','GET',public+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    approval={'revision':1,'decision':'APPROVE','reason_code':None}
    c.write('approve',review+'/1/moderate',approval,'reviewer',extra=['pm.expect(pm.response.json().data.status).to.eql("PUBLISHED");'])
    a('stale_moderation','POST',review+'/1/moderate',409,'boundary',dict(approval,decision='REJECT',reason_code='NOT_SUITABLE'),'reviewer')
    c.read('public_list',public+'?vehicle_id=1',extra=['const i=pm.response.json().data.items;pm.expect(i).to.have.lengthOf(1);pm.collectionVariables.set("experience_reference",i[0].experience_id);pm.expect(Object.keys(i[0]).sort()).to.eql(["experience_id","title","summary","model_id","published_at"].sort());',public_guard],boundary=(public+'?vehicle_id=1&cursor=999999',200),error=(public+'?vehicle_id=1&user_id=1',400))
    a('public_foreign_vehicle','GET',public+'?vehicle_id=2',404,'unauthorized')
    a('same_model_other_owner','GET',public+'?vehicle_id=2',200,'boundary',role='other',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(1);'])
    c.read('public_detail',detail,extra=[public_guard],boundary=(public+'/00000000-0000-4000-8000-000000000000?vehicle_id=1',404),error=(public+'/invalid?vehicle_id=1',400))
    a('foreign_public_detail','GET',public+'/{{experience_reference}}?vehicle_id=2',404,'unauthorized')
    c.read('curation_sources',sources,'manager',extra=['const i=pm.response.json().data.items;pm.expect(i).to.have.lengthOf(1);pm.expect(i[0].experience.experience_id).to.eql(pm.collectionVariables.get("experience_reference"));pm.collectionVariables.set("source_hash",i[0].source_hash);',private_guard],boundary=(sources+'?page=2',200),error=(sources+'?page_size=21',400))
    a('reviewer_not_curator','GET',sources,403,'unauthorized',role='reviewer')
    curation={'experience_id':'{{experience_reference}}','source_hash':'{{source_hash}}','priority':10,'source_note':'SYNTHETIC_PRIVATE_CURATION_NOTE','scope_confirmed':True,'valid_from':'{{curation_from}}','valid_to':'{{curation_to}}'}
    c.write('curation_create',curations,curation,'manager')
    c.items[-5]['event'].insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':[
        'if(!pm.collectionVariables.get("curation_from")){const start=Math.floor(Date.now()/1000)*1000+60000;pm.collectionVariables.set("curation_from",new Date(start).toISOString().replace(".000Z","Z"));pm.collectionVariables.set("curation_to",new Date(start+3600000).toISOString().replace(".000Z","Z"));}']}})
    a('source_hash_mismatch','POST',curations,409,'boundary',dict(curation,source_hash='0'*64),'manager')
    c.read('curation_list',curations,'manager',extra=['pm.expect(pm.response.json().data.total).to.eql(1);'],boundary=(curations+'?page=2',200),error=(curations+'?page_size=21',400))
    c.read('curation_detail',curations+'/1','manager',extra=['pm.expect(pm.response.json().data.status).to.eql("DRAFT");'],boundary=(curations+'/999999',404),error=(curations+'/1?user_id=1',400))
    update=dict(curation,priority=1,expected_revision=1)
    c.write('curation_update',curations+'/1',update,'manager',method='PUT')
    a('stale_curation_update','PUT',curations+'/1',409,'boundary',update,'manager')
    c.write('curation_publish',curations+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'manager',method='PUT')
    a('published_curation_frozen','PUT',curations+'/1',409,'boundary',dict(update,expected_revision=3),'manager')
    a('hot_before_window','GET',hot,200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    c.read('hot',hot,extra=['const d=pm.response.json().data;pm.expect(d.selection_type).to.eql("OPERATOR_CURATED");pm.expect(d.state).to.eql("CONFIGURED");pm.expect(d.items).to.have.lengthOf(1);pm.expect(d.items[0].priority).to.eql(1);pm.expect(d.items[0].experience.experience_id).to.eql(pm.collectionVariables.get("experience_reference"));',public_guard,'pm.expect(pm.response.text()).not.to.include("SYNTHETIC_PRIVATE_CURATION_NOTE");'],boundary=('/api/community/hot?vehicle_id=2',404),error=(hot+'&user_id=1',400))
    c.items[-5]['event'].insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':[
        'const delay=Date.parse(pm.collectionVariables.get("curation_from"))-Date.now()+1000;if(delay>0)setTimeout(()=>{},delay);']}})
    a('foreign_withdraw','POST',cards+'/1/withdraw',404,'unauthorized',{},'other')
    c.write('withdraw',cards+'/1/withdraw',{},bad={'unexpected':1},extra=['const c=pm.response.json().data.card;pm.expect(c.status).to.eql("WITHDRAWN");pm.expect(c.revision).to.eql(3);pm.expect(c.consent_version).to.eql(null);'])
    a('withdrawn_detail','GET',detail,404,'boundary')
    a('withdrawn_hot','GET',hot,200,'boundary',extra=['pm.expect(pm.response.json().data.state).to.eql("UNKNOWN");pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    a('withdrawn_sources','GET',sources,200,'boundary',role='manager',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    a('old_consent_key_rejected','POST',cards+'/1/consent',409,'boundary',consent,key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:consent')),extra=['pm.expect(pm.response.json().code).to.eql(45002);'])
    a('creation_receipt_after_source_loss','POST',curations,200,'boundary',curation,'manager',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:curation_create')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_curation_create")));'])
    c.write('reconsent',cards+'/1/consent',consent)
    c.write('reapprove',review+'/1/moderate',dict(approval,revision=4),'reviewer')
    a('new_public_uuid','GET',public+'?vehicle_id=1',200,'boundary',extra=['const i=pm.response.json().data.items;pm.expect(i).to.have.lengthOf(1);pm.expect(i[0].experience_id).not.to.eql(pm.collectionVariables.get("experience_reference"));'])
    a('old_uuid_never_revives','GET',detail,404,'boundary')
    a('curation_not_rebound','GET',hot,200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    c.write('curation_stop',curations+'/1/status',{'expected_revision':3,'status':'STOPPED'},'manager',method='PUT')
    a('curation_cannot_reopen','PUT',curations+'/1/status',409,'boundary',{'expected_revision':4,'status':'PUBLISHED'},'manager')
    c.write('second_withdraw',cards+'/1/withdraw',{},bad={'unexpected':1})
    c.write('third_consent',cards+'/1/consent',consent)
    c.write('reject',review+'/1/moderate',dict(approval,revision=7,decision='REJECT',reason_code='NOT_SUITABLE'),'reviewer')
    c.write('rejected_withdraw',cards+'/1/withdraw',{},bad={'unexpected':1})
    a('final_public_empty','GET',public+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    a('test_card_still_denied','POST',cards+'/2/consent',409,'boundary',consent,extra=['pm.expect(pm.response.json().code).to.eql(45001);'])
    return c.items


def assets():return assemble(cases(),'Autocare synthetic experience consent review and curation','isolated_experience_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=community_seed(mysql,secret)
    # Reuse a dedicated operator, with curate permission only (never review permission).
    mysql.sql('UPDATE operator_account SET can_community_manage=0,can_community_curate=1 WHERE id=1;')
    for file in range(13,16):
        mysql.sql(f"INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES({file},'staff_account',3,'synthetic-experience/{file}','image/png',100,'CLEAN');")
    for order,channel,test in [(1,'WECHAT',0),(2,'LOCAL_TEST',1)]:
        process,finish,signature=(9,10,11) if order==1 else (13,14,15)
        mysql.sql(f"""INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,service_report_ready_at) VALUES({order},'synthetic-experience-order-{order}',1,1,1,10,10,'COMPLETED','2026-10-09 02:00:00');
INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES({order},{order},'{channel}','synthetic-experience-payment-{order}','CNY',10,'SUCCEEDED','2026-10-09 02:00:00','synthetic-not-a-supplier-transaction-{order}');
INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('{channel}','synthetic-event-{order}',{order},'{'a'*64}','PAID');
INSERT INTO order_redemption(id,order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES({order},{order},1,1,{order},{test},'2026-10-09 02:00:00');
INSERT INTO technician_report(id,order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status,signed_at) VALUES({order},{order},3,'[{process}]','[]','[{finish}]','SYNTHETIC_PRIVATE_REPORT 13812345678','SYNTHETIC inspection','[]',40,1,'2026-10-09 01:00:00');
INSERT INTO service_report_submission(order_id,report_id,no_parts,no_fault_parts,submitted_at) VALUES({order},{order},1,1,'2026-10-09 00:50:00');
INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES({order},{order},{process},'PROCESS'),({order},{order},{finish},'FINISH'),({order},{order},{signature},'SIGNATURE');
INSERT INTO order_review(id,order_id,user_id,redemption_id,rating,content,test_mode,submitted_at) VALUES({order},{order},1,{order},5,'SYNTHETIC_PRIVATE_REVIEW',{test},'2026-10-09 02:10:00');
INSERT INTO service_archive_job(id,order_id,review_id,payload) VALUES({order},{order},{order},NULL);""")
    result['isolated_experience_cases']='true'
    return result


def environment(tokens):return [('SERVICE_ARCHIVE_ENABLED','true'),('SERVICE_ARCHIVE_INTERVAL_MS','250'),('EXPERIENCE_CARD_ENABLED','true'),('EXPERIENCE_PUBLICATION_ENABLED','true'),('KNOWLEDGE_AI_ENABLED','false')]


def prepare(mysql):
    deadline=time.monotonic()+30
    while mysql.text("SELECT COUNT(*) FROM service_archive_job WHERE status='DONE';")!='2':
        if time.monotonic()>=deadline:raise ValueError('Synthetic archive consumer did not produce both source cards')
        time.sleep(.25)
    if mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',order_id,':',test_mode) ORDER BY id) FROM experience_card;")!='1:1:0,2:2:1':raise ValueError('Unexpected synthetic source routing')


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    run=json.loads(path.read_text(encoding='utf-8'))['run']
    lists=[e for e in run['executions'] if e['item']['name']=='public_list']
    if len(lists)!=1:raise ValueError('Missing unique experience list evidence')
    body=json.loads(bytes(lists[0]['response']['stream']['data']).decode('utf-8'))
    if body.get('code')!=0 or len(body['data']['items'])!=1:raise ValueError('Missing approved experience reference')
    reference=body['data']['items'][0]['experience_id']
    if str(uuid.UUID(reference))!=reference:raise ValueError('Experience reference is not canonical UUID')
    resolved=copy.deepcopy(collection);inventory=copy.deepcopy(manifest)
    for item in resolved['item']:item['request']['url']=item['request']['url'].replace('{{experience_reference}}',reference)
    for item in inventory['items']:item['path']=item['path'].replace('{{experience_reference}}',reference)
    result=audit_owner_cases(path,resolved,inventory,tokens,expected_operations=OPERATION_COUNT)
    result['detail_bound_to_actual_experience_list']=True
    return result


def verify_database(mysql):
    tables={'cards':'experience_card','moderations':'experience_card_moderation','curations':'experience_curation','archives':'vehicle_archive','archive_jobs':'service_archive_job','reviews':'order_review','payments':'payment','payment_events':'payment_event'}
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    if actual!={'cards':2,'moderations':3,'curations':1,'archives':2,'archive_jobs':2,'reviews':2,'payments':2,'payment_events':2}:raise ValueError('Experience lifecycle rows missing or duplicated')
    checks={
        'withdrawn_revision_9':mysql.text("SELECT CONCAT(status,':',revision,':',IF(consent_version IS NULL,1,0)) FROM experience_card WHERE id=1;")=='WITHDRAWN:9:1',
        'test_card_never_authorized':mysql.text("SELECT CONCAT(status,':',revision,':',test_mode) FROM experience_card WHERE id=2;")=='DRAFT:0:1',
        'curation_stopped':mysql.text("SELECT CONCAT(status,':',revision) FROM experience_curation WHERE id=1;")=='STOPPED:4',
        'distinct_approved_ids':mysql.text('SELECT COUNT(DISTINCT public_id) FROM experience_card_moderation;')=='2',
        'consumer_not_retried':mysql.text("SELECT COUNT(*) FROM service_archive_job WHERE status='DONE' AND attempts=0;")=='2',
        'no_private_text_in_governance_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action IN ('EXPERIENCE_CARD_CONSENT','EXPERIENCE_CARD_WITHDRAW','EXPERIENCE_CARD_MODERATE') AND (after_state LIKE '%SYNTHETIC_PRIVATE%' OR after_state LIKE '%13812345678%');")=='0',
    }
    if not all(checks.values()):raise ValueError('Experience consent/source/curation invariant failed')
    return {'row_counts':actual,'checks':checks,'payment_scope':'two SQL synthetic source fixtures including WECHAT channel field; no supplier IO or real funds'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Experience API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
