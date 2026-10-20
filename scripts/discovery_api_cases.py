"""Local service discovery and confirmed shop locations; no map supplier or device calls."""
import base64
import hashlib
import hmac
import json
import math
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/discovery-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/discovery-case-manifest.json'
OPERATION_COUNT=9
POINT={'lng':113.1234567,'lat':23.7654321,'coordinate_system':'GCJ02'}
STORE={'lng':'113','lat':'23','coordinate_system':'GCJ02','source':'MERCHANT_CONFIRMED'}
CLEAR={'lng':None,'lat':None,'coordinate_system':None,'source':None}


def meters(lng,lat):
    a=math.sin(math.radians(lat-POINT['lat'])/2)**2+math.cos(math.radians(POINT['lat']))*math.cos(math.radians(lat))*math.sin(math.radians(lng-POINT['lng'])/2)**2
    return math.floor(6371008.8*2*math.asin(math.sqrt(min(1,max(0,a))))+0.5)


def cases():
    c=Cases();a=c.add
    def safe(name,method,path,body,extra=(),boundary=None):
        a(name,method,path,200,'normal',body,extra=extra)
        a(name+'_boundary',method,path,200 if boundary is None else boundary[1],'boundary',dict(body,page=2) if boundary is None else boundary[0])
        a(name+'_invalid',method,path,400,'error',{})
        a(name+'_anonymous',method,path,401,'unauthorized',body,'anonymous')
        a(name+'_wrong_role',method,path,403,'unauthorized',body,'merchant')
    projects='/api/service/projects';project='/api/service/project/1';quotes=project+'/merchants';shop='/api/service/merchant/1';loc='/api/merchant/location'
    c.read('projects',projects,extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(x=>x.id)).to.eql([1,2]);'],boundary=(projects+'?page=2',200),error=(projects+'?category=0',400))
    a('category_filter','GET',projects+'?category=2',200,'boundary',extra=['pm.expect(pm.response.json().data.items.map(x=>x.id)).to.eql([2]);'])
    c.read('project_detail',project,extra=['const d=pm.response.json().data;pm.expect(d.id).to.eql(1);pm.expect(d.base_price_low).to.eql("1.00");pm.expect(d.base_price_high).to.eql("100.00");'],boundary=('/api/service/project/3',404),error=('/api/service/project/0',400))
    a('deleted_project','GET','/api/service/project/4',404,'boundary')
    c.read('quotes',quotes,extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items.map(x=>x.merchant_id)).to.eql([3,1,2]);const v=d.items.find(x=>x.merchant_id===1);pm.expect(v.version).to.eql(2);pm.expect(v.price).to.eql("13.34");'],boundary=(quotes+'?page=2',200),error=(quotes+'?sort=rating',400))
    a('quotes_descending','GET',quotes+'?sort=price_desc',200,'boundary',extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([2,1,3]);'])
    a('quotes_region','GET',quotes+'?region_code=440305',200,'boundary',extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([3,1]);'])
    a('quotes_empty_region','GET',quotes+'?region_code=000000',200,'boundary',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    a('quotes_inactive_project','GET','/api/service/project/3/merchants',404,'boundary')
    c.read('storefront',shop,extra=['const d=pm.response.json().data;pm.expect(Object.keys(d.merchant).sort()).to.eql(["address","merchant_id","merchant_type","name","region_code"].sort());pm.expect(d.offers.total).to.eql(1);pm.expect(d.offers.items[0].version).to.eql(2);pm.expect(pm.response.text()).not.to.include("PRIVATE_");'],boundary=('/api/service/merchant/999999',404),error=(shop+'?project_id=0',400))
    a('storefront_filtered_empty','GET',shop+'?project_id=2',200,'boundary',extra=['pm.expect(pm.response.json().data.offers.total).to.eql(0);'])
    a('storefront_inactive_project','GET',shop+'?project_id=3',404,'boundary')
    for label,path,queries in [('projects',projects,['owner_id=2','category=1&category=2','page=1&page=2','page_size=20&page_size=20']),('project',project,['owner_id=2','page=1']),('quotes',quotes,['merchant_id=2','sort=price_asc&sort=price_desc','region_code=440305&region_code=440306','page=1&page=1']),('storefront',shop,['merchant_id=2','project_id=1&project_id=2','page_size=20&page_size=20'])]:
        for i,q in enumerate(queries):a(label+'_query_'+str(i),'GET',path+'?'+q,400,'error')
    search='/api/service/project-search';body={'query':'synthetic','category':0,'page':1}
    safe('search','POST',search,body,extra=['const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].id).to.eql(1);'])
    for label,q in [('percent','%'),('underscore','_')]:a('literal_'+label,'POST',search,200,'boundary',dict(body,query=q),extra=['pm.expect(pm.response.json().data.items.map(x=>x.id)).to.eql([2]);'])
    a('search_empty_category','POST',search,200,'boundary',dict(body,category=3),extra=['pm.expect(pm.response.json().data.total).to.eql(0);'])
    a('search_duplicate_field','POST',search,400,'error','{"query":"synthetic","query":"%","category":0,"page":1}')
    a('search_long_query','POST',search,400,'error',dict(body,query='x'*81))
    a('search_query_selector','POST',search+'?owner_id=2',400,'error',body)
    c.read('location_initial',loc,'merchant',extra=['const d=pm.response.json().data;pm.expect(d.can_edit).to.eql(true);pm.expect(d.location).to.eql(null);'],boundary=(loc,200),error=(loc+'?merchant_id=2',400))
    a('staff_location_read','GET',loc,200,'normal',role='staff',extra=['pm.expect(pm.response.json().data.can_edit).to.eql(false);'])
    c.write('location_set',loc,STORE,'merchant',method='PUT',extra=['pm.expect(pm.response.json().data.location.version).to.eql(1);'])
    a('location_changed_intent','PUT',loc,400,'error',dict(STORE,lng='114'),'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:location_set')))
    a('staff_location_write','PUT',loc,403,'unauthorized',STORE,'staff')
    a('location_duplicate_json','PUT',loc,400,'error','{"lng":"113","lng":"114","lat":"23","coordinate_system":"GCJ02","source":"MERCHANT_CONFIRMED"}','merchant')
    a('location_untrusted_source','PUT',loc,400,'error',dict(STORE,source='GPS'),'merchant')
    a('location_out_of_range','PUT',loc,400,'error',dict(STORE,lat='91'),'merchant')
    a('location_write_query','PUT',loc+'?merchant_id=2',400,'error',STORE,'merchant')
    a('other_store_before','GET',loc,200,'unauthorized',role='other_merchant',extra=['pm.expect(pm.response.json().data.location).to.eql(null);'])
    c.write('other_location_set',loc,dict(STORE,lng='114',lat='24'),'other_merchant',method='PUT')
    a('own_store_unchanged','GET',loc,200,'unauthorized',role='merchant',extra=['pm.expect(pm.response.json().data.location.lng).to.eql("113");pm.expect(pm.response.json().data.location.lat).to.eql("23");'])
    nearby=project+'/nearby-merchants';near=dict(POINT,sort='distance_asc',region_code=None,page=1,page_size=20)
    safe('nearby','POST',nearby,near,extra=['const i=pm.response.json().data.items;pm.expect(i.map(x=>x.merchant_id)).to.eql([1,2,3]);pm.expect(i.map(x=>x.distance_meters)).to.eql('+json.dumps([meters(113,23),meters(114,24),None])+');pm.expect(Object.keys(i[0]).sort()).to.eql(["merchant_project_id","merchant_id","merchant_name","address","price","version_id","version","distance_meters","location_source"].sort());'])
    a('nearby_price_sort','POST',nearby,200,'boundary',dict(near,sort='price_asc'),extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([3,1,2]);'])
    a('nearby_region','POST',nearby,200,'boundary',dict(near,region_code='440305'),extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([1,3]);'])
    a('nearby_selector','POST',nearby+'?owner_id=2',400,'error',near)
    a('nearby_bad_coordinates','POST',nearby,400,'error',dict(near,lng=181))
    a('nearby_foreign_coordinate_system','POST',nearby,400,'error',dict(near,coordinate_system='WGS84'))
    a('nearby_unknown_project','POST','/api/service/project/999999/nearby-merchants',404,'boundary',near)
    dist=shop+'/distance'
    safe('distance','POST',dist,POINT,extra=['const d=pm.response.json().data;pm.expect(d.distance_meters).to.eql('+str(meters(113,23))+');pm.expect(Object.keys(d).sort()).to.eql(["merchant_id","distance_meters","location_source"].sort());'],boundary=(dict(POINT,lng=180,lat=90),200))
    a('distance_unknown_location','POST','/api/service/merchant/3/distance',200,'boundary',POINT,extra=['pm.expect(pm.response.json().data.distance_meters).to.eql(null);pm.expect(pm.response.json().data.location_source).to.eql(null);'])
    a('distance_unknown_merchant','POST','/api/service/merchant/999999/distance',404,'boundary',POINT)
    a('distance_query','POST',dist+'?lat=23',400,'error',POINT)
    a('distance_string_coordinate','POST',dist,400,'error',dict(POINT,lng='113'))
    c.write('location_clear',loc,CLEAR,'merchant',method='PUT',extra=['pm.expect(pm.response.json().data.location).to.eql(null);'])
    a('old_location_receipt','PUT',loc,200,'boundary',STORE,'merchant',key=str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:location_set')),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_location_set")));'])
    a('location_stays_clear','GET',loc,200,'boundary',role='merchant',extra=['pm.expect(pm.response.json().data.location).to.eql(null);'])
    a('distance_after_clear','POST',dist,200,'boundary',POINT,extra=['pm.expect(pm.response.json().data.distance_meters).to.eql(null);'])
    a('nearby_after_clear','POST',nearby,200,'boundary',near,extra=['pm.expect(pm.response.json().data.items.map(x=>x.merchant_id)).to.eql([2,1,3]);'])
    return c.items


def assets():return assemble(cases(),'Autocare local service discovery and confirmed locations','isolated_discovery_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret)
    mysql.sql("""DELETE FROM file_object;
UPDATE merchant SET region_code='440305' WHERE id=1;
UPDATE merchant SET region_code='440306' WHERE id=2;
INSERT INTO merchant(id,merchant_type,name,address,status,region_code) VALUES(3,2,'synthetic no location','synthetic address',1,'440305');
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(4,1,'STAFF','synthetic-discovery-staff','ACTIVE'),(5,3,'MERCHANT','synthetic-discovery-third','ACTIVE');
INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status,is_deleted) VALUES(2,'literal 100%_ item',2,'literal reference',2,200,1,0),(3,'synthetic disabled',1,'synthetic disabled',1,100,0,0),(4,'synthetic deleted',1,'synthetic deleted',1,100,1,1);
UPDATE merchant_project SET price=13.34 WHERE id=1;
INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(2,1,2,13.34,1,1);
INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(2,2,1,20,1),(3,3,1,9,1);
INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(3,2,1,20,1,2),(4,3,1,9,1,5);""")
    session=str(uuid.uuid4());mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES('{session}','staff_account',4,'STAFF','merchant-account',1,'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
    claims={'iss':'vehicle-health-manager','sub':'4','iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':'staff_account','role':'STAFF','app_id':'merchant-account','merchant_id':1}
    enc=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
    raw=enc({'alg':'HS256','typ':'JWT'})+'.'+enc(claims)
    tokens['staff_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    tokens['isolated_discovery_cases']='true'
    return tokens


def environment(tokens):return [('MERCHANT_STAFF_ENABLED','true'),('WECHAT_APP_ID','fulfillment-cases')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    # POST coordinates must remain in the actual body, not merely the planned collection.
    executions=json.loads(path.read_text(encoding='utf-8'))['run']['executions']
    planned={x['name']:x['request'] for x in collection['item']}
    for x in executions:
        request=planned[x['item']['name']]
        if request.get('body') and (x['request'].get('body') or {}).get('raw')!=request['body']['raw']:
            raise ValueError('Discovery request body differs from planned source')
    return result


def verify_database(mysql):
    tables=['merchant_location','merchant_project','merchant_project_version','standard_project','idempotency_record','audit_log','order','payment','vehicle_archive']
    counts={t:int(mysql.text('SELECT COUNT(*) FROM `'+t+'`;')) for t in tables}
    expected=dict(zip(tables,[2,3,4,4,3,3,0,0,0]))
    if counts!=expected:raise ValueError('Discovery location/catalog rows or idempotency records changed unexpectedly')
    checks={
        'own_location_stays_cleared':mysql.text('SELECT CONCAT(version,\':\',IF(lng IS NULL AND lat IS NULL AND coordinate_system IS NULL AND source IS NULL,1,0)) FROM merchant_location WHERE merchant_id=1;')=='2:1',
        'other_store_unchanged':mysql.text("SELECT CONCAT(version,':',lng,':',lat) FROM merchant_location WHERE merchant_id=2;")=='1:114.0000000:24.0000000',
        'only_location_writes_audited':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_LOCATION_UPDATE';")=='3',
        'ephemeral_owner_coordinates_not_audited':mysql.text("SELECT COUNT(*) FROM audit_log WHERE CONCAT(COALESCE(before_state,''),COALESCE(after_state,'')) LIKE '%113.1234567%' OR CONCAT(COALESCE(before_state,''),COALESCE(after_state,'')) LIKE '%23.7654321%';")=='0',
        'latest_quote_and_inactive_sources_unchanged':mysql.text("SELECT CONCAT(price,':',on_shelf) FROM merchant_project WHERE id=1;")=='13.34:1' and mysql.text('SELECT COUNT(*) FROM standard_project WHERE status=0 OR is_deleted=1;')=='2',
        'read_only_distance_does_not_add_writes':counts['idempotency_record']==counts['audit_log']==3,
    }
    if not all(checks.values()):raise ValueError('Discovery scope/privacy/source invariants failed')
    return {'row_counts':counts,'checks':checks,'scope':'synthetic identities/catalog/quote inputs; HTTP confirmed locations; local straight-line estimates only'}


if __name__=='__main__':
    collection,manifest=assets()
    for p,d in [(OUTPUT,collection),(MANIFEST,manifest)]:p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Discovery cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
