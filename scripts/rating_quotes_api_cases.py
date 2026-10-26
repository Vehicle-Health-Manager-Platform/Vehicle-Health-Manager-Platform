"""Public star-service project rating quotes: isolated read-only HTTP with synthetic provenance."""
import hashlib
import json
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/rating-quotes-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/rating-quotes-case-manifest.json'
OPERATION_COUNT=1
QUOTES='/api/service/project/{id}/rating-quotes'
# Ordered key per table: review_publication has no surrogate id.
TABLES={'user':'id','vehicle':'id','order':'id','payment':'id','payment_event':'id',
        'order_redemption':'id','order_review':'id','review_publication':'review_id',
        'merchant':'id','merchant_project':'id','merchant_project_version':'id',
        'standard_project':'id','audit_log':'id','idempotency_record':'id'}
CHECKS={'unchanged_'+t for t in TABLES}
BASELINE={}
PRIVACY='for(const k of ["owner_id","user_id","review_id","order_id","content","actor_staff_id","audit_id","submission_key","phone","openid"])pm.expect(pm.response.text()).not.to.include("\\""+k+"\\"");'


def cases():
    c=Cases();add=c.add;path=QUOTES.replace('{id}','1')
    add('quotes','GET',path,200,'normal',role='owner',extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.page).to.eql(1);pm.expect(d.page_size).to.eql(20);',
        'const i=pm.response.json().data.items;pm.expect(i.map(x=>x.merchant_project_id)).to.eql([1,3,2]);pm.expect(i.map(x=>x.merchant_id)).to.eql([1,3,2]);',
        'const i=pm.response.json().data.items;pm.expect(i.map(x=>x.price)).to.eql(["12.34","30.00","10.00"]);pm.expect(i[0].version).to.eql(1);pm.expect(i[0].merchant_name).to.eql("synthetic shop");',
        'const i=pm.response.json().data.items;pm.expect(i[0].rating_summary).to.eql({count:1,rating_sum:5,average_rating:"5.00"});pm.expect(i[1].rating_summary).to.eql({count:1,rating_sum:2,average_rating:"2.00"});',
        'const i=pm.response.json().data.items;pm.expect(i[2].rating_summary).to.eql({count:0,rating_sum:0,average_rating:null});',PRIVACY])
    add('quotes_other_owner','GET',path,200,'normal',role='other',extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items.map(x=>x.merchant_project_id)).to.eql([1,3,2]);',PRIVACY])
    for name,project,query,extra in [
        ('quotes_empty_page',1,'?page=1000000',['const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items).to.eql([]);']),
        ('quotes_region',1,'?region_code=310000',['const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(x=>x.merchant_id)).to.eql([1,3]);',PRIVACY]),
        ('quotes_region_no_match',1,'?region_code=999999',['const d=pm.response.json().data;pm.expect(d.total).to.eql(0);pm.expect(d.items).to.eql([]);']),
        ('quotes_second_project',2,'?page=1',['const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].merchant_project_id).to.eql(6);pm.expect(d.items[0].version_id).to.eql(6);'])]:
        add(name,'GET',QUOTES.replace('{id}',str(project))+query,200,'boundary',role='owner',extra=extra)
    add('quotes_missing_project','GET',QUOTES.replace('{id}','999999'),404,'boundary',role='owner')
    add('quotes_inactive_project','GET',QUOTES.replace('{id}','3'),404,'boundary',role='owner')
    for name,url in [('quotes_page_zero',path+'?page=0'),('quotes_page_size',path+'?page_size=21'),
                     ('quotes_region_digits',path+'?region_code=31000'),('quotes_unknown_query',path+'?project_id=1'),
                     ('quotes_duplicate_page',path+'?page=1&page=2'),('quotes_zero_project',QUOTES.replace('{id}','0'))]:
        add(name,'GET',url,400,'error',role='owner')
    add('quotes_anonymous','GET',path,401,'unauthorized',role='anonymous')
    for role in ('merchant','technician','other_merchant'):
        add('quotes_'+role+'_denied','GET',path,403,'unauthorized',role=role)
    return c.items


def assets():return assemble(cases(),'Autocare isolated public star service rating quotes','isolated_rating_quotes_cases',OPERATION_COUNT)


def literal(value):
    return "'"+json.dumps(value,ensure_ascii=False,separators=(',',':')).replace('\\','\\\\').replace("'","''")+"'"


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret);tokens['isolated_rating_quotes_cases']='true'
    mysql.sql("""UPDATE merchant SET region_code='310000' WHERE id=1;
UPDATE merchant SET region_code='440100' WHERE id=2;
INSERT INTO merchant(id,merchant_type,name,address,status,region_code) VALUES(3,2,'synthetic third shop','synthetic address',1,'310000');
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(4,3,'MERCHANT','fulfillment-third-shop','ACTIVE');
INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status) VALUES
(2,'synthetic partially excluded service',1,'synthetic inspection',1,100,1),
(3,'synthetic inactive service',1,'synthetic inspection',1,100,0);
INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES
(2,2,1,10.00,1),(3,3,1,30.00,1),(4,2,2,8.00,0),(5,3,2,50.00,1),(6,1,2,9.00,1);
INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES
(2,2,1,10.00,1,2),(3,3,1,30.00,1,4),(4,4,1,8.00,0,2),(5,5,1,0.00,1,4),(6,6,1,9.00,1,1);
INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status) VALUES
(201,'SYNTH-RQ-0201',1,1,1,1,12.34,12.34,'COMPLETED'),
(202,'SYNTH-RQ-0202',1,1,3,1,30.00,30.00,'COMPLETED');
INSERT INTO payment(id,order_id,channel,payment_no,channel_payment_no,amount,currency,status,paid_at) VALUES
(201,201,'WECHAT','SYNTHRQ000201','SYNTHRQWX000201',12.34,'CNY','SUCCEEDED',UTC_TIMESTAMP()),
(202,202,'WECHAT','SYNTHRQ000202','SYNTHRQWX000202',30.00,'CNY','SUCCEEDED',UTC_TIMESTAMP());
INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES
('WECHAT','66666666-6666-4666-8666-666666666666',201,REPEAT('a',64),'PAID'),
('WECHAT','77777777-7777-4777-8777-777777777777',202,REPEAT('b',64),'PAID');
INSERT INTO order_redemption(order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES
(201,1,1,201,0,UTC_TIMESTAMP()),(202,3,4,202,0,UTC_TIMESTAMP());
INSERT INTO order_review(order_id,user_id,redemption_id,rating,content,test_mode,submitted_at)
SELECT 201,1,d.id,5,'synthetic rating five',0,UTC_TIMESTAMP() FROM order_redemption d WHERE d.order_id=201;
INSERT INTO order_review(order_id,user_id,redemption_id,rating,content,test_mode,submitted_at)
SELECT 202,1,d.id,2,'synthetic rating two',0,UTC_TIMESTAMP() FROM order_redemption d WHERE d.order_id=202;
INSERT INTO review_publication(review_id,consented,version) SELECT id,1,1 FROM order_review WHERE order_id IN (201,202);""")
    BASELINE.clear();BASELINE.update(snapshot(mysql));return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        request=e['request']
        if request.get('body') or any(h['key'].lower()=='idempotency-key' for h in request.get('header',[]) if not h.get('disabled')):
            raise ValueError('Read-only request carried body or write intent')
    result['read_only_intents_verified']=True;return result


def snapshot(mysql):
    return {t:hashlib.sha256(mysql.text('SELECT * FROM `'+t+'` ORDER BY '+key+';').encode()).hexdigest() for t,key in TABLES.items()}


COUNTED=('order','payment','order_review','review_publication','merchant_project')
EXPECTED_COUNTS={'order':2,'payment':2,'order_review':2,'review_publication':2,'merchant_project':6}


def row_counts(mysql):
    return {t:int(mysql.text('SELECT COUNT(*) FROM `'+t+'`;')) for t in COUNTED}


def verify_database(mysql):
    after=snapshot(mysql);checks={'unchanged_'+t:t in BASELINE and after[t]==BASELINE[t] for t in TABLES}
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Read-only business content changed')
    counts=row_counts(mysql)
    if counts!=EXPECTED_COUNTS:raise ValueError('Synthetic rating quote fixtures are missing or duplicated: '+str(counts))
    return dict(row_counts=counts,checks=checks,scope='SQL synthetic identities, completed orders, consenting reviews and on-shelf quotes; real HTTP reads only')


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Rating quotes API cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
