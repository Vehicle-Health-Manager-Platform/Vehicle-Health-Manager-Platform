"""Store-local customer relations and conservative price comparisons: isolated read-only HTTP."""
import hashlib
import json
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from governance_api_cases import seed as governance_seed

OUTPUT=ROOT/'docs/testing/postman/relations-price-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/relations-price-case-manifest.json'
OPERATION_COUNT=4
TABLES=('user','vehicle','order','pickup_check','order_status_transition','invite_registration',
        'merchant_project','merchant_project_version','price_monitor_policy','audit_log',
        'idempotency_record','order_archive_grant','vehicle_archive')
CHECKS={'unchanged_'+t for t in TABLES}
BASELINE={}


def cases():
    c=Cases();add=c.add
    customers='/api/merchant/customer-relations';prices='/api/admin/price-monitor/comparisons'
    privacy='for(const k of ["user_id","owner_id","phone","openid","plate_no","vin","vehicle_id","order_no","amount","archives","object_key"])pm.expect(JSON.stringify(pm.response.json().data)).not.to.include("\\\""+k+"\\\"");'
    def read(name,path,role,extra,boundary,error):c.read(name,path,role,extra=extra,boundary=boundary,error=error)
    read('relations',customers,'merchant',[
        'const d=pm.response.json().data;pm.expect(d.observed_at).to.match(/^\\d{4}-\\d{2}-\\d{2}T/);const p=d.relationships;pm.expect(p.total).to.eql(23);pm.expect(p.page_size).to.eql(20);pm.expect(p.items).to.have.lengthOf(20);pm.expect(p.items[0].relationship_order_id).to.eql(120);',privacy],
        (customers+'?page=1000000',200),(customers+'?page=0',400))
    add('relations_second_page','GET',customers+'?page=2',200,'boundary',role='merchant',extra=[
        'const p=pm.response.json().data.relationships;pm.expect(p.total).to.eql(23);pm.expect(p.items.map(i=>i.relationship_order_id)).to.eql([100,8,1]);',privacy])
    add('relations_empty_page','GET',customers+'?page=1000000&page_size=20',200,'boundary',role='merchant',extra=['pm.expect(pm.response.json().data.relationships.items).to.eql([]);'])
    add('relations_other_store','GET',customers,200,'unauthorized',role='other_merchant',extra=[
        'const p=pm.response.json().data.relationships;pm.expect(p.total).to.eql(1);pm.expect(p.items.map(i=>i.relationship_order_id)).to.eql([4]);',privacy])
    add('relations_technician','GET',customers,403,'unauthorized',role='technician')
    for name,query in [('size','?page_size=21'),('duplicate','?page=1&page=2'),('injected','?merchant_id=2')]:add('relations_'+name,'GET',customers+query,400,'error',role='merchant')
    read('relation',customers+'/1','merchant',[
        'const d=pm.response.json().data;const r=d.relationship;pm.expect(r.relationship_order_id).to.eql(1);pm.expect(r.order_count).to.eql(4);pm.expect(r.identity_source).to.eql("UNKNOWN");pm.expect(r.last_service_at).to.eql("2026-10-03T10:30:00Z");pm.expect(Object.keys(r).sort()).to.eql(["identity_source","last_order_at","last_service_at","latest_vehicle","order_count","relationship_order_id"]);',
        'const p=pm.response.json().data.orders;pm.expect(p.total).to.eql(4);pm.expect(p.items.map(i=>i.order_id)).to.eql([7,3,2,1]);pm.expect(p.items.map(i=>i.service_context_available)).to.eql([false,true,false,false]);pm.expect(p.items.find(i=>i.order_id===1).service_at).to.eql(null);pm.expect(p.items.find(i=>i.order_id===3).service_at).to.eql("2026-10-03T10:30:00Z");',privacy],
        (customers+'/999999',404),(customers+'/0',400))
    for name,path,status,role,extra in [
        ('relation_empty',customers+'/1?page=1000000',200,'merchant',['const p=pm.response.json().data.orders;pm.expect(p.total).to.eql(4);pm.expect(p.items).to.eql([]);']),
        ('relation_noncanonical',customers+'/3',404,'merchant',[]),
        ('relation_foreign',customers+'/1',404,'other_merchant',[]),
        ('relation_other_anchor',customers+'/4',404,'merchant',[]),
        ('relation_technician',customers+'/1',403,'technician',[]),
        ('relation_test_identity',customers+'/8',200,'merchant',['const r=pm.response.json().data.relationship;pm.expect(r.identity_source).to.eql("TEST");pm.expect(r.last_service_at).to.eql(null);']),
        ('relation_synthetic_verified_identity',customers+'/100',200,'merchant',['pm.expect(pm.response.json().data.relationship.identity_source).to.eql("VERIFIED_WECHAT");']),
        ('relation_inactive_owner',customers+'/124',404,'merchant',[]),
        ('relation_moved_vehicle',customers+'/125',404,'merchant',[]),
        ('relation_query',customers+'/1?user_id=2',400,'merchant',[]),
        ('relation_bad_page',customers+'/1?page=0',400,'merchant',[])]:add(name,'GET',path,status,'unauthorized' if status==403 or name in ('relation_foreign','relation_other_anchor') else 'error' if status==400 else 'boundary',role=role,extra=extra)
    price_privacy='for(const k of ["actor_staff_id","submission_key","audit_id","phone","after_state"])pm.expect(JSON.stringify(pm.response.json().data)).not.to.include("\\\""+k+"\\\"");'
    read('comparisons',prices,'monitor',[
        'const p=pm.response.json().data.comparisons;pm.expect(p.total).to.eql(13);pm.expect(p.page_size).to.eql(20);pm.expect(p.items.map(i=>i.quote_id)).to.eql([13,12,11,10,9,8,7,6,5,4,3,2,1]);',price_privacy],
        (prices+'?page=1000000',200),(prices+'?page_size=50',400))
    add('comparisons_empty','GET',prices+'?page=1000000',200,'boundary',role='monitor',extra=['const p=pm.response.json().data.comparisons;pm.expect(p.items).to.eql([]);pm.expect(p.total).to.eql(13);'])
    read('comparison',prices+'/5','monitor',[
        'const r=pm.response.json().data.comparison;pm.expect(r.state).to.eql("WITHIN_RANGE");pm.expect(r.reason).to.eql("VERIFIED");pm.expect(r.review_required).to.eql(false);pm.expect(r.current.source.price).to.eql("12.34");pm.expect(r.policy.lower_price).to.eql("12.34");pm.expect(r.policy.upper_price).to.eql("12.34");',price_privacy],
        (prices+'/999999',404),(prices+'/0',400))
    for name,path in [('comparisons',prices),('comparison',prices+'/5')]:
        for role in ('catalog','plain'):add(name+'_'+role+'_denied','GET',path,403,'unauthorized',role=role)
    for name,path in [('comparisons_duplicate',prices+'?page=1&page=2'),('comparisons_injected',prices+'?quote_id=5'),('comparison_query',prices+'/5?page=1')]:add(name,'GET',path,400,'error',role='monitor')
    states=[(1,'UNKNOWN','POLICY_MISSING',False),(2,'UNKNOWN','SOURCE_UNKNOWN',False),(3,'UNKNOWN','POLICY_NOT_PUBLISHED',False),
        (4,'UNKNOWN','NOT_STARTED',False),(6,'BELOW_RANGE','VERIFIED',True),(7,'ABOVE_RANGE','VERIFIED',True),
        (8,'UNKNOWN','EXPIRED',False),(9,'UNKNOWN','POLICY_STOPPED',False),(10,'UNKNOWN','SOURCE_UNAVAILABLE',False),
        (11,'UNKNOWN','SOURCE_CHANGED',False),(12,'UNKNOWN','POLICY_SOURCE_UNKNOWN',False),(13,'UNKNOWN','POLICY_SOURCE_UNKNOWN',False)]
    for id,state,reason,review in states:
        add('comparison_state_'+str(id),'GET',prices+'/'+str(id),200,'boundary',role='monitor',extra=[
            'const r=pm.response.json().data.comparison;pm.expect(r.quote_id).to.eql('+str(id)+');pm.expect(r.state).to.eql('+json.dumps(state)+');pm.expect(r.reason).to.eql('+json.dumps(reason)+');pm.expect(r.review_required).to.eql('+str(review).lower()+');',price_privacy])
    return c.items


def assets():return assemble(cases(),'Autocare isolated customer relations and price comparisons','isolated_relations_price_cases',OPERATION_COUNT)


def literal(value):
    return "'"+json.dumps(value,ensure_ascii=False,separators=(',',':')).replace('\\','\\\\').replace("'","''")+"'"


def snapshot(mysql):
    return {t:hashlib.sha256(mysql.text('SELECT * FROM `'+t+'` ORDER BY '+('user_id' if t=='invite_registration' else 'id')+';').encode()).hexdigest() for t in TABLES}


def seed(mysql,secret):
    tokens=governance_seed(mysql,secret);tokens['isolated_relations_price_cases']='true'
    mysql.sql("""UPDATE standard_project SET source_note='synthetic-source' WHERE id=1;
UPDATE merchant_project_version SET created_at='2026-09-01 00:00:00' WHERE id=1;
INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,created_at,project_snapshot) VALUES
(1,'synthetic-relation-1',1,1,1,12.34,12.34,'PAID','2026-10-01 10:00:00','{"project_name":"synthetic service"}'),
(2,'synthetic-relation-2',1,1,1,12.34,12.34,'COMPLETED','2026-10-02 10:00:00','{}'),
(3,'synthetic-relation-3',1,1,1,12.34,12.34,'RECEIVED','2026-10-03 10:00:00','{}'),
(4,'synthetic-relation-4',1,1,2,12.34,12.34,'COMPLETED','2026-10-04 10:00:00','{}'),
(5,'synthetic-relation-5',1,1,1,12.34,12.34,'PENDING_PAYMENT','2026-10-05 10:00:00','{}'),
(6,'synthetic-relation-6',1,1,1,12.34,12.34,'CLOSED','2026-10-06 10:00:00','{}'),
(7,'synthetic-relation-7',1,1,1,12.34,12.34,'DISPUTED','2026-10-07 10:00:00','{}'),
(8,'synthetic-relation-8',2,2,1,12.34,12.34,'PAID','2026-10-08 10:00:00','{}');
UPDATE `order` SET check_in_completed_at=DATE_ADD(created_at,INTERVAL 1 HOUR) WHERE id IN (1,3);
INSERT INTO pickup_check(order_id,merchant_id,staff_id,arrived_at) VALUES(1,1,1,'2026-10-01 10:30:00'),(3,1,1,'2026-10-03 10:30:00');
INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES(3,1,'PAID','RECEIVED','ORDER_CHECK_IN','staff_account',1,'2026-10-03 11:00:00');""")
    for i in range(3,26):
        mysql.sql(f"INSERT INTO user(id,openid,status) VALUES({i},'synthetic-relation-user-{i}',{2 if i==24 else 1});INSERT INTO vehicle(id,user_id,model_id) VALUES({i},{1 if i==25 else i},1);")
        order=i+97 if i<=23 else i+100
        mysql.sql(f"INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,created_at,project_snapshot) VALUES({order},'synthetic-relation-{order}',{i},{i},1,10,10,'PAID','2026-10-10 10:00:00','{{}}');")
    for user,identity in [(2,'TEST'),(3,'VERIFIED_WECHAT')]:
        mysql.sql(f"INSERT INTO invite_registration(user_id,record_id,app_id,identity_source,attribution_result,qualification_status,registered_at,rule_version) VALUES({user},UUID(),'synthetic-read-fixture','{identity}','NONE','PENDING','2026-10-01 00:00:00','R6A_INVITE_V1');")
    for id in range(2,14):
        mysql.sql(f"INSERT INTO standard_project(id,project_name,category,service_content,quality_standard,source_note,base_price_low,base_price_high,status) VALUES({id},'synthetic service {id}',1,'synthetic inspection','','synthetic-source',1,100,1);INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES({id},1,{id},12.34,1);INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id,created_at) VALUES({id},{id},1,12.34,1,1,'2026-09-01 00:00:00');")
        proof=dict(merchant_project_id=id,version_id=id,version=1,price='12.34',status=1)
        mysql.sql(f"INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('staff_account',1,'MERCHANT_PROJECT_SAVE','merchant_project',{id},'{{}}',{literal(proof)},'synthetic-read-quote-{id}');")
    for id in range(3,14):
        source=dict(quote_id=id,quote_version_id=id,quote_version=1,price='12.34',merchant=dict(id=1,name='synthetic shop',region_code=''),
            project=dict(id=id,revision=1,project_name='synthetic service '+str(id),service_content='synthetic inspection',quality_standard='',source_note='synthetic-source'))
        audit_id=int(mysql.text(f"SELECT id FROM audit_log WHERE action='MERCHANT_PROJECT_SAVE' AND resource_id={id};"))
        evidence=dict(source=source,quoted_at='2026-09-01T00:00:00Z',actor=1,audit_id=audit_id)
        digest=hashlib.sha256(json.dumps(evidence,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
        status='DRAFT' if id==3 else 'STOPPED' if id==9 else 'PUBLISHED'
        lower,upper=('12.35','20.00') if id==6 else ('10.00','12.33') if id==7 else ('12.34','12.34')
        start='2999-01-01T00:00:00Z' if id==4 else '2026-10-01T00:00:00Z';end='3000-01-01T00:00:00Z' if id==4 else '2026-10-02T00:00:00Z' if id==8 else '2999-01-01T00:00:00Z'
        published=None if status=='DRAFT' else '2026-09-02T00:00:00Z';stopped='2026-09-03T00:00:00Z' if status=='STOPPED' else None
        policy=dict(id=id,quote_id=id,source_hash=digest,source_snapshot=source,lower_price=lower,upper_price=upper,price_unit='CNY/service',applicability_note='synthetic scope',source_note='synthetic policy',scope_confirmed=True,revision=1 if status=='DRAFT' else 3 if status=='STOPPED' else 2,status=status,rule_version='R8B_PRICE_SOURCE_V1',valid_from=start,valid_to=end,created_at='2026-09-01T00:00:00Z',published_at=published,stopped_at=stopped)
        sqltime=lambda s:'NULL' if s is None else "'"+s.replace('T',' ').replace('Z','')+"'"
        mysql.sql(f"INSERT INTO price_monitor_policy(id,quote_id,source_hash,source_snapshot,lower_price,upper_price,price_unit,applicability_note,source_note,valid_from,valid_to,created_by,submission_key,submission_hash,created_at,published_at,stopped_at,revision,status) VALUES({id},{id},'{digest}',{literal(source)},{lower},{upper},'CNY/service','synthetic scope','synthetic policy',{sqltime(start)},{sqltime(end)},2,UUID(),'{digest}','2026-09-01 00:00:00',{sqltime(published)},{sqltime(stopped)},{policy['revision']},'{status}');")
        if status!='DRAFT':mysql.sql(f"INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('operator_account',2,'PRICE_POLICY_STATUS','price_monitor_policy',{id},'{{}}',{literal(policy)},'synthetic-read-policy-{id}');")
    mysql.sql("""UPDATE standard_project SET source_note='' WHERE id=2;
UPDATE merchant_project SET on_shelf=0 WHERE id=10;
UPDATE merchant SET region_code='440100' WHERE id=1;
UPDATE audit_log SET after_state=JSON_SET(after_state,'$.source_snapshot.merchant.region_code','440100') WHERE action='PRICE_POLICY_STATUS' AND resource_id<>11;
UPDATE price_monitor_policy SET source_snapshot=JSON_SET(source_snapshot,'$.merchant.region_code','440100') WHERE quote_id<>11;
UPDATE audit_log SET is_deleted=1 WHERE action='PRICE_POLICY_STATUS' AND resource_id=12;
UPDATE price_monitor_policy SET rule_version='SYNTHETIC_INVALID' WHERE id=13;""")
    # Region belongs to the hashed source. Keep all but quote 11 bound to the final region.
    for id in range(3,14):
        if id==11:continue
        row=json.loads(mysql.text(f"SELECT source_snapshot FROM price_monitor_policy WHERE id={id};"))
        audit_id=int(mysql.text(f"SELECT id FROM audit_log WHERE action='MERCHANT_PROJECT_SAVE' AND resource_id={id};"))
        digest=hashlib.sha256(json.dumps(dict(source=row,quoted_at='2026-09-01T00:00:00Z',actor=1,audit_id=audit_id),sort_keys=True,separators=(',',':')).encode()).hexdigest()
        mysql.sql(f"UPDATE price_monitor_policy SET source_hash='{digest}' WHERE id={id};UPDATE audit_log SET after_state=JSON_SET(after_state,'$.source_hash','{digest}') WHERE action='PRICE_POLICY_STATUS' AND resource_id={id};")
    BASELINE.clear();BASELINE.update(snapshot(mysql));return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        request=e['request']
        if request.get('body') or any(h['key'].lower()=='idempotency-key' for h in request.get('header',[]) if not h.get('disabled')):raise ValueError('Read-only request carried body or write intent')
    result['read_only_intents_verified']=True;return result


def verify_database(mysql):
    after=snapshot(mysql);checks={'unchanged_'+t:t in BASELINE and after[t]==BASELINE[t] for t in TABLES}
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Read-only business content changed')
    return dict(checks=checks,scope='SQL synthetic identities, historical orders and quote/policy audit fixtures; real HTTP reads only')


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Relations/price cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
