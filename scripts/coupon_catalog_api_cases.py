"""Real coupon draft edits, merchant/pool lists and eligibility reads with synthetic identities."""
import json
import hashlib
import uuid
from urllib.request import Request, urlopen
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from governance_api_cases import seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/coupon-catalog-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/coupon-catalog-case-manifest.json'
OPERATION_COUNT=9
ACTIVE_FROM='2020-01-01T00:00:00Z'
ACTIVE_TO='2999-01-01T00:00:00Z'
TERMS=dict(name='synthetic cash',type='DISCOUNT',project_id=1,amount='5.00',min_spend='10.00',total_quantity=2,valid_from=ACTIVE_FROM,valid_to=ACTIVE_TO)
REVISED=dict(TERMS,name='synthetic revised cash',amount='6.00')
TABLES=('coupon','coupon_source','coupon_publication','coupon_issue','coupon_stock_event','user_coupon','audit_log','idempotency_record','order','payment','refund')
CHECKS=set('five_sources four_frozen_publications cash_revision_frozen service_stopped_pool_withdrawn exact_stock two_public_issues two_stock_events no_foreign_issues seventeen_audits seventeen_receipts no_money_or_orders read_content_unchanged read_probes_passed'.split())
BACKEND=None
TOKENS={}


def key(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:'+name))


def cases():
    c=Cases();add=c.add
    shop='/api/merchant/coupons';admin='/api/admin/coupons';offers='/api/coupon/offers';mine='/api/coupon/mine';claim='/api/coupon/receive'
    def get(name,path,role,extra=(),kind='normal',status=200):add(name,'GET',path,status,kind,role=role,extra=extra)
    def ids(path,expected,total=None):
        total=len(expected) if total is None else total
        return ['const d=pm.response.json().data;pm.expect(d.total).to.eql('+str(total)+');pm.expect(d.items.map(i=>i.coupon_id)).to.eql('+json.dumps(expected)+');']
    c.write('catalog_draft',shop,{'terms':TERMS},'merchant',extra=['const d=pm.response.json().data;pm.expect(d.coupon_id).to.eql(1);pm.expect(d.revision).to.eql(1);pm.expect(d.status).to.eql("DRAFT");'])
    edit={'expected_revision':1,'terms':REVISED}
    c.write('catalog_edit',shop+'/1',edit,'merchant',method='PUT',extra=['const d=pm.response.json().data;pm.expect(d.revision).to.eql(2);pm.expect(d.terms.amount).to.eql("6.00");pm.expect(d.status).to.eql("DRAFT");'])
    for name,path,body,status,role,request_key in [
        ('edit_stale',shop+'/1',dict(edit,terms=TERMS),409,'merchant',None),
        ('edit_changed_intent',shop+'/1',dict(edit,terms=TERMS),400,'merchant',key('catalog_edit')),
        ('edit_foreign',shop+'/1',edit,404,'other_merchant',None),
        ('edit_missing',shop+'/999999',edit,404,'merchant',None),
        ('edit_zero_id',shop+'/0',edit,400,'merchant',None),
        ('edit_query',shop+'/1?merchant_id=2',edit,400,'merchant',None),
        ('edit_expired',shop+'/1',dict(edit,expected_revision=2,terms=dict(REVISED,valid_to='2021-01-01T00:00:00Z')),409,'merchant',None),
        ('edit_numeric_amount',shop+'/1',dict(edit,terms=dict(REVISED,amount=6)),400,'merchant',None),
        ('edit_service_nonzero',shop+'/1',dict(edit,terms=dict(REVISED,type='SERVICE')),400,'merchant',None),
        ('edit_technician',shop+'/1',edit,403,'technician',None)]:add(name,'PUT',path,status,'unauthorized' if role!='merchant' else 'boundary' if status==404 else 'error',body,role,key=request_key)
    c.write('catalog_publish',shop+'/1/status',{'expected_revision':2,'status':'PUBLISHED'},'merchant',method='PUT')
    add('edit_published','PUT',shop+'/1',409,'boundary',dict(edit,expected_revision=3),'merchant')
    add('edit_old_receipt','PUT',shop+'/1',200,'boundary',edit,'merchant',key=key('catalog_edit'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_catalog_edit")));'])
    # Separate issuing shop, a not-yet-valid source, a service source and an unpublished draft.
    for name,role,terms,id in [('other_draft','other_merchant',dict(TERMS,name='synthetic other shop'),2),('future_draft','merchant',dict(TERMS,name='synthetic future',valid_from='2998-01-01T00:00:00Z'),3),('service_draft','merchant',dict(TERMS,name='synthetic service',type='SERVICE',amount='0.00',min_spend='0.00',total_quantity=3),4),('unpublished_draft','merchant',dict(TERMS,name='synthetic unpublished'),5)]:
        add(name,'POST',shop,200,'normal',{'terms':terms},role,extra=['pm.expect(pm.response.json().data.coupon_id).to.eql('+str(id)+');'])
        if id!=5:add(name+'_publish','PUT',shop+'/'+str(id)+'/status',200,'normal',{'expected_revision':1,'status':'PUBLISHED'},role)
    c.write('catalog_pool',admin+'/1/pool',{'expected_pool_revision':0,'selected':True},'coupon',method='PUT')
    for id in (3,4):add('pool_'+str(id),'PUT',admin+'/'+str(id)+'/pool',200,'normal',{'expected_pool_revision':0,'selected':True},'coupon',key=key('pool_'+str(id)))
    get('published_after_old_edit',shop,'merchant',[
        'const i=pm.response.json().data.items.find(i=>i.coupon_id===1);pm.expect(i.status).to.eql("PUBLISHED");pm.expect(i.revision).to.eql(3);pm.expect(i.publication_revision).to.eql(3);pm.expect(i.terms.amount).to.eql("6.00");'])
    c.read('merchant_catalog',shop,'merchant',extra=ids(shop,[5,4,3,1]),boundary=(shop+'?page=1000000',200),error=(shop+'?page=0',400))
    get('merchant_empty',shop+'?page=1000000','merchant',ids(shop,[],4),'boundary')
    get('merchant_page_size',shop+'?page_size=1','merchant',ids(shop,[5],4),'boundary')
    get('merchant_other_store',shop,'other_merchant',ids(shop,[2]),'unauthorized')
    get('merchant_technician',shop,'technician',kind='unauthorized',status=403)
    c.read('pool_catalog',admin,'coupon',extra=ids(admin,[4,3,2,1]),boundary=(admin+'?page=1000000',200),error=(admin+'?page_size=101',400))
    get('pool_empty',admin+'?page=1000000','coupon',ids(admin,[],4),'boundary')
    for role in ('plain','catalog'):get('pool_'+role+'_denied',admin,role,kind='unauthorized',status=403)
    c.read('offers_catalog',offers,extra=ids(offers,[4,1]),boundary=(offers+'?page=1000000',200),error=(offers+'?merchant_id=0',400))
    get('offers_empty',offers+'?page=1000000','owner',ids(offers,[],2),'boundary')
    get('offers_shop',offers+'?merchant_id=1','owner',ids(offers,[4,1]),'boundary')
    get('offers_other_shop',offers+'?merchant_id=2','owner',ids(offers,[2]),'boundary')
    get('offers_unknown_shop',offers+'?merchant_id=999999','owner',ids(offers,[]),'boundary')
    for name,path,role in [('offers_duplicate',offers+'?merchant_id=1&merchant_id=2','owner'),('offers_injection',offers+'?user_id=2','owner'),('merchant_injection',shop+'?merchant_id=2','merchant'),('pool_injection',admin+'?merchant_id=1','coupon')]:get(name,path,role,kind='error',status=400)
    issue={'coupon_id':1,'publication_revision':3}
    c.write('catalog_claim',claim,issue,extra=['pm.expect(pm.response.json().data.user_coupon_id).to.eql(1);'])
    get('offers_after_claim',offers,'owner',ids(offers,[4]),'boundary')
    get('offers_other_owner',offers,'other',ids(offers,[4,1]),'boundary')
    add('claim_lifetime_limit','POST',claim,409,'boundary',issue)
    add('claim_other_owner','POST',claim,200,'normal',issue,'other',extra=['pm.expect(pm.response.json().data.user_coupon_id).to.eql(2);'])
    add('claim_exhausted','POST',claim,409,'boundary',issue)
    get('offers_exhausted','/api/coupon/offers?merchant_id=1','owner',ids(offers,[4]),'boundary')
    c.read('catalog_mine',mine,extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items[0].coupon_id).to.eql(1);pm.expect(d.items[0].terms.amount).to.eql("6.00");pm.expect(d.items[0].status).to.eql("AVAILABLE");'],boundary=(mine+'?page=1000000',200),error=(mine+'?user_id=2',400))
    add('withdraw_service_pool','PUT',admin+'/4/pool',200,'normal',{'expected_pool_revision':1,'selected':False},'coupon')
    add('old_pool_receipt','PUT',admin+'/4/pool',200,'boundary',{'expected_pool_revision':0,'selected':True},'coupon',key=key('pool_4'),extra=['const d=pm.response.json().data;pm.expect(d.pool_selected).to.eql(true);pm.expect(d.pool_revision).to.eql(1);'])
    get('pool_old_receipt_no_revival',admin,'coupon',[
        'const i=pm.response.json().data.items.find(i=>i.coupon_id===4);pm.expect(i.pool_selected).to.eql(false);pm.expect(i.pool_revision).to.eql(2);'],'boundary')
    get('offers_after_pool_withdraw',offers,'owner',ids(offers,[]),'boundary')
    get('shop_after_pool_withdraw',offers+'?merchant_id=1','owner',ids(offers,[4]),'boundary')
    add('stop_service','PUT',shop+'/4/status',200,'normal',{'expected_revision':2,'status':'STOPPED'},'merchant')
    get('shop_after_stop',offers+'?merchant_id=1','owner',ids(offers,[]),'boundary')
    get('pool_keeps_stopped_source',admin,'coupon',[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(4);const i=d.items.find(i=>i.coupon_id===4);pm.expect(i.status).to.eql("STOPPED");pm.expect(i.stock_left).to.eql(3);'],'boundary')
    for item in c.items:
        if item['request']['method']=='GET' and item['status']==200:
            item['event'][0]['script']['exec'].append('pm.test("minimal projection",()=>{const text=JSON.stringify(pm.response.json().data);for(const k of ["user_id","openid","phone","created_by","submission_key","request_hash"])pm.expect(text).not.to.include(JSON.stringify(k)+":");});')
            item['assertions']+=1
    return c.items


def assets():return assemble(cases(),'Autocare isolated coupon catalog and draft revision','isolated_coupon_catalog_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret)
    mysql.sql("UPDATE operator_account SET can_price_monitor=0,can_coupon=1 WHERE id=2;INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(2,2,1,12.34,1);")
    tokens['coupon_token']=tokens.pop('monitor_token');tokens['isolated_coupon_catalog_cases']='true'
    TOKENS.clear();TOKENS.update(tokens);return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def prepare_backend(backend):
    global BACKEND
    BACKEND=backend


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    requests={i['name']:i['request'] for i in collection['item']}
    for e in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        expected=requests[e['item']['name']];actual=e['request']
        headers=lambda r:sorted((h['key'].lower(),h['value']) for h in r.get('header',[]) if not h.get('disabled') and h['key'].lower() in ('idempotency-key','content-type'))
        if headers(expected)!=headers(actual) or expected.get('body')!=actual.get('body'):raise ValueError('Coupon terms, version, selection or write key substituted')
    result['coupon_catalog_intents_verified']=True;return result


def snapshot(mysql):return {t:hashlib.sha256(mysql.text('SELECT * FROM `'+t+'` ORDER BY 1;').encode()).hexdigest() for t in TABLES}


def verify_database(mysql):
    checks={
        'five_sources':mysql.text('SELECT (SELECT COUNT(*) FROM coupon)+(SELECT COUNT(*) FROM coupon_source);')=='10',
        'four_frozen_publications':mysql.text('SELECT COUNT(*) FROM coupon_publication;')=='4',
        'cash_revision_frozen':mysql.text("SELECT COUNT(*) FROM coupon c JOIN coupon_source s ON s.coupon_id=c.id JOIN coupon_publication p ON p.coupon_id=c.id WHERE c.id=1 AND c.status='PUBLISHED' AND s.revision=3 AND p.revision=3 AND JSON_UNQUOTE(JSON_EXTRACT(p.terms_json,'$.amount'))='6.00' AND JSON_UNQUOTE(JSON_EXTRACT(s.terms_json,'$.amount'))='6.00';")=='1',
        'service_stopped_pool_withdrawn':mysql.text("SELECT COUNT(*) FROM coupon c JOIN coupon_source s ON s.coupon_id=c.id WHERE c.id=4 AND c.status='STOPPED' AND s.revision=3 AND s.pool_selected=0 AND s.pool_revision=2;")=='1',
        'exact_stock':mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',issued_quantity,':',total_quantity) ORDER BY id) FROM coupon;")=='1:2:2,2:0:2,3:0:2,4:0:3,5:0:2',
        'two_public_issues':mysql.text("SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1 AND publication_revision=3 AND origin='PUBLIC';")=='2',
        'two_stock_events':mysql.text('SELECT COUNT(*) FROM coupon_stock_event WHERE coupon_id=1 AND quantity_delta=-1 AND stock_after IN (0,1);')=='2',
        'no_foreign_issues':mysql.text("SELECT COUNT(*) FROM coupon_issue i JOIN user_coupon u ON u.id=i.user_coupon_id AND u.user_id=i.user_id AND u.coupon_id=i.coupon_id WHERE i.user_id IN (1,2) AND u.status='AVAILABLE';")=='2' and mysql.text('SELECT COUNT(*) FROM user_coupon;')=='2',
        'seventeen_audits':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'COUPON_%';")=='17',
        'seventeen_receipts':mysql.text('SELECT COUNT(*) FROM idempotency_record WHERE response_body IS NOT NULL;')=='17',
        'no_money_or_orders':mysql.text('SELECT (SELECT COUNT(*) FROM `order`)+(SELECT COUNT(*) FROM payment)+(SELECT COUNT(*) FROM refund);')=='0',
    }
    before=snapshot(mysql);probes=[]
    for path,role,total in [('/api/coupon/offers','owner',0),('/api/merchant/coupons','merchant',4),('/api/admin/coupons','coupon',4),('/api/coupon/mine','owner',1)]:
        with urlopen(Request('http://127.0.0.1:'+str(BACKEND.port)+path,headers={'Authorization':'Bearer '+TOKENS[role+'_token']}),timeout=20) as response:
            data=json.loads(response.read());probes.append(response.status==200 and 'no-store' in response.headers.get('Cache-Control','') and data['code']==0 and data['data']['total']==total)
    checks['read_content_unchanged']=before==snapshot(mysql)
    checks['read_probes_passed']=len(probes)==4 and all(probes)
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Coupon catalog revision, stock, audit or read invariant failed: '+str(checks))
    return dict(checks=checks,read_probe_requests=4,scope='Synthetic identities and shop/project fixtures; coupon changes and issuance via real HTTP; no money or supplier calls')


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Coupon catalog cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
