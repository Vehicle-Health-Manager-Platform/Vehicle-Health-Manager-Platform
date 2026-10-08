package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** First assignment and the technician's acceptance share the existing order locks. */
public class TechnicianAssignments {
    private final ReservationStore db;
    private final String appId;
    public TechnicianAssignments(ReservationStore db,String appId){this.db=db;this.appId=appId;}
    private static FulfillmentConflict conflict(){return new FulfillmentConflict(40905,"派工或订单状态已变化，请刷新后重试");}
    private void eligible(long staff,long shop,boolean lock){
        String tail=lock?" FOR UPDATE":"";
        db.one("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role='TECHNICIAN' AND status='ACTIVE' AND is_deleted=0"+tail,staff,shop);
        db.one("SELECT id FROM staff_wechat_identity WHERE staff_account_id=? AND app_id=? AND status='ACTIVE' AND is_deleted=0 AND unbound_at IS NULL"+tail,staff,appId);
    }
    public Map<String,Object> candidates(MerchantActor actor,int page,int size){
        ServiceCatalog.validate(null,page,size);
        return db.reads.execute(tx->{db.merchant(actor,false);
            String where=" FROM staff_account s WHERE s.merchant_id=? AND s.role='TECHNICIAN' AND s.status='ACTIVE' AND s.is_deleted=0 AND EXISTS(SELECT 1 FROM staff_wechat_identity b WHERE b.staff_account_id=s.id AND b.app_id=? AND b.status='ACTIVE' AND b.is_deleted=0 AND b.unbound_at IS NULL)";
            long total=db.jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,actor.merchantId(),appId);
            var rows=db.jdbc.queryForList("SELECT s.id AS technician_id,s.account AS label"+where+" ORDER BY s.id LIMIT ? OFFSET ?",actor.merchantId(),appId,size,(page-1L)*size);
            return ReservationStore.page(rows,total,page,size);
        });
    }
    private Map<String,Object> order(long id,long shop,boolean lock){
        var o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,shop);
        if(lock){if(o.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",o.get("slot_id"));
            o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,shop);}
        return o;
    }
    private Map<String,Object> assignment(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM technician_assignment WHERE order_id=?"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0); // Deleted rows still own the unique order key.
    }
    private Map<String,Object> sheet(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private boolean checked(Map<String,Object> o,Map<String,Object> p){return p!=null && o.get("check_in_completed_at")!=null
        && Objects.equals(o.get("merchant_id"),p.get("merchant_id"));}
    private boolean confirmed(Map<String,Object> o,Map<String,Object> p){return checked(o,p) && o.get("owner_confirmed_at")!=null
        && p.get("owner_confirm") instanceof Number decision && decision.intValue()==1 && p.get("confirm_at")!=null;}
    private void prerequisites(Map<String,Object> o,Map<String,Object> p){
        if(!OrderStatus.RECEIVED.equals(o.get("status")))throw conflict();
        if(!checked(o,p))throw new FulfillmentConflict(43001,"接车检查未完成，请先完成接车检查");
        if(!confirmed(o,p))throw new FulfillmentConflict(43003,"车主尚未确认接车，不能派工或接单");
    }
    private void complete(Map<String,Object> o,Map<String,Object> a){
        if(a==null || ReservationStore.number(a,"is_deleted")!=0 || !Objects.equals(a.get("merchant_id"),o.get("merchant_id"))
            || a.get("assigned_by")==null || ReservationStore.number(a,"assigned_by")<=0 || ReservationStore.number(a,"technician_id")<=0 || o.get("assigned_at")==null
            || !("ASSIGNED".equals(a.get("status")) && a.get("accepted_at")==null
                || "ACCEPTED".equals(a.get("status")) && a.get("accepted_at")!=null))throw conflict();
        // Historical assignments must not refer to staff from another merchant.
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM staff_account WHERE id IN (?,?) AND merchant_id=?",Long.class,
            a.get("technician_id"),a.get("assigned_by"),o.get("merchant_id"))!=2L)throw conflict();
    }
    private void lockEvidence(long id){assignment(id,true);sheet(id,true);}
    public Map<String,Object> merchantDetail(MerchantActor actor,long id){
        ServiceCatalog.validateId(id);
        return db.reads.execute(tx->{db.merchant(actor,false);var o=order(id,actor.merchantId(),false);var a=assignment(id,false);
            var result=new LinkedHashMap<String,Object>();if(a!=null)complete(o,a);else if(o.get("assigned_at")!=null)throw conflict();
            result.put("assignment",a==null?null:merchantView(o,a));return result;
        });
    }
    private Map<String,Object> merchantView(Map<String,Object> o,Map<String,Object> a){
        var result=new LinkedHashMap<String,Object>();
        for(String name:List.of("id","order_id","technician_id","status"))result.put(name.equals("id")?"assignment_id":name,a.get(name));
        var staff=db.jdbc.queryForList("SELECT account FROM staff_account WHERE id=? AND merchant_id=?",a.get("technician_id"),a.get("merchant_id"));
        result.put("technician_label",staff.isEmpty()?"技师账号不可用":staff.get(0).get("account"));
        result.put("assigned_at",ReservationStore.iso(o.get("assigned_at")));result.put("accepted_at",ReservationStore.iso(a.get("accepted_at")));return result;
    }
    private Map<String,Object> result(Map<String,Object> o,Map<String,Object> a,boolean changed){
        var r=new LinkedHashMap<String,Object>();r.put("assignment_id",a.get("id"));r.put("order_id",o.get("id"));r.put("technician_id",a.get("technician_id"));
        r.put("assignment_status",a.get("status"));r.put("order_status",o.get("status"));r.put("assigned_at",ReservationStore.iso(o.get("assigned_at")));
        r.put("accepted_at",ReservationStore.iso(a.get("accepted_at")));r.put("changed",changed);return r;
    }
    public JsonNode assign(MerchantActor actor,String key,long id,long technician){
        ServiceCatalog.validateId(id);ServiceCatalog.validateId(technician);
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/orders/"+id+"/assign",key,db.mapper.valueToTree(Map.of("technician_id",technician)),()->{
            db.merchant(actor,true);order(id,actor.merchantId(),false);eligible(technician,actor.merchantId(),true);
            order(id,actor.merchantId(),true);lockEvidence(id);
        },()->{
            var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var p=sheet(id,true);prerequisites(o,p);var a=assignment(id,true);
            if(a!=null){complete(o,a);if(ReservationStore.number(a,"technician_id")!=technician || !"ASSIGNED".equals(a.get("status")))throw conflict();
                var current=result(o,a,false);return new Change("ORDER_ASSIGN","technician_assignment",ReservationStore.number(a,"id"),current,current,current,false);}
            if(o.get("assigned_at")!=null)throw conflict();
            long created=db.insert("INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(?,?,?,'ASSIGNED',?)",id,actor.merchantId(),technician,actor.staffId());
            db.jdbc.update("UPDATE `order` SET assigned_at=? WHERE id=?",ReservationStore.time(db.now()),id);
            o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);a=assignment(id,true);var after=result(o,a,true);
            return new Change("ORDER_ASSIGN","technician_assignment",created,Map.of("order_id",id),after,after);
        });
    }
    private Map<String,Object> own(TechnicianActor actor,long id,boolean lock){
        // Check ownership before revealing any state or locking another person's order.
        db.one("SELECT a.id FROM technician_assignment a JOIN `order` o ON o.id=a.order_id WHERE o.id=? AND a.technician_id=? AND a.merchant_id=? AND o.merchant_id=? AND a.is_deleted=0 AND o.is_deleted=0",id,actor.staffId(),actor.merchantId(),actor.merchantId());
        var o=order(id,actor.merchantId(),lock);var a=assignment(id,lock);
        if(a==null || ReservationStore.number(a,"technician_id")!=actor.staffId() || ReservationStore.number(a,"is_deleted")!=0)throw ReservationStore.missing();
        complete(o,a);return o;
    }
    private Map<String,Object> technicianView(Map<String,Object> o,Map<String,Object> a){
        var r=result(o,a,false);r.remove("technician_id");r.remove("changed");r.put("order_no",o.get("order_no"));
        r.put("project_snapshot",MerchantOrders.snapshot(db.parse(o.get("project_snapshot")),"project_snapshot"));
        r.put("appointment_snapshot",MerchantOrders.snapshot(db.parse(o.get("appointment_snapshot")),"appointment_snapshot"));
        r.put("can_accept","ASSIGNED".equals(a.get("status")) && OrderStatus.RECEIVED.equals(o.get("status")) && confirmed(o,sheet(ReservationStore.number(o,"id"),false)));return r;
    }
    public Map<String,Object> detail(TechnicianActor actor,long id){
        ServiceCatalog.validateId(id);return db.reads.execute(tx->{db.technician(actor,appId,false);var o=own(actor,id,false);return technicianView(o,assignment(id,false));});
    }
    public Map<String,Object> list(TechnicianActor actor,String status,int page,int size){
        ServiceCatalog.validate(null,page,size);if(status!=null&&!Set.of("ASSIGNED","ACCEPTED").contains(status))throw ReservationInput.bad();
        return db.reads.execute(tx->{db.technician(actor,appId,false);
            String where=" FROM technician_assignment a JOIN `order` o ON o.id=a.order_id WHERE a.technician_id=? AND a.merchant_id=? AND o.merchant_id=? AND a.is_deleted=0 AND o.is_deleted=0"+(status==null?"":" AND a.status=?");
            var args=new ArrayList<Object>(List.of(actor.staffId(),actor.merchantId(),actor.merchantId()));if(status!=null)args.add(status);
            long total=db.jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray());args.add(size);args.add((page-1L)*size);
            var rows=db.jdbc.queryForList("SELECT o.*"+where+" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",args.toArray());
            var views=new ArrayList<Map<String,Object>>();for(var o:rows){var a=assignment(ReservationStore.number(o,"id"),false);complete(o,a);views.add(technicianView(o,a));}
            return ReservationStore.page(views,total,page,size);
        });
    }
    public JsonNode accept(TechnicianActor actor,String key,long id){
        ServiceCatalog.validateId(id);
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/tech/orders/"+id+"/accept",key,db.mapper.createObjectNode(),()->{
            db.technician(actor,appId,true);own(actor,id,true);sheet(id,true);
        },()->{
            var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var a=assignment(id,true);complete(o,a);
            if("ACCEPTED".equals(a.get("status")) && OrderStatus.IN_SERVICE.equals(o.get("status"))){var current=result(o,a,false);return new Change("ORDER_TECH_ACCEPT","order",id,current,current,current,false);}
            prerequisites(o,sheet(id,true));if(!"ASSIGNED".equals(a.get("status")) || !OrderStatus.can(String.valueOf(o.get("status")),OrderStatus.IN_SERVICE))throw conflict();
            var before=result(o,a,false);var now=ReservationStore.time(db.now());
            db.jdbc.update("UPDATE technician_assignment SET status='ACCEPTED',accepted_at=? WHERE id=?",now,a.get("id"));
            db.jdbc.update("UPDATE `order` SET status='IN_SERVICE' WHERE id=?",id);
            db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES(?,?,'RECEIVED','IN_SERVICE','ORDER_TECH_ACCEPT','staff_account',?,?)",id,actor.merchantId(),actor.staffId(),now);
            var after=result(db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id),assignment(id,true),true);
            return new Change("ORDER_TECH_ACCEPT","order",id,before,after,after);
        });
    }
}
