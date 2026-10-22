"""Pickup delivery comparison: baseline vs finished-work evidence, isolated read-only HTTP."""
import hashlib
import json
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/delivery-compare-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/delivery-compare-case-manifest.json'
OPERATION_COUNT=1
COMPARE='/api/check/delivery/compare'
SLOTS=['DASHBOARD','FRONT','INTERIOR','LEFT','REAR','RIGHT','ROOF']
TABLES={'order':'id','pickup_check':'id','pickup_check_file':'id','repair_protection':'id',
        'technician_report':'id','service_report_submission':'id','service_evidence_file':'id',
        'file_object':'id','delivery_compare':'id','order_status_transition':'id',
        'audit_log':'id','idempotency_record':'id'}
CHECKS={'unchanged_'+t for t in TABLES}|{'no_delivery_compare_rows'}
BASELINE={}


def cases():
    c=Cases();add=c.add
    def body(order):return {'order_id':order}
    add('delivery_ready','POST',COMPARE,200,'normal',body(301),role='owner',extra=[
        'const d=pm.response.json().data;pm.expect(d.order_id).to.eql(301);pm.expect(d.order_status).to.eql("PENDING_VERIFY");',
        'const d=pm.response.json().data;pm.expect(d.result).to.eql("READY");pm.expect(d.deliverable).to.eql(true);pm.expect(d.missing).to.eql([]);',
        'const b=pm.response.json().data.baseline;pm.expect(b.mileage).to.eql(120);pm.expect(b.mileage_delta).to.eql(0);pm.expect(b.fuel_level).to.eql("HALF");pm.expect(b.damage_status).to.eql("NONE");pm.expect(b.damage_count).to.eql(0);pm.expect(b.owner_confirm).to.eql(1);',
        'const b=pm.response.json().data.baseline;pm.expect(b.photo_count).to.eql(7);pm.expect(b.photo_slots).to.eql('+json.dumps(SLOTS)+');',
        'const w=pm.response.json().data.work;pm.expect(w.protection).to.eql({submitted:true,item_count:2,photo:true});',
        'const r=pm.response.json().data.work.report;pm.expect(r.status).to.eql("SIGNED");pm.expect(r.submitted).to.eql(true);pm.expect(r.process_photos).to.eql(1);pm.expect(r.finish_photos).to.eql(1);pm.expect(r.fault_photos).to.eql(0);pm.expect(r.signature_photos).to.eql(1);pm.expect(r.no_fault_parts).to.eql(true);',
        'for(const k of ["user_id","owner_id","staff_id","technician_id","phone","openid","object_key","report_id","pickup_check_file"])pm.expect(pm.response.text()).not.to.include("\\""+k+"\\"");'])
    add('delivery_ready_merchant','POST',COMPARE,200,'normal',body(301),role='merchant',extra=[
        'const d=pm.response.json().data;pm.expect(d.result).to.eql("READY");pm.expect(d.deliverable).to.eql(true);pm.expect(d.baseline.pickup_check_id).to.eql(301);'])
    add('delivery_incomplete','POST',COMPARE,200,'boundary',body(302),role='owner',extra=[
        'const d=pm.response.json().data;pm.expect(d.result).to.eql("INCOMPLETE");pm.expect(d.deliverable).to.eql(false);pm.expect(d.missing).to.eql(["OWNER_CONFIRM","PROTECTION_PHOTO","FINISH","FAULT"]);',
        'const w=pm.response.json().data.work;pm.expect(w.protection.submitted).to.eql(false);pm.expect(w.report.status).to.eql("SIGNED");pm.expect(w.report.finish_photos).to.eql(0);'])
    add('delivery_completed','POST',COMPARE,200,'boundary',body(305),role='owner',extra=[
        'const d=pm.response.json().data;pm.expect(d.order_status).to.eql("COMPLETED");pm.expect(d.result).to.eql("READY");pm.expect(d.deliverable).to.eql(false);pm.expect(d.missing).to.eql([]);'])
    add('delivery_missing_baseline','POST',COMPARE,409,'boundary',body(303),role='owner',extra=[
        'pm.expect(pm.response.json().code).to.eql(43001);'])
    add('delivery_missing_order','POST',COMPARE,404,'boundary',body(999999),role='owner')
    for name,payload in [('delivery_empty_body',{}),('delivery_extra_field',{'order_id':301,'note':'x'}),
                         ('delivery_zero_order',{'order_id':0}),('delivery_string_order',{'order_id':'301'}),
                         ('delivery_list_body',[301])]:
        add(name,'POST',COMPARE,400,'error',payload,role='owner')
    add('delivery_query','POST',COMPARE+'?order_id=301',400,'error',body(301),role='owner')
    add('delivery_anonymous','POST',COMPARE,401,'unauthorized',body(301),role='anonymous')
    add('delivery_technician_denied','POST',COMPARE,403,'unauthorized',body(301),role='technician')
    add('delivery_other_owner','POST',COMPARE,404,'unauthorized',body(301),role='other')
    add('delivery_other_merchant','POST',COMPARE,404,'unauthorized',body(301),role='other_merchant')
    return c.items


def assets():return assemble(cases(),'Autocare isolated pickup delivery comparison','isolated_delivery_compare_cases',OPERATION_COUNT)


def seed(mysql,secret):
    tokens=identity_seed(mysql,secret);tokens['isolated_delivery_compare_cases']='true'
    # Extra private files: signature/process evidence must belong to the assigned technician.
    rows=[]
    for file in range(13,33):
        owner=3 if file in (20,21,30,31,32,9,10,11) else 1
        rows.append(f"({file},'staff_account',{owner},'synthetic-delivery/{file}','image/png',2048,'CLEAN')")
    mysql.sql('INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES '+','.join(rows)+';')
    mysql.sql("""INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,check_in_completed_at,service_report_ready_at) VALUES
(301,'SYNTH-DELIVERY-0301',1,1,1,12.34,12.34,'PENDING_VERIFY','2026-10-20 10:00:00','2026-10-20 16:00:00'),
(302,'SYNTH-DELIVERY-0302',1,1,1,12.34,12.34,'PENDING_VERIFY','2026-10-20 10:00:00','2026-10-20 16:00:00'),
(303,'SYNTH-DELIVERY-0303',1,1,1,12.34,12.34,'PENDING_VERIFY',NULL,NULL),
(305,'SYNTH-DELIVERY-0305',1,1,1,12.34,12.34,'COMPLETED','2026-10-19 10:00:00','2026-10-19 16:00:00');""")
    def photos(check,files):
        return ','.join(f"({check},{file},'{slot}')" for slot,file in zip(SLOTS,files))
    mysql.sql(f"""INSERT INTO pickup_check(id,order_id,merchant_id,staff_id,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,damage_marks,owner_confirm,confirm_at,arrived_at) VALUES
(301,301,1,1,120,'MANUAL','{{"mileage":120}}','HALF','NONE','[]',1,'2026-10-20 10:30:00','2026-10-20 10:00:00'),
(302,302,1,1,120,'MANUAL','{{"mileage":120}}','HALF','NONE','[]',0,NULL,'2026-10-20 10:00:00'),
(305,305,1,1,120,'MANUAL','{{"mileage":120}}','HALF','NONE','[]',1,'2026-10-19 10:30:00','2026-10-19 10:00:00');
INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES
{photos(301,[1,2,3,4,5,6,7])},{photos(302,[13,14,15,16,17,18,19])},{photos(305,[22,23,24,25,26,27,28])};
INSERT INTO repair_protection(id,order_id,items,uploaded_at) VALUES
(301,301,'["SEAT","STEERING"]','2026-10-20 11:00:00'),(305,305,'["SEAT","STEERING"]','2026-10-19 11:00:00');
INSERT INTO technician_report(id,order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status,signed_at) VALUES
(301,301,3,'[9]','[]','[10]','synthetic plan','synthetic analysis','[]',60,1,'2026-10-20 15:30:00'),
(302,302,3,'[20]','[]','[]','synthetic plan','synthetic analysis','[]',30,1,'2026-10-20 15:30:00'),
(305,305,3,'[30]','[]','[31]','synthetic plan','synthetic analysis','[]',60,1,'2026-10-19 15:30:00');
INSERT INTO service_report_submission(id,order_id,report_id,no_fault_parts,no_parts,submitted_at) VALUES
(301,301,301,1,1,'2026-10-20 15:00:00'),(302,302,302,0,1,'2026-10-20 15:00:00'),(305,305,305,1,1,'2026-10-19 15:00:00');
INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES
(301,301,8,'PROTECTION'),(301,301,9,'PROCESS'),(301,301,10,'FINISH'),(301,301,11,'SIGNATURE'),
(302,302,20,'PROCESS'),(302,302,21,'SIGNATURE'),
(305,305,29,'PROTECTION'),(305,305,30,'PROCESS'),(305,305,31,'FINISH'),(305,305,32,'SIGNATURE');""")
    BASELINE.clear();BASELINE.update(snapshot(mysql));return tokens


def environment(tokens):return [('WECHAT_APP_ID','fulfillment-cases')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    result['read_only_evidence']=True
    return result


def count_rows(mysql,table):
    return mysql.text('SELECT COUNT(*) FROM `'+table+'`;').strip()


def snapshot(mysql):
    return {t:hashlib.sha256(mysql.text('SELECT * FROM `'+t+'` ORDER BY '+key+';').encode()).hexdigest() for t,key in TABLES.items()}


def verify_database(mysql):
    after=snapshot(mysql);checks={'unchanged_'+t:t in BASELINE and after[t]==BASELINE[t] for t in TABLES}
    checks['no_delivery_compare_rows']=count_rows(mysql,'delivery_compare')=='0'
    if set(checks)!=CHECKS or not all(checks.values()):raise ValueError('Delivery comparison wrote business content')
    return dict(checks=checks,scope='SQL synthetic orders, pickup sheets, protection and report evidence; real HTTP reads only, delivery confirmation still belongs to redemption')


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Delivery compare API cases:',manifest['cases'],'assertions:',sum(i['assertions'] for i in manifest['items']))
