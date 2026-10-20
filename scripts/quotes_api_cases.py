"""Real local merchant selection, quote versions and shelving; no supplier requests."""
import json
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from discovery_api_cases import seed as discovery_seed

OUTPUT=ROOT/'docs/testing/postman/quotes-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/quotes-case-manifest.json'
OPERATION_COUNT=4
FAIL_KEY='71444444-4444-4444-8444-444444444444'


def cases():
    c=Cases();a=c.add
    standards='/api/merchant/standard-projects';own='/api/merchant/projects';market='/api/service/project/1/merchants'
    q={'standard_project_id':1,'price':'0.01','status':1}
    def own_item(price,version,status,project=1):
        return 'const i=pm.response.json().data.items.find(x=>x.standard_project_id==='+str(project)+');pm.expect(i.price).to.eql('+json.dumps(price)+');pm.expect(i.version).to.eql('+str(version)+');pm.expect(i.status).to.eql('+str(status)+');'
    c.read('standards',standards,'merchant',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(x=>x.id)).to.eql([1,2]);'],boundary=(standards+'?page=2',200),error=(standards+'?category=0',400))
    a('standards_category','GET',standards+'?category=2',200,'boundary',role='merchant',extra=['pm.expect(pm.response.json().data.items.map(x=>x.id)).to.eql([2]);'])
    a('staff_standards','GET',standards,200,'normal',role='staff',extra=['pm.expect(pm.response.json().data.total).to.eql(2);'])
    c.read('own_initial',own,'merchant',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items.map(x=>x.standard_project_id)).to.eql([1,3,4]);pm.expect(d.items.filter(x=>x.available===false).map(x=>x.standard_project_id)).to.eql([3,4]);'],boundary=(own+'?page=2',200),error=(own+'?page=0',400))
    a('staff_own_read','GET',own,200,'normal',role='staff',extra=['pm.expect(pm.response.json().data.total).to.eql(3);'])
    a('other_store_initial','GET',own,200,'unauthorized',role='other_merchant',extra=[own_item('20.00',1,1),'pm.expect(pm.response.json().data.total).to.eql(1);'])
    for label,path,queries in [('standards',standards,['merchant_id=2','category=1&category=2','page=1&page=2','page_size=20&page_size=20']),('own',own,['merchant_id=2','page=1&page=2','page_size=20&page_size=20'])]:
        for i,query in enumerate(queries):a(label+'_query_'+str(i),'GET',path+'?'+query,400,'error',role='merchant')
    for i,price in enumerate(['0.00','-1.00','1.1','1.001','100000000.00','1e2','01.00']):a('bad_price_'+str(i),'POST',own,400,'error',dict(q,price=price),'merchant')
    for i,body in enumerate([dict(q,price=1),dict(q,status=2),dict(q,standard_project_id=0),dict(q,standard_project_id=9007199254740992),dict(q,merchant_id=2)]):a('bad_shape_'+str(i),'POST',own,400,'error',body,'merchant')
    for label,raw in [('duplicate_price','{"standard_project_id":1,"price":"0.01","price":"0.01","status":1}'),('duplicate_status','{"standard_project_id":1,"price":"0.01","status":0,"status":1}'),('trailing_json','{"standard_project_id":1,"price":"0.01","status":1} {}'),('large_json','{"standard_project_id":1,"price":"0.01","status":1}'+(' '*2049))]:a(label,'POST',own,400,'error',raw,'merchant')
    a('save_query','POST',own+'?merchant_id=2',400,'error',q,'merchant')
    a('staff_write','POST',own,403,'unauthorized',q,'staff')
    a('technician_write','POST',own,403,'unauthorized',q,'technician')
    a('unknown_project','POST',own,404,'boundary',dict(q,standard_project_id=999999),'merchant')
    c.write('price_update',own,q,'merchant',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(3);pm.expect(d.price).to.eql("0.01");'])
    a('changed_intent','POST',own,400,'error',dict(q,price='9.99'),'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:price_update')))
    c.read('market',market,extra=['const d=pm.response.json().data;pm.expect(d.items.map(x=>x.merchant_id)).to.eql([1,2]);pm.expect(d.items[0].price).to.eql("0.01");pm.expect(d.items[0].version).to.eql(3);'],boundary=(market+'?page=2',200),error=(market+'?merchant_id=2',400))
    maximum=dict(q,standard_project_id=2,price='99999999.99')
    c.write('selection_maximum',own,maximum,'merchant',extra=['const d=pm.response.json().data;pm.expect(d.merchant_project_id).to.eql(5);pm.expect(d.version).to.eql(1);pm.expect(d.price).to.eql("99999999.99");'])
    c.write('shelf_off',own,dict(q,status=0),'merchant',extra=['pm.expect(pm.response.json().data.version).to.eql(4);'])
    a('old_price_receipt','POST',own,200,'boundary',q,'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:price_update')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_price_update")));'])
    a('own_stays_off','GET',own,200,'boundary',role='merchant',extra=[own_item('0.01',4,0)])
    a('market_stays_hidden','GET',market,200,'boundary',extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([2]);'])
    c.write('shelf_on',own,dict(q,price='15.55'),'merchant',extra=['pm.expect(pm.response.json().data.version).to.eql(5);'])
    a('audit_failure','POST',own,503,'error',dict(q,price='7.77'),'merchant',key=FAIL_KEY)
    a('audit_failure_rolled_back','GET',own,200,'boundary',role='merchant',extra=[own_item('15.55',5,1)])
    a('retry_failed_key','POST',own,200,'normal',dict(q,price='7.78'),'merchant',key=FAIL_KEY,extra=['const d=pm.response.json().data;pm.expect(d.price).to.eql("7.78");pm.expect(d.version).to.eql(6);pm.collectionVariables.set("retry_receipt",JSON.stringify(pm.response.json()));'])
    a('retry_failed_key_replay','POST',own,200,'boundary',dict(q,price='7.78'),'merchant',key=FAIL_KEY,extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("retry_receipt")));'])
    for project,price,label in [(3,'33.33','inactive'),(4,'44.44','deleted')]:
        off=dict(q,standard_project_id=project,price=price,status=0)
        a(label+'_cannot_reprice','POST',own,404,'boundary',dict(off,price='55.55'),'merchant')
        a(label+'_cannot_shelve','POST',own,404,'boundary',dict(off,status=1),'merchant')
        a(label+'_cannot_select_other_store','POST',own,404,'unauthorized',off,'other_merchant')
        c.write(label+'_off',own,off,'merchant',extra=['pm.expect(pm.response.json().data.version).to.eql(2);'])
    c.write('new_selection_off',own,dict(maximum,status=0),'merchant',extra=['pm.expect(pm.response.json().data.version).to.eql(2);'])
    c.write('final_off',own,dict(q,price='7.78',status=0),'merchant',extra=['pm.expect(pm.response.json().data.version).to.eql(7);'])
    a('old_shelf_on_receipt','POST',own,200,'boundary',dict(q,price='15.55'),'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:shelf_on')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_shelf_on")));'])
    a('final_own','GET',own,200,'normal',role='merchant',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(4);pm.expect(d.items.every(x=>x.status===0)).to.eql(true);',own_item('7.78',7,0),own_item('99999999.99',2,0,2)])
    a('final_market','GET',market,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].merchant_id).to.eql(2);pm.expect(d.items[0].price).to.eql("20.00");'])
    a('other_store_final','GET',own,200,'unauthorized',role='other_merchant',extra=[own_item('20.00',1,1),'pm.expect(pm.response.json().data.total).to.eql(1);'])
    a('other_project_hidden','GET','/api/service/project/2/merchants',200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    return c.items


def assets():return assemble(cases(),'Autocare merchant quote selection versions and shelving','isolated_quotes_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=discovery_seed(mysql,secret)
    mysql.sql("""DELETE FROM merchant_project_version WHERE merchant_project_id=3;
DELETE FROM merchant_project WHERE id=3;
INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(3,1,3,33.33,1),(4,1,4,44.44,1);
INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(4,3,1,33.33,1,1),(5,4,1,44.44,1,1);""")
    tokens['isolated_quotes_cases']='true'
    return tokens


def prepare(mysql):
    mysql.sql("""DELIMITER $$
CREATE TRIGGER reject_quotes_fixture_audit BEFORE INSERT ON audit_log FOR EACH ROW
BEGIN
 IF NEW.action='MERCHANT_PROJECT_SAVE' AND JSON_UNQUOTE(JSON_EXTRACT(NEW.after_state,'$.price'))='7.77' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic quote audit failure';
 END IF;
END$$
DELIMITER ;
""")


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    planned={x['name']:x['request'] for x in collection['item']}
    for x in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        expected=planned[x['item']['name']]
        if expected.get('body') and (x['request'].get('body') or {}).get('raw')!=expected['body']['raw']:
            raise ValueError('Quote body differs from actual expected execution')
        # Bind the actual key as well: old receipts and the failed-key retry are essential cases.
        wanted=[h['value'] for h in expected.get('header',[]) if h['key'].lower()=='idempotency-key']
        actual=[h['value'] for h in x['request'].get('header',[]) if h['key'].lower()=='idempotency-key' and not h.get('disabled')]
        if actual!=wanted:raise ValueError('Quote idempotency proof used a different key')
    return result


def verify_database(mysql):
    tables=['merchant_project','merchant_project_version','idempotency_record','audit_log','order','payment','vehicle_archive']
    counts={t:int(mysql.text('SELECT COUNT(*) FROM `'+t+'`;')) for t in tables}
    if counts!=dict(zip(tables,[5,14,9,9,0,0,0])):raise ValueError('Quote/version/audit/idempotency rows differ from committed operations')
    checks={
        'own_quotes_all_off':mysql.text("SELECT COUNT(*) FROM merchant_project WHERE merchant_id=1 AND on_shelf=0;")=='4',
        'monotonic_current_versions':mysql.text("SELECT GROUP_CONCAT(CONCAT(q.id,':',v.version) ORDER BY q.id) FROM merchant_project q JOIN merchant_project_version v ON v.merchant_project_id=q.id AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id);")=='1:7,2:1,3:2,4:2,5:2',
        'other_store_untouched':mysql.text("SELECT CONCAT(price,':',on_shelf) FROM merchant_project WHERE id=2;")=='20.00:1',
        'original_versions_immutable':mysql.text("SELECT GROUP_CONCAT(CONCAT(version,':',price,':',on_shelf) ORDER BY version) FROM merchant_project_version WHERE merchant_project_id=1 AND version<=2;")=='1:12.34:1,2:13.34:1',
        'failed_audit_has_no_partial_version':mysql.text("SELECT COUNT(*) FROM merchant_project_version WHERE price=7.77;")=='0',
        'failed_key_can_be_committed_once':mysql.text("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key='"+FAIL_KEY+"' AND JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.data.price'))='7.78';")=='1',
        'exact_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_PROJECT_SAVE';")=='9',
        'inactive_and_deleted_sources_not_reactivated':mysql.text("SELECT CONCAT(SUM(status=0),':',SUM(is_deleted=1)) FROM standard_project WHERE id IN (3,4);")=='1:1',
    }
    if not all(checks.values()):raise ValueError('Quote history/tenant/replay/rollback invariant failed')
    return {'row_counts':counts,'checks':checks,'scope':'synthetic identity/catalog/prior quote fixtures and conditional audit failure; all nine subsequent quote writes via HTTP'}


if __name__=='__main__':
    collection,manifest=assets()
    for p,d in [(OUTPUT,collection),(MANIFEST,manifest)]:p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Quote cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
