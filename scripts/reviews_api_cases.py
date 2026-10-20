"""Owner review, independent public star, public text and summary consent; synthetic fulfillment only."""
import json
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from fulfillment_api_cases import seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/reviews-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/reviews-case-manifest.json'
OPERATION_COUNT=14


def cases():
    c=Cases();a=c.add
    submit='/api/order/review'
    first='/api/order/1/review';second='/api/order/2/review'
    pub1='/api/order/1/review-publication';pub2='/api/order/2/review-publication'
    ratings='/api/service/merchant/1/ratings'
    text2='/api/order/2/public-review-text';preview='/api/order/2/public-review-text/preview'
    texts='/api/service/merchant/1/review-texts';admin='/api/admin/review-texts'
    consent='/api/order/2/review-summary-consent';summary='/api/service/merchant/1/review-summary'
    body={'order_id':1,'rating':5,'content':'维修后复检正常，沟通清楚。','photo_file_ids':[20,21]}
    # 本人可评价/不可评价的资格投影
    c.read('first_pending',first,extra=['const d=pm.response.json().data;pm.expect(d.can_submit).to.eql(true);pm.expect(d.review).to.eql(null);pm.expect(d.test_mode).to.eql(true);pm.expect(d.unavailable_reason).to.eql(null);'],
           error=(first+'?order_id=1',400))
    a('not_completed','GET','/api/order/3/review',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.can_submit).to.eql(false);pm.expect(d.unavailable_reason).to.eql("ORDER_NOT_COMPLETED");pm.expect(d.review).to.eql(null);'])
    a('redemption_missing','GET','/api/order/4/review',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.unavailable_reason).to.eql("REDEMPTION_UNVERIFIED");'])
    a('open_dispute','GET','/api/order/6/review',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.unavailable_reason).to.eql("OPEN_DISPUTE");'])
    a('other_owner_order','GET',first,404,'unauthorized',role='other')
    a('technician_order','GET',first,403,'unauthorized',role='technician')
    # 写接口输入严格性
    for i,rating in enumerate([0,6,-1]):a('bad_rating_'+str(i),'POST',submit,400,'error',dict(body,rating=rating),'owner')
    for i,content in enumerate(['','   ','\n\t']):a('blank_content_'+str(i),'POST',submit,400,'error',dict(body,content=content),'owner')
    a('long_content','POST',submit,400,'error',dict(body,content='车'*501),'owner')
    a('too_many_photos','POST',submit,400,'error',dict(body,photo_file_ids=[20,21,20,21]),'owner')
    a('duplicate_target','POST',submit,400,'error',dict(body,photo_file_ids=[20,20]),'owner')
    a('unknown_field','POST',submit,400,'error',dict(body,user_id=2),'owner')
    a('missing_photo_key','POST',submit,400,'error',{'order_id':1,'rating':5,'content':'只有三个字段'},'owner')
    for label,raw in [('duplicate_rating','{"order_id":1,"rating":5,"rating":4,"content":"a","photo_file_ids":[]}'),
                      ('duplicate_content','{"order_id":1,"rating":5,"content":"a","content":"b","photo_file_ids":[]}'),
                      ('trailing_json','{"order_id":1,"rating":5,"content":"a","photo_file_ids":[]} {}'),
                      ('large_json','{"order_id":1,"rating":5,"content":"a","photo_file_ids":[]}'+(' '*8200))]:
        a(label,'POST',submit,400,'error',raw,'owner')
    a('submit_query','POST',submit+'?order_id=1',400,'error',body,'owner')
    a('other_owner_file','POST',submit,422,'boundary',dict(body,photo_file_ids=[22]),'owner')
    a('missing_file','POST',submit,422,'boundary',dict(body,photo_file_ids=[99]),'owner')
    # 正常提交 + 幂等/重放/不可修改
    c.write('create',submit,body,'owner',extra=['const d=pm.response.json().data;pm.expect(d.order_id).to.eql(1);pm.expect(d.review.rating).to.eql(5);pm.expect(d.review.photo_file_ids).to.eql([20,21]);pm.expect(d.review.test_mode).to.eql(true);'])
    key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:create'))
    a('changed_payload','POST',submit,400,'error',dict(body,content='同键改内容'),'owner',key=key)
    a('new_key_same_body','POST',submit,200,'boundary',body,'owner',extra=['const r=pm.response.json();pm.expect(r.data).to.eql(JSON.parse(pm.collectionVariables.get("receipt_create")).data);pm.expect(r.request_id).not.to.eql(JSON.parse(pm.collectionVariables.get("receipt_create")).request_id);'])
    a('new_key_other_body','POST',submit,409,'error',dict(body,rating=4),'owner',extra=['pm.expect(pm.response.json().code).to.eql(44002);'])
    a('reviewed_detail','GET',first,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.can_submit).to.eql(false);pm.expect(d.unavailable_reason).to.eql("ALREADY_REVIEWED");pm.expect(d.review.rating).to.eql(5);'])
    a('reviewed_other_owner','GET',first,404,'unauthorized',role='other')
    # 第二条正式（WECHAT）评价承载公开链路
    c.write('create_public',submit,{'order_id':2,'rating':4,'content':'价格与工时报清楚，交付准时。','photo_file_ids':[]},'owner',
            extra=['const d=pm.response.json().data;pm.expect(d.review.test_mode).to.eql(false);pm.expect(d.review.photo_file_ids).to.eql([]);'])
    # 独立公开星级授权
    c.read('publication_initial',pub2,extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(0);pm.expect(d.consented).to.eql(false);pm.expect(d.can_publish).to.eql(true);pm.expect(d.visible).to.eql(false);'],
           error=(pub2+'?publish=true',400))
    a('publication_test_order','GET',pub1,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.can_publish).to.eql(false);pm.expect(d.unavailable_reason).to.eql("TEST_ORDER");'])
    a('publication_before_review','GET','/api/order/3/review-publication',404,'boundary')
    c.write('publish',pub2,{'publish':True,'expected_version':0},'owner',method='PUT',extra=['pm.expect(pm.response.json().data.version).to.eql(1);pm.expect(pm.response.json().data.consented).to.eql(true);'])
    a('publish_stale_version','PUT',pub2,409,'boundary',{'publish':False,'expected_version':0},'owner')
    a('publish_non_boolean','PUT',pub2,400,'error',{'publish':'true','expected_version':1},'owner')
    a('publish_query','PUT',pub2+'?publish=false',400,'error',{'publish':False,'expected_version':1},'owner')
    a('publish_large_json','PUT',pub2,400,'error','{"publish":false,"expected_version":1}'+(' '*520),'owner')
    a('publication_after_publish','GET',pub2,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(1);pm.expect(d.visible).to.eql(true);pm.expect(d.consented).to.eql(true);'])
    # 公开星级列表（只认非测试 WECHAT 履约）
    c.read('ratings',ratings,extra=['const d=pm.response.json().data;pm.expect(d.summary.count).to.eql(1);pm.expect(d.items.length).to.eql(1);pm.expect(d.items[0].rating).to.eql(4);pm.expect(Object.keys(d.items[0]).sort()).to.eql(["rating","submitted_date"]);pm.expect(d.summary.rating_sum).to.eql(4);pm.expect(d.summary.average_rating).to.eql("4.00");pm.expect(Object.keys(d).sort()).to.eql(["items","merchant_id","page","page_size","summary"]);'],
           boundary=(ratings+'?page=1&page_size=1',200),error=(ratings+'?page=0',400))
    for i,query in enumerate(['rating=4','page=1&page=2','page_size=20&page_size=20','merchant_id=2']):
        a('ratings_query_'+str(i),'GET',ratings+'?'+query,400,'error')
    # 公开评价文本：预览脱敏 → 投稿 → 运营审核 → 公开展示
    c.read('text_initial',text2,extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("NONE");pm.expect(d.version).to.eql(0);pm.expect(d.public_text).to.eql(null);pm.expect(d.visible).to.eql(false);'],
           error=(text2+'?version=1',400))
    a('text_not_reviewed','GET','/api/order/3/public-review-text',404,'boundary')
    raw_text='师傅讲解清楚，电话13800001234，邮箱owner@example.com，车牌粤A12345，VIN LSVAU2180N2123456，费用1200元。'
    a('text_preview','POST',preview,200,'normal',{'public_text':raw_text},'owner',
      extra=['const d=pm.response.json().data;pm.expect(d.redaction_version).to.eql("TEXT_REDACTION_V1");pm.expect(d.public_text).not.to.include("13800001234");pm.expect(d.public_text).not.to.include("owner@example.com");pm.expect(d.public_text).not.to.include("LSVAU2180N2123456");pm.expect(d.public_text).to.include("[已隐藏]");pm.collectionVariables.set("clean_text",d.public_text);'])
    a('text_preview_empty','POST',preview,400,'error',{'public_text':''},'owner')
    a('text_preview_long','POST',preview,400,'error',{'public_text':'字'*301},'owner')
    a('text_preview_unknown','POST',preview,400,'error',{'public_text':'x','order_id':2},'owner')
    a('text_preview_max','POST',preview,200,'boundary',{'public_text':'好'*300},'owner',
      extra=['pm.expect(pm.response.json().data.public_text.length).to.eql(300);'])
    a('text_preview_wrong_role','POST',preview,403,'unauthorized',{'public_text':'非本人不可预览'},'merchant')
    a('text_submit_raw','PUT',text2,422,'boundary',{'action':'SUBMIT','expected_version':0,'public_text':raw_text,'consent_version':'R4B2_TEXT_V1'},'owner')
    c.write('text_submit',text2,{'action':'SUBMIT','expected_version':0,'public_text':'{{clean_text}}','consent_version':'R4B2_TEXT_V1'},'owner',method='PUT',
            extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("PENDING");pm.expect(d.version).to.eql(1);pm.expect(d.visible).to.eql(false);'])
    a('text_stale_version','PUT',text2,409,'boundary',{'action':'SUBMIT','expected_version':0,'public_text':'{{clean_text}}','consent_version':'R4B2_TEXT_V1'},'owner')
    a('text_consent_version','PUT',text2,400,'error',{'action':'SUBMIT','expected_version':1,'public_text':'{{clean_text}}','consent_version':'OTHER'},'owner')
    a('text_query','PUT',text2+'?version=1',400,'error',{'action':'WITHDRAW','expected_version':1},'owner')
    a('text_large_json','PUT',text2,400,'error','{"action":"WITHDRAW","expected_version":1}'+(' '*4100),'owner')
    # 运营审核
    a('admin_queue','GET',admin,200,'normal',role='reviewer',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].review_id).to.eql(2);pm.expect(d.items[0].version).to.eql(1);pm.expect(d.items[0].can_approve).to.eql(true);pm.expect(Object.keys(d.items[0]).sort()).to.eql(["can_approve","public_text","review_id","version"]);'])
    a('admin_queue_paging','GET',admin+'?page=1&page_size=20',200,'boundary',role='reviewer')
    a('admin_queue_duplicate','GET',admin+'?page=1&page=2',400,'error',role='reviewer')
    a('admin_manager_denied','GET',admin,403,'unauthorized',role='manager')
    a('admin_owner_denied','GET',admin,403,'unauthorized',role='owner')
    moderate={'expected_version':1,'decision':'APPROVE','reason_code':None}
    a('admin_moderate_bad_decision','POST',admin+'/2/moderate',400,'error',dict(moderate,decision='PUBLISH'),'reviewer')
    a('admin_moderate_unknown_reason','POST',admin+'/2/moderate',400,'error',dict(moderate,reason_code='PRIVACY'),'reviewer')
    a('admin_moderate_unknown','POST',admin+'/999/moderate',404,'boundary',moderate,'reviewer')
    a('admin_moderate_owner_denied','POST',admin+'/2/moderate',403,'unauthorized',moderate,'owner')
    c.write('admin_approve',admin+'/2/moderate',moderate,'reviewer',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("APPROVED");pm.expect(d.version).to.eql(2);'])
    # 同决策重复审核是幂等重放：返回当前态且不二次写审计
    a('admin_repeat_approve','POST',admin+'/2/moderate',200,'boundary',moderate,'reviewer',
      extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("APPROVED");pm.expect(d.version).to.eql(2);'])
    a('admin_version_conflict','POST',admin+'/2/moderate',409,'error',dict(moderate,expected_version=5),'reviewer')
    a('admin_decision_conflict','POST',admin+'/2/moderate',409,'error',dict(moderate,decision='REJECT',reason_code='PRIVACY'),'reviewer')
    c.read('public_texts',texts,extra=['const d=pm.response.json().data;pm.expect(d.merchant_id).to.eql(1);pm.expect(d.total).to.eql(1);pm.expect(d.items.length).to.eql(1);pm.expect(Object.keys(d.items[0]).sort()).to.eql(["public_id","public_text","published_date"]);pm.expect(d.items[0].public_text).to.include("[已隐藏]");'],
           boundary=(texts+'?page=1&page_size=1',200),error=(texts+'?page=1&page=2',400))
    a('public_texts_other_merchant','GET','/api/service/merchant/2/review-texts',200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    a('text_visible_after_approve','GET',text2,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("APPROVED");pm.expect(d.visible).to.eql(true);'])
    # 摘要用途授权与生成
    c.read('consent_initial',consent,extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(0);pm.expect(d.text_version).to.eql(2);pm.expect(d.consented).to.eql(false);pm.expect(d.can_consent).to.eql(true);pm.expect(d.effective).to.eql(false);'],
           error=(consent+'?consent_version=R4B2_SUMMARY_V1',400))
    a('consent_missing_text_version','PUT',consent,400,'error',{'consented':True,'expected_version':0,'consent_version':'R4B2_SUMMARY_V1'},'owner')
    a('consent_stale_version','PUT',consent,409,'boundary',{'consented':True,'expected_version':3,'expected_text_version':2,'consent_version':'R4B2_SUMMARY_V1'},'owner')
    a('consent_stale_text','PUT',consent,409,'boundary',{'consented':True,'expected_version':0,'expected_text_version':1,'consent_version':'R4B2_SUMMARY_V1'},'owner')
    c.write('consent',consent,{'consented':True,'expected_version':0,'expected_text_version':2,'consent_version':'R4B2_SUMMARY_V1'},'owner',method='PUT',
            extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(1);pm.expect(d.text_version).to.eql(2);pm.expect(d.effective).to.eql(true);'])
    a('consent_query','PUT',consent+'?consented=false',400,'error',{'consented':False,'expected_version':1,'expected_text_version':2,'consent_version':'R4B2_SUMMARY_V1'},'owner')
    a('consent_after','GET',consent,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(1);pm.expect(d.consented).to.eql(true);pm.expect(d.effective).to.eql(true);'])
    a('summary_body','POST',summary,400,'error',{'merchant_id':1},'owner')
    a('summary_anonymous','POST',summary,401,'unauthorized',{},'anonymous')
    a('summary_owner','POST',summary,200,'normal',{},'owner',
      extra=['const d=pm.response.json().data;pm.expect(d.merchant_id).to.eql(1);pm.expect(["AI_READY","FALLBACK","EMPTY","DISABLED","SOURCE_CHANGED"]).to.include(d.status);pm.expect(d.sample_count).to.be.at.most(20);if(d.status==="FALLBACK"){pm.expect(d.method).to.eql("EXACT_EXTRACT_V1");pm.expect(d.highlights.length).to.be.at.least(1);}'])
    # 撤销：星级与文字都不再公开，历史不变
    c.write('unpublish',pub2,{'publish':False,'expected_version':1},'owner',method='PUT',extra=['pm.expect(pm.response.json().data.version).to.eql(2);pm.expect(pm.response.json().data.consented).to.eql(false);'])
    a('ratings_after_withdraw','GET',ratings,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.summary).to.eql({count:0,rating_sum:0,average_rating:null});'])
    a('withdraw_text','PUT',text2,200,'boundary',{'action':'WITHDRAW','expected_version':2},'owner',
      extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("WITHDRAWN");pm.expect(d.version).to.eql(3);pm.expect(d.public_text).to.eql(null);pm.expect(d.visible).to.eql(false);'])
    a('public_texts_after_withdraw','GET',texts,200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    a('summary_after_withdraw','POST',summary,200,'boundary',{},'owner',
      extra=['const d=pm.response.json().data;pm.expect(["EMPTY","SOURCE_CHANGED","DISABLED"]).to.include(d.status);pm.expect(d.sample_count).to.eql(0);pm.expect(d.highlights).to.eql([]);pm.expect(d.sources).to.eql([]);'])
    a('text_history_kept','GET',text2,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("WITHDRAWN");pm.expect(d.version).to.eql(3);pm.expect(d.public_text).to.eql(null);'])
    return c.items


def assets():return assemble(cases(),'Autocare owner review public star text and summary','isolated_reviews_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret)
    import base64, hashlib, hmac, time
    mysql.sql("""INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES
(20,'user',1,'synthetic-review/20','image/png',1024,'CLEAN'),(21,'user',1,'synthetic-review/21','image/jpeg',2048,'CLEAN'),(22,'user',2,'synthetic-review/22','image/png',1024,'CLEAN');
INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status) VALUES
(1,'SYNTH-REVIEW-0001',1,1,1,1,12.34,12.34,'COMPLETED'),(2,'SYNTH-REVIEW-0002',1,1,1,1,20.00,20.00,'COMPLETED'),
(3,'SYNTH-REVIEW-0003',1,1,1,1,12.34,12.34,'PENDING_PAYMENT'),(4,'SYNTH-REVIEW-0004',1,1,1,1,12.34,12.34,'COMPLETED'),
(5,'SYNTH-REVIEW-0005',2,2,1,1,12.34,12.34,'COMPLETED'),(6,'SYNTH-REVIEW-0006',1,1,1,1,12.34,12.34,'COMPLETED');
INSERT INTO payment(id,order_id,channel,payment_no,channel_payment_no,amount,currency,status,paid_at) VALUES
(1,1,'LOCAL_TEST','SYNTHPAY0001','SYNTHLOCAL0001',12.34,'CNY','SUCCEEDED',UTC_TIMESTAMP()),(2,2,'WECHAT','SYNTHPAY0002','SYNTHWECHAT0002',20.00,'CNY','SUCCEEDED',UTC_TIMESTAMP()),
(3,4,'WECHAT','SYNTHPAY0004','SYNTHWECHAT0004',12.34,'CNY','SUCCEEDED',UTC_TIMESTAMP()),(4,5,'WECHAT','SYNTHPAY0005','SYNTHWECHAT0005',12.34,'CNY','SUCCEEDED',UTC_TIMESTAMP()),
(5,6,'WECHAT','SYNTHPAY0006','SYNTHWECHAT0006',12.34,'CNY','SUCCEEDED',UTC_TIMESTAMP());
INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES
('LOCAL_TEST','11111111-1111-4111-8111-111111111111',1,REPEAT('a',64),'PAID'),('WECHAT','22222222-2222-4222-8222-222222222222',2,REPEAT('b',64),'PAID'),
('WECHAT','33333333-3333-4333-8333-333333333333',3,REPEAT('c',64),'PAID'),('WECHAT','44444444-4444-4444-8444-444444444444',4,REPEAT('d',64),'PAID'),
('WECHAT','55555555-5555-4555-8555-555555555555',5,REPEAT('e',64),'PAID');
INSERT INTO order_redemption(order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES
(1,1,1,1,1,UTC_TIMESTAMP()),(2,1,1,2,0,UTC_TIMESTAMP()),(5,1,1,4,0,UTC_TIMESTAMP()),(6,1,1,5,0,UTC_TIMESTAMP());
INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,reason,from_status,status,opened_by,opened_at) VALUES(6,1,1,'synthetic open dispute','COMPLETED','OPEN',1,UTC_TIMESTAMP());""")
    mysql.sql("""INSERT INTO operator_account(id,account,password_hash,phone,status,can_community_manage,can_community_answer,can_knowledge,can_review) VALUES
(1,'synthetic-review-manager','not-a-login-password','13900000001','ACTIVE',0,0,0,0),
(2,'synthetic-review-reviewer','not-a-login-password','13900000002','ACTIVE',0,0,0,1);""")
    for role,subject in [('manager',1),('reviewer',2)]:
        session=str(uuid.uuid4())
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES('{session}','operator_account',{subject},'OPERATOR','operator-account','{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':'operator_account','role':'OPERATOR','app_id':'operator-account'}
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        tokens[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    tokens['isolated_reviews_cases']='true'
    return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('REVIEW_TEXT_PUBLICATION_ENABLED','true'),('REVIEW_SUMMARY_ENABLED','true')]


def audit(path,collection,manifest,tokens):
    import re
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    report=json.loads(path.read_text(encoding='utf-8'))
    planned={x['name']:x['request'] for x in collection['item']}
    bodies={x['item']['name']:(x['response'].get('stream') or x['response'].get('body') or '') for x in report['run']['executions']}
    raw_preview=bodies.get('text_preview') or ''
    if isinstance(raw_preview,dict):raw_preview=bytes(raw_preview['data']).decode('utf-8')
    try:preview=json.loads(raw_preview)['data']['public_text']
    except Exception:preview=None
    for x in report['run']['executions']:
        expected=planned[x['item']['name']]
        if not expected.get('body'):continue
        want=expected['body']['raw'];got=(x['request'].get('body') or {}).get('raw')
        # 占位符在执行时才被替换：字面部分必须逐字相同，替换值必须回指本次预览结果。
        parts=[];names=[];last=0
        for m in re.finditer(r'\{\{(\w+)\}\}',want):
            parts.append(re.escape(want[last:m.start()]));parts.append('(.+?)');names.append(m.group(1));last=m.end()
        parts.append(re.escape(want[last:]))
        match=None if not isinstance(got,str) else re.fullmatch(''.join(parts),got,re.S)
        if match is None:raise ValueError('Review body differs from actual expected execution')
        for name,value in zip(names,match.groups()):
            if name=='clean_text' and preview is not None and value!=preview:raise ValueError('Review body used unconfirmed redacted text')
        wanted=[h['value'] for h in expected.get('header',[]) if h['key'].lower()=='idempotency-key']
        actual=[h['value'] for h in x['request'].get('header',[]) if h['key'].lower()=='idempotency-key' and not h.get('disabled')]
        if actual!=wanted:raise ValueError('Review idempotency proof used a different key')
    return result


def verify_database(mysql):
    tables=['order_review','order_review_file','review_publication','public_review_text','public_review_text_moderation','review_summary_consent','idempotency_record','audit_log']
    counts={t:int(mysql.text('SELECT COUNT(*) FROM `'+t+'`;')) for t in tables}
    checks={
        'two_immutable_reviews':mysql.text("SELECT CONCAT(COUNT(*),':',SUM(rating=5),':',SUM(rating=4)) FROM order_review;")=='2:1:1',
        'test_flag_follows_redemption':mysql.text("SELECT GROUP_CONCAT(test_mode ORDER BY order_id) FROM order_review;")=='1,0',
        'photo_positions_kept':mysql.text("SELECT GROUP_CONCAT(CONCAT(file_id,':',position) ORDER BY position) FROM order_review_file;")=='20:0,21:1',
        'orders_still_completed':mysql.text("SELECT COUNT(*) FROM `order` WHERE status='COMPLETED';")=='5',
        'star_consent_withdrawn':mysql.text("SELECT CONCAT(consented,':',version) FROM review_publication WHERE review_id=2;")=='0:2',
        'test_order_never_public':mysql.text("SELECT COUNT(*) FROM review_publication WHERE review_id=1;")=='0',
        'text_withdrawn_but_history_kept':mysql.text("SELECT CONCAT(status,':',version,':',public_text IS NULL,':',public_id IS NULL) FROM public_review_text WHERE review_id=2;")=='WITHDRAWN:3:1:1',
        'moderation_history_kept':mysql.text("SELECT CONCAT(COUNT(*),':',SUM(decision='APPROVE')) FROM public_review_text_moderation WHERE review_id=2;")=='1:1',
        'moderation_audited_once':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='PUBLIC_REVIEW_TEXT_MODERATE' AND resource_id=2;")=='1',
        'summary_consent_recorded':mysql.text("SELECT CONCAT(version,':',consented,':',text_version,':',consent_version) FROM review_summary_consent WHERE review_id=2;")=='1:1:2:R4B2_SUMMARY_V1',
        'other_owner_untouched':mysql.text("SELECT COUNT(*) FROM order_review WHERE user_id<>1;")=='0',
    }
    if not all(checks.values()):raise ValueError('Review/publication invariant failed')
    return {'row_counts':counts,'checks':checks,'scope':'synthetic identity, order, payment, redemption and image fixtures; every review/publication write via HTTP'}


if __name__=='__main__':
    collection,manifest=assets()
    for p,d in [(OUTPUT,collection),(MANIFEST,manifest)]:p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Review cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
