"""Isolated onboarding, operator permission and staff management HTTP acceptance."""
import base64
import hashlib
import hmac
import json
import secrets
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/merchant-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/merchant-case-manifest.json'
OPERATION_COUNT=17


def cases():
    c=Cases();a=c.add
    application={'merchant_name':'合成申请门店','category':'REPAIR','region_code':'440106','address':'合成地址一号',
                 'contact_phone':'13800000000','qualification_file_ids':[20]}
    c.write('application','/api/merchant-applications',application,extra=['pm.expect(pm.response.json().data.application.application_id).to.eql(1);'])
    a('application_duplicate','POST','/api/merchant-applications',409,'boundary',application)
    a('application_foreign_file','POST','/api/merchant-applications',409,'unauthorized',application,'other')
    c.read('application_mine','/api/merchant-applications/mine',extra=['pm.expect(pm.response.json().data.application.status).to.eql("PENDING_REVIEW");'],error=('/api/merchant-applications/mine?user_id=2',400))
    a('application_none_other','GET','/api/merchant-applications/mine',200,'boundary',role='other',extra=['pm.expect(pm.response.json().data.application).to.eql(null);'])
    c.read('pending_applications','/api/admin/merchant-applications','operator',extra=[
        'pm.expect(pm.response.json().data.items).to.have.lengthOf(1);pm.expect(pm.response.text()).not.to.include("13800000000");pm.expect(pm.response.text()).not.to.include("qualification_file_ids");'],
        boundary=('/api/admin/merchant-applications?page=2',200),error=('/api/admin/merchant-applications?page_size=51',400))
    a('pending_review_permission_not_onboard','GET','/api/admin/merchant-applications',403,'unauthorized',role='review_operator')
    c.read('application_detail','/api/admin/merchant-applications/1','operator',extra=['pm.expect(pm.response.json().data.application.application_id).to.eql(1);'],
        boundary=('/api/admin/merchant-applications/999999',404),error=('/api/admin/merchant-applications/0',400))
    a('detail_review_permission_not_onboard','GET','/api/admin/merchant-applications/1',403,'unauthorized',role='review_operator')
    reject={'revision':1,'decision':'REJECT','reason_code':'QUALIFICATION_INCOMPLETE'}
    a('moderate_review_permission_not_onboard','POST','/api/admin/merchant-applications/1/moderate',403,'unauthorized',reject,'review_operator')
    c.write('reject','/api/admin/merchant-applications/1/moderate',reject,'operator')
    c.write('resubmit','/api/merchant-applications',dict(application,merchant_name='合成重新申请门店'))
    approve={'revision':2,'decision':'APPROVE','reason_code':None}
    a('approve_without_quota','POST','/api/admin/merchant-applications/1/moderate',409,'boundary',approve,'operator',extra=['pm.expect(pm.response.json().code).to.eql(49010);'])
    a('quota_initial_empty','GET','/api/admin/merchant-quotas',200,'boundary',role='operator',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(0);'])
    quota={'region_code':'440106','category':'REPAIR','max_active':1}
    c.write('set_quota','/api/admin/merchant-quotas',quota,'operator',method='PUT')
    c.read('quota_list','/api/admin/merchant-quotas','operator',extra=['pm.expect(pm.response.json().data.items).to.have.lengthOf(1);'],error=('/api/admin/merchant-quotas?unknown=1',400))
    a('quota_review_permission_not_onboard','GET','/api/admin/merchant-quotas',403,'unauthorized',role='review_operator')
    a('quota_overflow','PUT','/api/admin/merchant-quotas',400,'boundary',dict(quota,max_active=100001),'operator')
    c.write('approve','/api/admin/merchant-applications/1/moderate',approve,'operator')
    a('quota_below_active','PUT','/api/admin/merchant-quotas',409,'boundary',dict(quota,max_active=0),'operator',extra=['pm.expect(pm.response.json().code).to.eql(49011);'])
    a('application_approved','GET','/api/merchant-applications/mine',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.application.status).to.eql("APPROVED");pm.expect(d.reviews).to.have.lengthOf(2);'])
    c.read('merchant_orders','/api/merchant/orders','merchant',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],
        boundary=('/api/merchant/orders?page=2',200),error=('/api/merchant/orders?unknown=1',400))
    a('pending_activation_cannot_use_signed_token','GET','/api/merchant/orders',401,'unauthorized',role='pending_merchant')

    staff={'role':'STAFF','display_name':'合成店员','phone':'13800000001','password':'{{staff_password}}'}
    c.write('create_staff','/api/merchant/staff',staff,'merchant',extra=[
        'const d=pm.response.json().data;pm.expect(d.staff_id).to.eql(5);pm.expect(Object.keys(d).sort()).to.eql(["staff_id","account","role","display_name","phone_masked","status","employee_code_issued","wechat_bound","created_at"].sort());pm.expect(pm.response.text()).not.to.include(pm.environment.get("staff_password"));pm.expect(pm.response.text()).not.to.include("13800000001");'])
    a('staff_cannot_create_staff','POST','/api/merchant/staff',403,'unauthorized',staff,'staff')
    c.read('staff_list','/api/merchant/staff','merchant',extra=['pm.expect(pm.response.json().data.items.some(i=>i.staff_id===5)).to.eql(true);'],
        boundary=('/api/merchant/staff?page=2',200),error=('/api/merchant/staff?page_size=51',400))
    a('staff_read_roster','GET','/api/merchant/staff',200,'normal',role='staff')
    a('staff_execution_allowed','GET','/api/merchant/orders',200,'normal',role='staff')
    c.read('tech_orders','/api/tech/orders','technician',extra=['pm.expect(pm.response.json().data.total).to.eql(0);'],
        boundary=('/api/tech/orders?page=2',200),error=('/api/tech/orders?unknown=1',400))
    a('disable_foreign_employee','POST','/api/merchant/staff/3/disable',404,'unauthorized',{},'other_merchant')
    a('staff_cannot_disable','POST','/api/merchant/staff/3/disable',403,'unauthorized',{},'staff')
    c.write('disable_tech','/api/merchant/staff/3/disable',{},'merchant',bad={'status':'DISABLED'},extra=['pm.expect(pm.response.json().data.sessions_revoked).to.be.greaterThan(0);'])
    a('disabled_tech_session_revoked','GET','/api/tech/orders',401,'unauthorized',role='technician')
    c.write('enable_tech','/api/merchant/staff/3/enable',{},'merchant',bad={'status':'ACTIVE'})
    a('enabled_tech_old_session_stays_revoked','GET','/api/tech/orders',401,'unauthorized',role='technician')
    code_path='/api/merchant/staff/3/employee-code'
    a('code_issue','POST',code_path,200,'normal',role='merchant',extra=['const d=pm.response.json().data;pm.expect(d.single_use).to.eql(true);pm.expect(d.employee_code).to.be.a("string").and.not.empty;pm.collectionVariables.set("employee_code",d.employee_code);'])
    a('code_rotate','POST',code_path,200,'boundary',role='merchant',extra=['const d=pm.response.json().data;pm.expect(d.employee_code).not.to.eql(pm.collectionVariables.get("employee_code"));pm.collectionVariables.set("employee_code",d.employee_code);'])
    a('code_unknown_query','POST',code_path+'?unknown=1',400,'error',role='merchant')
    a('code_anonymous','POST',code_path,401,'unauthorized',role='anonymous')
    a('code_wrong_role','POST',code_path,403,'unauthorized')
    a('code_foreign_shop','POST',code_path,404,'unauthorized',role='other_merchant')
    a('code_revoke','DELETE',code_path,200,'normal',role='merchant',extra=['pm.expect(pm.response.json().data.employee_code_revoked).to.eql(true);'])
    a('code_revoke_repeat','DELETE',code_path,200,'boundary',role='merchant')
    a('code_revoke_unknown_query','DELETE',code_path+'?unknown=1',400,'error',role='merchant')
    a('code_revoke_anonymous','DELETE',code_path,401,'unauthorized',role='anonymous')
    a('code_revoke_wrong_role','DELETE',code_path,403,'unauthorized')
    c.read('profile','/api/merchant/profile','merchant',extra=['pm.expect(pm.response.json().data.can_edit).to.eql(true);'],error=('/api/merchant/profile?merchant_id=2',400))
    a('profile_staff_readonly','GET','/api/merchant/profile',200,'boundary',role='staff',extra=['pm.expect(pm.response.json().data.can_edit).to.eql(false);'])
    profile={'name':'合成更新门店','address':'合成地址二号','contact_phone':'13800000002','lng':None,'lat':None}
    c.write('profile_save_null_coordinates','/api/merchant/profile',profile,'merchant',method='PUT',extra=['pm.expect(pm.response.json().data.lng).to.eql(null);pm.expect(pm.response.json().data.lat).to.eql(null);'])
    a('profile_staff_cannot_edit','PUT','/api/merchant/profile',403,'unauthorized',profile,'staff')
    a('profile_cannot_change_commission','PUT','/api/merchant/profile',400,'error',dict(profile,commission_rate='0.00'),'merchant')
    a('profile_coordinate_boundary','PUT','/api/merchant/profile',200,'boundary',dict(profile,lng=180,lat=90),'merchant')
    a('profile_coordinate_overflow','PUT','/api/merchant/profile',400,'boundary',dict(profile,lng=180.001,lat=90),'merchant')
    a('profile_canonical_coordinates','GET','/api/merchant/profile',200,'normal',role='merchant',extra=['pm.expect(pm.response.json().data.lng).to.eql("180");pm.expect(pm.response.json().data.lat).to.eql("90");'])
    for item in c.items:
        if '/employee-code' in item['request']['url']:
            item['request']['header']=[h for h in item['request']['header'] if h['key']!='Idempotency-Key']
    return c.items


def assets():return assemble(cases(),'Autocare isolated merchant onboarding staff and operator permissions','isolated_merchant_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql("""INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(20,'user',1,'synthetic-merchant/application','image/png',100,'CLEAN');
INSERT INTO operator_account(id,account,password_hash,phone,status,can_onboard,can_review) VALUES(1,'synthetic-onboard','not-a-login-password','13800000003','ACTIVE',1,0),(2,'synthetic-review','not-a-login-password','13800000004','ACTIVE',0,1);""")
    result['isolated_merchant_cases']='true';result['staff_password']='Synthetic-Only-9!'+secrets.token_hex(8)
    for role,subject,kind,claim_role,shop in [('operator',1,'operator_account','OPERATOR',None),('review_operator',2,'operator_account','OPERATOR',None),('staff',5,'staff_account','STAFF',1),('pending_merchant',4,'staff_account','MERCHANT',3)]:
        session=str(uuid.uuid4());app='operator-account' if kind=='operator_account' else 'merchant-account'
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES('{session}','{kind}',{subject},'{claim_role}','{app}',{'NULL' if shop is None else shop},'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims={'iss':'vehicle-health-manager','sub':str(subject),'iat':int(time.time()),'exp':int(time.time())+3600,'jti':session,'subject_type':kind,'role':claim_role,'app_id':app}
        if shop:claims['merchant_id']=shop
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=').decode()
        raw=encode({'alg':'HS256','typ':'JWT'})+'.'+encode(claims)
        result[role+'_token']=raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),raw.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    return result


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases'),('MERCHANT_ONBOARDING_ENABLED','true'),('MERCHANT_STAFF_ENABLED','true')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    for execution in json.loads(path.read_text(encoding='utf-8'))['run']['executions']:
        if 'employee-code' in execution['request']['url']['path']:
            if any(h['key'].lower()=='idempotency-key' and not h.get('disabled') for h in execution['request'].get('header',[])):
                raise ValueError('Employee code rotation/revocation must not use an idempotency key')
    result['employee_code_requests_without_idempotency_verified']=True
    return result


def verify_database(mysql):
    counts={'applications':int(mysql.text('SELECT COUNT(*) FROM merchant_application;')),'reviews':int(mysql.text('SELECT COUNT(*) FROM merchant_application_review;')),
            'merchants':int(mysql.text('SELECT COUNT(*) FROM merchant;')),'staff':int(mysql.text('SELECT COUNT(*) FROM staff_account;'))}
    if counts!={'applications':1,'reviews':2,'merchants':3,'staff':5}:raise ValueError('Merchant management rows are missing or duplicated')
    checks={
        'approved_revision_two':mysql.text("SELECT CONCAT(status,':',revision,':',merchant_id) FROM merchant_application WHERE id=1;")=='APPROVED:2:3',
        'pending_activation_has_no_password':mysql.text("SELECT COUNT(*) FROM staff_account WHERE id=4 AND merchant_id=3 AND status='PENDING_ACTIVATION' AND password_hash IS NULL;")=='1',
        'staff_created_active_hashed_password':mysql.text("SELECT COUNT(*) FROM staff_account WHERE id=5 AND merchant_id=1 AND role='STAFF' AND status='ACTIVE' AND password_hash LIKE '$2%';")=='1',
        'tech_enabled_without_code':mysql.text("SELECT COUNT(*) FROM staff_account WHERE id=3 AND status='ACTIVE' AND employee_code_hash IS NULL;")=='1',
        'tech_sessions_revoked':mysql.text("SELECT COUNT(*) FROM auth_session WHERE subject_type='staff_account' AND subject_id=3 AND revoked_at IS NULL;")=='0',
        'tech_binding_stays_revoked':mysql.text("SELECT COUNT(*) FROM staff_wechat_identity WHERE staff_account_id=3 AND status='ACTIVE' AND unbound_at IS NULL;")=='0',
        'foreign_shop_unchanged':mysql.text("SELECT name FROM merchant WHERE id=2;")=='synthetic other shop',
        'profile_canonical_coordinates':mysql.text("SELECT CONCAT(lng,':',lat) FROM merchant WHERE id=1;")=='180.0000000:90.0000000',
        'quota_not_below_active':mysql.text("SELECT max_active FROM merchant_region_category_quota WHERE region_code='440106' AND category='REPAIR';")=='1',
        'no_code_in_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action IN ('STAFF_CODE_ISSUE','STAFF_CODE_REVOKE') AND (after_state LIKE '%\"employee_code\"%' OR before_state LIKE '%\"employee_code\"%');")=='0',
    }
    if not all(checks.values()):raise ValueError('Merchant activation/session/quota/privacy invariant failed')
    return {'row_counts':counts,'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Merchant API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
