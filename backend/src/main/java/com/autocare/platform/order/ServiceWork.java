package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.file.FileMetadataRepository;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Immutable construction evidence and technician quality sign-off. */
public class ServiceWork {
    private final ReservationStore db;
    private final TechnicianAssignments assignments;
    private final String appId;
    public ServiceWork(ReservationStore db,TechnicianAssignments assignments,String appId){this.db=db;this.assignments=assignments;this.appId=appId;}
    private static FulfillmentConflict conflict(){return new FulfillmentConflict(40905,"施工记录或订单状态已变化，请刷新");}
    private Map<String,Object> scope(Object actor,long id,boolean lock){
        ServiceCatalog.validateId(id);
        if(actor instanceof TechnicianActor tech){db.technician(tech,appId,lock);return assignments.own(tech,id,lock);}
        if(!(actor instanceof MerchantActor shop))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权访问施工记录");
        db.merchant(shop,lock);var o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,shop.merchantId());
        if(lock){if(o.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",o.get("slot_id"));o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,shop.merchantId());}return o;
    }
    private void evidenceGuard(Map<String,Object> o,boolean lock){
        long id=ReservationStore.number(o,"id");String tail=lock?" FOR UPDATE":"";
        var sheets=db.jdbc.queryForList("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0"+tail,id);
        var disputes=db.jdbc.queryForList("SELECT status FROM order_dispute WHERE order_id=?"+tail,id);
        if(OrderStatus.DISPUTED.equals(o.get("status")) || disputes.stream().anyMatch(d->"OPEN".equals(d.get("status"))))throw new FulfillmentConflict(43007,"订单存在未解决争议，请先处理争议");
        if(o.get("check_in_completed_at")==null || sheets.isEmpty() || !Objects.equals(o.get("merchant_id"),sheets.get(0).get("merchant_id")))throw new FulfillmentConflict(43001,"接车检查未完成");
        if(o.get("owner_confirmed_at")==null || !PickupInspection.ownerConfirmed(sheets.get(0).get("owner_confirm")) || sheets.get(0).get("confirm_at")==null)throw new FulfillmentConflict(43003,"车主尚未确认接车单");
    }
    private void technicianGuard(TechnicianActor tech,Map<String,Object> o,boolean allowSigned){
        evidenceGuard(o,true);String status=String.valueOf(o.get("status"));
        if(!OrderStatus.IN_SERVICE.equals(status) && !(allowSigned&&OrderStatus.PENDING_VERIFY.equals(status)))throw conflict();
        var a=db.one("SELECT * FROM technician_assignment WHERE order_id=? FOR UPDATE",o.get("id"));
        if(!"ACCEPTED".equals(a.get("status")) || a.get("accepted_at")==null || ReservationStore.number(a,"technician_id")!=tech.staffId())throw new FulfillmentConflict(43004,"请先由本人接单");
    }
    private Map<String,Object> protection(long order,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM repair_protection WHERE order_id=?"+(lock?" FOR UPDATE":""),order);return rows.isEmpty()?null:rows.get(0);
    }
    private void protectionGuard(long order,boolean lock){
        var p=protection(order,lock);if(p==null)throw new FulfillmentConflict(43002,"请先完成施工防护拍照");
        if(ReservationStore.number(p,"is_deleted")!=0 || p.get("uploaded_at")==null)throw new FulfillmentConflict(43002,"防护记录不可用");
        // Only relation/file rows need write locks. A nested nonlocking ownership lookup avoids
        // locking the merchant employee after the technician already holds the shop lock.
        var ids=db.jdbc.queryForList("SELECT f.id FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.record_id=? AND e.kind='PROTECTION' AND f.owner_type='staff_account' AND f.owner_id IN (SELECT id FROM staff_account WHERE role='MERCHANT' AND merchant_id=?) AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.content_type IN ('image/jpeg','image/png') AND f.size_bytes BETWEEN 1 AND 10485760 ORDER BY f.id"+(lock?" FOR UPDATE":""),order,p.get("id"),db.one("SELECT merchant_id FROM `order` WHERE id=?",order).get("merchant_id"));
        var items=db.parse(p.get("items"));var values=new HashSet<String>();if(items.isArray())items.forEach(v->values.add(v.asText()));
        if(ids.size()!=1 || !values.containsAll(Set.of("SEAT_COVER","STEERING_COVER")))throw new FulfillmentConflict(43002,"防护证据不完整或图片不可用");
    }
    private Map<String,Object> submission(long order,boolean lock){
        var rows=db.jdbc.queryForList("SELECT r.*,s.no_fault_parts,s.no_parts,s.submitted_at FROM service_report_submission s JOIN technician_report r ON r.id=s.report_id AND r.order_id=s.order_id WHERE s.order_id=?"+(lock?" FOR UPDATE":""),order);
        if(rows.isEmpty())return null;if(rows.size()!=1 || ReservationStore.number(rows.get(0),"is_deleted")!=0)throw conflict();return rows.get(0);
    }
    private List<Long> fileIds(JsonNode photos){var ids=new ArrayList<Long>();photos.forEach(v->ids.add(v.longValue()));return ids;}
    private void freshFiles(long staff,List<Long> ids,boolean signature){
        for(long file:ids.stream().sorted().toList()){
            var f=db.jdbc.queryForList("SELECT id FROM file_object WHERE id=? AND owner_type='staff_account' AND owner_id=? AND scan_status='CLEAN' AND is_deleted=0 AND size_bytes BETWEEN 1 AND 10485760 AND content_type IN ('image/jpeg','image/png')"+(signature?" AND content_type='image/png'":"")+" FOR UPDATE",file,staff);
            if(f.isEmpty() || db.jdbc.queryForObject("SELECT COUNT(*) FROM service_evidence_file WHERE file_id=?",Long.class,file)>0 || db.jdbc.queryForObject("SELECT COUNT(*) FROM pickup_check_file WHERE file_id=?",Long.class,file)>0)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"图片非本人安全文件、格式不符或已被使用");
        }
    }
    private void link(long order,long record,String kind,List<Long> ids){for(long file:ids)db.jdbc.update("INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES(?,?,?,?)",order,record,file,kind);}
    public JsonNode protect(MerchantActor shop,String key,JsonNode body){
        ServiceWorkInput.protection(body);long id=body.get("order_id").longValue();long file=body.get("photo_file_id").longValue();
        return db.writes.execute(new Actor("staff_account",shop.staffId()),"POST","/api/check/protection/upload",key,body,()->{
            var o=scope(shop,id,true);evidenceGuard(o,true);if(!Set.of(OrderStatus.RECEIVED,OrderStatus.IN_SERVICE).contains(o.get("status")))throw conflict();
            if(protection(id,true)!=null)protectionGuard(id,true);
        },()->{
            if(protection(id,true)!=null)throw conflict();freshFiles(shop.staffId(),List.of(file),false);
            long record=db.insert("INSERT INTO repair_protection(order_id,items,uploaded_at) VALUES(?,?,?)",id,db.json(body.get("items")),ReservationStore.time(db.now()));link(id,record,"PROTECTION",List.of(file));
            var after=view(id);return new Change("ORDER_PROTECTION_SUBMIT","repair_protection",record,Map.of("order_id",id),after,after);
        });
    }
    public JsonNode submit(TechnicianActor tech,String key,JsonNode body){
        ServiceWorkInput.report(body);long id=body.get("order_id").longValue();
        return db.writes.execute(new Actor("staff_account",tech.staffId()),"POST","/api/tech/report/submit",key,body,()->{
            var o=scope(tech,id,true);technicianGuard(tech,o,true);protectionGuard(id,true);
            var old=submission(id,true);if(old!=null)reportGuard(tech.staffId(),id,old);else if(!OrderStatus.IN_SERVICE.equals(o.get("status")))throw conflict();
        },()->{
            if(db.jdbc.queryForObject("SELECT COUNT(*) FROM technician_report WHERE order_id=?",Long.class,id)>0 || submission(id,true)!=null)throw conflict();
            var ids=new ArrayList<Long>();for(String name:List.of("process_photos","fault_part_photos","finish_photos"))ids.addAll(fileIds(body.get(name)));freshFiles(tech.staffId(),ids,false);
            long report=db.insert("INSERT INTO technician_report(order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status) VALUES(?,?,?,?,?,?,?,?,?,0)",id,tech.staffId(),db.json(body.get("process_photos")),db.json(body.get("fault_part_photos")),db.json(body.get("finish_photos")),ServiceWorkInput.text(body.get("repair_plan"),2000),ServiceWorkInput.text(body.get("fault_analysis"),2000),db.json(body.get("parts_used")),body.get("work_hours").intValue());
            db.jdbc.update("INSERT INTO service_report_submission(order_id,report_id,no_fault_parts,no_parts,submitted_at) VALUES(?,?,?,?,?)",id,report,body.get("no_fault_parts").booleanValue()?1:0,body.get("no_parts").booleanValue()?1:0,ReservationStore.time(db.now()));
            link(id,report,"PROCESS",fileIds(body.get("process_photos")));link(id,report,"FAULT",fileIds(body.get("fault_part_photos")));link(id,report,"FINISH",fileIds(body.get("finish_photos")));
            var after=view(id);return new Change("ORDER_REPORT_SUBMIT","technician_report",report,Map.of("order_id",id),after,after);
        });
    }
    private JsonNode reportBody(Map<String,Object> r){
        var b=db.mapper.createObjectNode();b.put("order_id",ReservationStore.number(r,"order_id"));for(String name:List.of("process_photos","fault_part_photos","finish_photos","parts_used"))b.set(name,db.parse(r.get(name)));
        for(String name:List.of("repair_plan","fault_analysis"))b.put(name,r.get(name)==null?null:r.get(name).toString());
        b.put("work_hours",r.get("work_hours")==null?0:ReservationStore.number(r,"work_hours"));b.put("no_fault_parts",ReservationStore.number(r,"no_fault_parts")==1);b.put("no_parts",ReservationStore.number(r,"no_parts")==1);return b;
    }
    private void reportGuard(long technician,long order,Map<String,Object> r){
        if(r==null)throw new FulfillmentConflict(43005,"请先提交完整施工报工");
        if(ReservationStore.number(r,"technician_id")!=technician || db.jdbc.queryForObject("SELECT COUNT(*) FROM technician_report WHERE order_id=?",Long.class,order)!=1L)throw conflict();
        var b=reportBody(r);try{ServiceWorkInput.report(b);}catch(org.springframework.web.server.ResponseStatusException e){throw new FulfillmentConflict(43005,"施工报工证据不完整");}
        var expected=new HashMap<Long,String>();for(var pair:Map.of("process_photos","PROCESS","fault_part_photos","FAULT","finish_photos","FINISH").entrySet())for(long file:fileIds(b.get(pair.getKey())))expected.put(file,pair.getValue());
        var actual=db.jdbc.queryForList("SELECT e.file_id,e.kind,f.owner_id,f.owner_type,f.content_type,f.size_bytes,f.scan_status,f.is_deleted FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.record_id=? AND e.kind IN ('PROCESS','FAULT','FINISH') ORDER BY f.id FOR UPDATE",order,r.get("id"));
        if(actual.size()!=expected.size())throw new FulfillmentConflict(43005,"施工图片证据不完整");
        for(var f:actual)if(!Objects.equals(expected.get(ReservationStore.number(f,"file_id")),f.get("kind")) || !"staff_account".equals(f.get("owner_type")) || ReservationStore.number(f,"owner_id")!=technician || !"CLEAN".equals(f.get("scan_status")) || ReservationStore.number(f,"is_deleted")!=0 || !Set.of("image/jpeg","image/png").contains(f.get("content_type")) || ReservationStore.number(f,"size_bytes")<1 || ReservationStore.number(f,"size_bytes")>10485760)throw new FulfillmentConflict(43005,"施工图片不可用");
    }
    public JsonNode sign(TechnicianActor tech,String key,JsonNode body){
        ServiceWorkInput.sign(body);long id=body.get("order_id").longValue(),file=body.get("signature_file_id").longValue();
        return db.writes.execute(new Actor("staff_account",tech.staffId()),"POST","/api/tech/sign",key,body,()->{
            var o=scope(tech,id,true);technicianGuard(tech,o,true);protectionGuard(id,true);var r=submission(id,true);reportGuard(tech.staffId(),id,r);
            if(ReservationStore.number(r,"status")==1){if(!OrderStatus.PENDING_VERIFY.equals(o.get("status")) || r.get("signed_at")==null || o.get("service_report_ready_at")==null)throw conflict();
                db.one("SELECT f.id FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.record_id=? AND e.kind='SIGNATURE' AND e.file_id=? AND f.owner_type='staff_account' AND f.owner_id=? AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.content_type='image/png' AND f.size_bytes BETWEEN 1 AND 10485760 FOR UPDATE",id,r.get("id"),file,tech.staffId());
            }else if(ReservationStore.number(r,"status")!=0 || r.get("signed_at")!=null || o.get("service_report_ready_at")!=null || !OrderStatus.IN_SERVICE.equals(o.get("status")))throw conflict();
        },()->{
            var r=submission(id,true);if(ReservationStore.number(r,"status")!=0)throw conflict();freshFiles(tech.staffId(),List.of(file),true);var before=view(id);link(id,ReservationStore.number(r,"id"),"SIGNATURE",List.of(file));
            if(!OrderStatus.can(OrderStatus.IN_SERVICE,OrderStatus.PENDING_VERIFY))throw conflict();var now=ReservationStore.time(db.now());
            db.jdbc.update("UPDATE technician_report SET status=1,signed_at=? WHERE id=?",now,r.get("id"));db.jdbc.update("UPDATE `order` SET status='PENDING_VERIFY',service_report_ready_at=? WHERE id=?",now,id);
            db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES(?,?,'IN_SERVICE','PENDING_VERIFY','ORDER_SERVICE_FINISH','staff_account',?,?)",id,tech.merchantId(),tech.staffId(),now);
            var after=view(id);return new Change("ORDER_SERVICE_FINISH","technician_report",ReservationStore.number(r,"id"),before,after,after);
        });
    }
    /** Revalidate immutable evidence at redemption without requiring the historical technician session. */
    void redemptionGuard(Map<String,Object> order){
        long id=ReservationStore.number(order,"id");evidenceGuard(order,true);protectionGuard(id,true);
        var rows=db.jdbc.queryForList("SELECT * FROM technician_assignment WHERE order_id=? FOR UPDATE",id);
        if(rows.size()!=1 || order.get("assigned_at")==null || !Objects.equals(order.get("merchant_id"),rows.get(0).get("merchant_id")) || !"ACCEPTED".equals(rows.get(0).get("status")) || rows.get(0).get("accepted_at")==null)throw new FulfillmentConflict(43004,"派工接单证据不完整");
        long technician=ReservationStore.number(rows.get(0),"technician_id");var r=submission(id,true);reportGuard(technician,id,r);
        if(ReservationStore.number(r,"status")!=1 || r.get("signed_at")==null || order.get("service_report_ready_at")==null)throw new FulfillmentConflict(43005,"完整报工与质检签字尚未完成");
        var signs=db.jdbc.queryForList("SELECT f.id FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.record_id=? AND e.kind='SIGNATURE' AND f.owner_type='staff_account' AND f.owner_id=? AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.content_type='image/png' AND f.size_bytes BETWEEN 1 AND 10485760 FOR UPDATE",id,r.get("id"),technician);
        if(signs.size()!=1)throw new FulfillmentConflict(43005,"质检签字证据不可用");
    }
    public Map<String,Object> detail(Object actor,long id){return db.reads.execute(tx->{scope(actor,id,false);return view(id);});}
    private Map<String,Object> view(long id){
        var out=new LinkedHashMap<String,Object>();out.put("order_id",id);out.put("order_status",db.one("SELECT status FROM `order` WHERE id=?",id).get("status"));
        var p=protection(id,false);Map<String,Object> protection=null;if(p!=null&&ReservationStore.number(p,"is_deleted")==0){protection=new LinkedHashMap<>();protection.put("items",db.parse(p.get("items")));protection.put("uploaded_at",ReservationStore.iso(p.get("uploaded_at")));var files=db.jdbc.queryForList("SELECT file_id FROM service_evidence_file WHERE order_id=? AND record_id=? AND kind='PROTECTION'",id,p.get("id"));protection.put("photo_file_id",files.size()==1?files.get(0).get("file_id"):null);}out.put("protection",protection);
        var r=submission(id,false);Map<String,Object> report=null;if(r!=null){report=new LinkedHashMap<>();report.put("report_id",r.get("id"));report.putAll(db.mapper.convertValue(reportBody(r),Map.class));report.remove("order_id");report.put("submitted_at",ReservationStore.iso(r.get("submitted_at")));report.put("signed_at",ReservationStore.iso(r.get("signed_at")));report.put("status",ReservationStore.number(r,"status")==1?"SIGNED":"SUBMITTED");var files=db.jdbc.queryForList("SELECT file_id FROM service_evidence_file WHERE order_id=? AND record_id=? AND kind='SIGNATURE'",id,r.get("id"));report.put("signature_file_id",files.size()==1?files.get(0).get("file_id"):null);}out.put("report",report);return out;
    }
    public FileMetadataRepository.Actor fileOwner(Object actor,long order,long file){
        ServiceCatalog.validateId(file);return db.reads.execute(tx->{scope(actor,order,false);
            var f=db.one("SELECT f.owner_type,f.owner_id FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.file_id=? AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.owner_type='staff_account' AND f.content_type IN ('image/jpeg','image/png') AND f.size_bytes BETWEEN 1 AND 10485760 AND ((e.kind='PROTECTION' AND EXISTS(SELECT 1 FROM repair_protection p WHERE p.id=e.record_id AND p.order_id=e.order_id AND p.is_deleted=0)) OR (e.kind IN ('PROCESS','FAULT','FINISH','SIGNATURE') AND EXISTS(SELECT 1 FROM technician_report r JOIN service_report_submission s ON s.report_id=r.id AND s.order_id=r.order_id WHERE r.id=e.record_id AND r.order_id=e.order_id AND r.is_deleted=0)))",order,file);
            return new FileMetadataRepository.Actor(String.valueOf(f.get("owner_type")),ReservationStore.number(f,"owner_id"));});
    }
}
