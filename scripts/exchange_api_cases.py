"""Real earned points -> atomic coupon exchange, with synthetic identity fixtures only."""
import json
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT,assemble
from points_api_cases import seed as points_seed,environment as points_environment,audit as body_audit,replay_key

OUTPUT=ROOT/'docs/testing/postman/exchange-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/exchange-case-manifest.json'
OPERATION_COUNT=17
CAT='/api/admin/point/exchange-catalog'


def cases():
    c=Cases();a=c.add;p=CAT;offers='/api/point/exchange-catalog';x='/api/point/exchange'
    private='for(const k of ["identity_session","submission_key","submission_hash","creation_result","source_hash","refresh_hash"])pm.expect(pm.response.text()).not.to.include(k);'
    c.read('empty_catalog',p,'points',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],boundary=(p+'?page=1000000',200),error=(p+'?page=0',400))
    c.read('empty_sources','/api/admin/point/exchange-sources','points',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],boundary=('/api/admin/point/exchange-sources?page=1000000',200),error=('/api/admin/point/exchange-sources?user_id=2',400))
    c.read('empty_offers',offers,extra=['pm.expect(pm.response.json().data.items).to.eql([]);'],boundary=(offers+'?page=1000000',200),error=(offers+'?page=0',400))
    a('coupon_operator_cannot_catalog','GET',p,403,'unauthorized',role='coupon')
    terms={'name':'SYNTHETIC exchange cash','type':'DISCOUNT','project_id':1,'amount':'5.00','min_spend':'10.00','total_quantity':2,'valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('cash_create','/api/merchant/coupons',{'terms':terms},'merchant')
    c.write('cash_publish','/api/merchant/coupons/1/status',{'expected_revision':1,'status':'PUBLISHED'},'merchant',method='PUT')
    c.write('cash_pool','/api/admin/coupons/1/pool',{'expected_pool_revision':0,'selected':True},'coupon',method='PUT')
    service=dict(terms,name='SYNTHETIC exchange service',type='SERVICE',amount='0.00',min_spend='0.00',total_quantity=1)
    c.write('service_create','/api/merchant/coupons',{'terms':service},'merchant')
    c.write('service_publish','/api/merchant/coupons/2/status',{'expected_revision':1,'status':'PUBLISHED'},'merchant',method='PUT')
    c.write('service_pool','/api/admin/coupons/2/pool',{'expected_pool_revision':0,'selected':True},'coupon',method='PUT')
    a('points_cannot_pool','PUT','/api/admin/coupons/1/pool',403,'unauthorized',{'expected_pool_revision':1,'selected':False},'points')
    a('selected_sources','GET','/api/admin/point/exchange-sources',200,'normal',role='points',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(x=>x.terms.type)).to.have.members(["DISCOUNT","SERVICE"]);',private])
    config={'coupon_id':1,'publication_revision':2,'pool_revision':1,'points_cost':8,'per_owner_limit':1,'source_note':'SYNTHETIC exchange protocol fixture','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('cash_catalog',p,config,'points',extra=['pm.expect(pm.response.json().data.id).to.eql(1);',private])
    for name,body,status in [('invalid_cost',dict(config,points_cost=0),400),('invalid_limit',dict(config,per_owner_limit=0),400),('invalid_source_revision',dict(config,publication_revision=3),409),('invalid_pool_revision',dict(config,pool_revision=2),409),('unknown_source',dict(config,coupon_id=999999),404),('client_privilege',dict(config,can_points=True),400)]:
        a(name,'POST',p,status,'error' if status==400 else 'boundary',body,'points')
    changed=dict(config,expected_revision=1,points_cost=6)
    c.write('cash_update',p+'/1',changed,'points',method='PUT')
    a('stale_catalog','PUT',p+'/1',409,'boundary',changed,'points')
    a('missing_catalog','PUT',p+'/999999',404,'boundary',changed,'points')
    c.write('cash_catalog_publish',p+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'points',method='PUT')
    a('published_immutable','PUT',p+'/1',409,'boundary',dict(changed,expected_revision=3),'points')
    c.write('service_catalog',p,dict(config,coupon_id=2,points_cost=9,per_owner_limit=2),'points')
    c.write('service_catalog_publish',p+'/2/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    a('unfunded_quote','GET',offers,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(0);pm.expect(d.legacy_state).to.eql("REVIEW_REQUIRED");pm.expect(d.items.every(x=>x.eligibility==="INSUFFICIENT_POINTS")).to.eql(true);'])
    cash={'catalog_id':1,'catalog_revision':3};svc={'catalog_id':2,'catalog_revision':2}
    a('unfunded_exchange','POST',x,409,'boundary',cash)
    a('unverified_exchange','POST',x,409,'unauthorized',cash,'other')
    policy={'task_type':'CHECK_IN','points':20,'source_note':'SYNTHETIC earned exchange fixture','valid_from':'{{valid_from}}','valid_to':'{{valid_to}}'}
    c.write('earning_policy','/api/admin/point/task-policies',policy,'points')
    c.write('earning_publish','/api/admin/point/task-policies/1/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    c.write('earn_checkin','/api/point/check-in',{'day':'{{point_day}}','policy_id':1,'policy_revision':2})
    c.read('earned_overview','/api/point/overview',extra=['pm.expect(pm.response.json().data.balance).to.eql(20);'],boundary=('/api/point/overview',200),error=('/api/point/overview?user_id=2',400))
    # Allow the real recovery worker to run before reading its committed credit.
    next(i for i in c.items if i['name']=='earned_overview')['event'].insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':['setTimeout(()=>{},1200);']}})
    a('eligible_quotes','GET',offers,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(20);pm.expect(d.exchange_available).to.eql(true);pm.expect(d.items.every(x=>x.eligibility==="ELIGIBLE")).to.eql(true);',private])
    a('other_quote','GET',offers,200,'unauthorized',role='other',extra=['const d=pm.response.json().data;pm.expect(d.balance).to.eql(0);pm.expect(d.exchange_available).to.eql(false);pm.expect(d.items.every(x=>x.exchanged_count===0)).to.eql(true);'])
    a('wrong_catalog_revision','POST',x,409,'boundary',dict(cash,catalog_revision=2))
    c.write('cash_exchange',x,cash,extra=['const d=pm.response.json().data;pm.expect(d.exchange_id).to.eql(1);pm.expect(d.points_spent).to.eql(6);pm.expect(d.balance_after).to.eql(14);pm.expect(d.catalog.terms.type).to.eql("DISCOUNT");',private])
    a('same_key_changed_catalog','POST',x,400,'error',svc,key=replay_key('cash_exchange'))
    a('cash_lifetime_limit','POST',x,409,'boundary',cash)
    c.write('cash_stop',p+'/1/status',{'expected_revision':3,'status':'STOPPED'},'points',method='PUT')
    a('old_cash_publish','PUT',p+'/1/status',200,'boundary',{'expected_revision':2,'status':'PUBLISHED'},'points',key=replay_key('cash_catalog_publish'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_cash_catalog_publish")));'])
    a('stopped_cannot_republish','PUT',p+'/1/status',409,'boundary',{'expected_revision':4,'status':'PUBLISHED'},'points')
    c.write('replacement_catalog',p,dict(config,points_cost=4),'points')
    c.write('replacement_publish',p+'/3/status',{'expected_revision':1,'status':'PUBLISHED'},'points',method='PUT')
    a('replacement_lifetime_limit','POST',x,409,'boundary',{'catalog_id':3,'catalog_revision':2})
    a('replacement_quote','GET',offers,200,'boundary',extra=['const n=pm.response.json().data.items.find(x=>x.catalog.id===3);pm.expect(n.eligibility).to.eql("LIMIT_REACHED");pm.expect(n.exchanged_count).to.eql(1);pm.expect(n.remaining_limit).to.eql(0);'])
    c.write('service_exchange',x,svc,extra=['const d=pm.response.json().data;pm.expect(d.exchange_id).to.eql(2);pm.expect(d.points_spent).to.eql(9);pm.expect(d.balance_after).to.eql(5);pm.expect(d.catalog.terms.type).to.eql("SERVICE");'])
    a('service_sold_out','POST',x,409,'boundary',svc)
    a('sold_out_quote','GET',offers,200,'boundary',extra=['pm.expect(pm.response.json().data.items.find(x=>x.catalog.id===2).eligibility).to.eql("OUT_OF_STOCK");'])
    c.write('service_remove_pool','/api/admin/coupons/2/pool',{'expected_pool_revision':1,'selected':False},'coupon',method='PUT')
    a('source_changed_quote','GET',offers,200,'boundary',extra=['pm.expect(pm.response.json().data.items.find(x=>x.catalog.id===2).eligibility).to.eql("SOURCE_CHANGED");'])
    c.write('service_restore_pool','/api/admin/coupons/2/pool',{'expected_pool_revision':2,'selected':True},'coupon',method='PUT')
    a('old_pool_version_not_restored','GET',offers,200,'boundary',extra=['pm.expect(pm.response.json().data.items.find(x=>x.catalog.id===2).eligibility).to.eql("SOURCE_CHANGED");'])
    a('changed_source_exchange','POST',x,409,'boundary',svc)
    c.write('service_source_stop','/api/merchant/coupons/2/status',{'expected_revision':2,'status':'STOPPED'},'merchant',method='PUT')
    for name,body,key in [('cash_receipt_after_stop',cash,'cash_exchange'),('service_receipt_after_source_stop',svc,'service_exchange')]:
        a(name,'POST',x,200,'boundary',body,key=replay_key(key),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_'+key+'")));'])
    c.write('service_catalog_stop',p+'/2/status',{'expected_revision':2,'status':'STOPPED'},'points',method='PUT')
    c.write('replacement_stop',p+'/3/status',{'expected_revision':2,'status':'STOPPED'},'points',method='PUT')
    a('no_active_offers','GET',offers,200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);pm.expect(pm.response.json().data.balance).to.eql(5);'])
    c.read('history','/api/point/exchanges',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(x=>x.points_spent)).to.eql([9,6]);pm.expect(d.items.map(x=>x.balance_after)).to.eql([5,14]);',private],boundary=('/api/point/exchanges?page=1000000',200),error=('/api/point/exchanges?page=0',400))
    a('other_history','GET','/api/point/exchanges',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c.read('coupons_arrived','/api/coupon/mine',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.every(x=>x.source_known&&x.status==="AVAILABLE"&&x.availability==="AVAILABLE")).to.eql(true);pm.expect(d.items.map(x=>x.terms.type)).to.have.members(["DISCOUNT","SERVICE"]);',private],boundary=('/api/coupon/mine?page=1000000',200),error=('/api/coupon/mine?page=0',400))
    a('other_coupons','GET','/api/coupon/mine',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    c.read('ledger','/api/point/flow',extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items.map(x=>x.delta)).to.eql([-9,-6,20]);pm.expect(d.items.map(x=>x.balance_after)).to.eql([5,14,20]);',private],boundary=('/api/point/flow?page=1000000',200),error=('/api/point/flow?page=0',400))
    a('final_catalog','GET',p,200,'normal',role='points',extra=['const i=pm.response.json().data.items;pm.expect(i).to.have.lengthOf(3);pm.expect(i.every(x=>x.status==="STOPPED")).to.eql(true);pm.expect(i.map(x=>x.revision)).to.eql([3,3,4]);'])
    for name,path,role in [('offers_duplicate',offers+'?page=1&page=2','owner'),('history_selector','/api/point/exchanges?user_id=2','owner'),('sources_duplicate','/api/admin/point/exchange-sources?page=1&page=2','points')]:a(name,'GET',path,400,'error',role=role)
    a('exchange_query','POST',x+'?user_id=2',400,'error',cash)
    a('catalog_query','POST',p+'?coupon_id=2',400,'error',config,'points')
    a('coupon_operator_cannot_create','POST',p,403,'unauthorized',config,'coupon')
    return c.items


def assets():return assemble(cases(),'Autocare earned points and atomic cash/service coupon exchange','isolated_exchange_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=points_seed(mysql,secret)
    mysql.sql('UPDATE operator_account SET can_review=0,can_coupon=1 WHERE id=5;')
    result['coupon_token']=result.pop('reviewer_token');result['isolated_exchange_cases']='true'
    return result


def environment(tokens):return [(k,'true' if k=='POINTS_EXCHANGE_ENABLED' else v) for k,v in points_environment(tokens)]


def audit(path,collection,manifest,tokens):return body_audit(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)


def verify_database(mysql):
    tables={'catalogs':'point_exchange_catalog','exchanges':'point_exchange_record','accounts':'point_verified_account','entries':'point_verified_entry','task_events':'point_task_event','user_coupons':'user_coupon','issues':'coupon_issue','stock_events':'coupon_stock_event','payments':'payment'}
    actual={k:int(mysql.text('SELECT COUNT(*) FROM `'+v+'`;')) for k,v in tables.items()}
    if actual!={'catalogs':3,'exchanges':2,'accounts':1,'entries':3,'task_events':1,'user_coupons':2,'issues':2,'stock_events':2,'payments':0}:raise ValueError('Exchange atomic rows differ: '+str(actual))
    checks={
        'earned_then_spent_once':mysql.text('SELECT CONCAT(balance,\':\',revision) FROM point_verified_account WHERE user_id=1;')=='5:3',
        'debit_flow_matches_receipts':mysql.text("SELECT COUNT(*) FROM point_exchange_record x JOIN point_verified_entry l ON l.exchange_id=x.id AND l.user_id=x.user_id AND l.delta=-x.points_cost AND l.balance_after=JSON_EXTRACT(x.creation_result,'$.balance_after');")=='2',
        'coupon_issue_links_exchange':mysql.text("SELECT COUNT(*) FROM coupon_issue i JOIN point_exchange_record x ON x.id=i.points_exchange_id AND x.user_coupon_id=i.user_coupon_id AND x.user_id=i.user_id AND x.coupon_id=i.coupon_id JOIN user_coupon u ON u.id=i.user_coupon_id AND u.user_id=x.user_id AND u.status='AVAILABLE' WHERE i.origin='POINTS';")=='2',
        'stock_charged_once':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',issued_quantity) ORDER BY id) FROM coupon;")=='1:1,2:1',
        'catalogs_stay_stopped':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status,':',revision) ORDER BY id) FROM point_exchange_catalog;")=='1:STOPPED:4,2:STOPPED:3,3:STOPPED:3',
        'frozen_exchange_terms':mysql.text("SELECT GROUP_CONCAT(CONCAT(catalog_id,':',catalog_revision,':',points_cost) ORDER BY id) FROM point_exchange_record;")=='1:3:6,2:2:9',
        'legacy_untouched':mysql.text('SELECT point_balance FROM user WHERE id=1;')=='99999',
        'other_owner_untouched':mysql.text('SELECT COUNT(*) FROM point_exchange_record WHERE user_id<>1;')=='0' and mysql.text('SELECT COUNT(*) FROM user_coupon WHERE user_id<>1;')=='0',
        'distinct_operator_permissions':mysql.text('SELECT COUNT(*) FROM operator_account WHERE can_points=1 AND can_coupon=1;')=='0',
        'two_exchange_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='POINT_EXCHANGE';")=='2',
    }
    if not all(checks.values()):raise ValueError('Exchange balance/stock/frozen terms/identity invariant failed')
    return {'row_counts':actual,'checks':checks,'scope':'synthetic verified identity; real earned 20 points, two atomic exchanges; no external supplier or money'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Point exchange cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
