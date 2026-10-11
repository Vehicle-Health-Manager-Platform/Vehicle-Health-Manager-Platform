package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Agreement, original payment and cancellation are server-owned; supplier I/O is separate. */
public class OrderRefunds {
    final ReservationStore db;final PaymentService payments;final RefundChannels channels;
    public OrderRefunds(ReservationStore db,PaymentService payments,RefundChannels channels){this.db=db;this.payments=payments;this.channels=channels;}
    static final Set<String> ELIGIBLE=OrderStatus.REFUND_CANCELLABLE;
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"订单或退款状态已变化，请刷新确认");}
    static void manager(MerchantActor actor){if(!actor.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅本店店长可处理退款");}
    static boolean nonempty(Object value){return value instanceof String s&&!s.isBlank();}
    static String text(String value){if(value==null||value.strip().isEmpty()||value.strip().length()>256)throw ReservationInput.bad();return value.strip();}
    Map<String,Object> lock(VehicleOwner owner,MerchantActor actor,long id){
        if(owner!=null)db.owner(owner,true);
        if(actor!=null){manager(actor);db.merchant(actor,true);db.one("SELECT id FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());}
        return payments.lockOrder(id,owner);
    }
    Map<String,Object> refund(long id,boolean lock){var rows=db.jdbc.queryForList("SELECT * FROM refund WHERE order_id=? ORDER BY id"+(lock?" FOR UPDATE":""),id);if(rows.size()>1||(!rows.isEmpty()&&(rows.get(0).get("refund_no")==null||ReservationStore.number(rows.get(0),"is_deleted")!=0)))throw conflict();return rows.isEmpty()?null:rows.get(0);}
    Map<String,Object> payment(Map<String,Object> order){
        long id=ReservationStore.number(order,"id");
        if(!ELIGIBLE.contains(order.get("status")))throw conflict();
        var rows=db.jdbc.queryForList("SELECT * FROM payment WHERE order_id=? AND is_deleted=0 AND status='SUCCEEDED' ORDER BY id FOR UPDATE",id);
        if(rows.size()!=1||!db.jdbc.queryForList("SELECT id FROM payment_exception WHERE order_id=? AND is_deleted=0 FOR UPDATE",id).isEmpty())throw conflict();
        var p=rows.get(0);if(!"CNY".equals(p.get("currency"))||((BigDecimal)p.get("amount")).compareTo((BigDecimal)order.get("pay_amount"))!=0||((BigDecimal)p.get("amount")).signum()<=0||p.get("paid_at")==null||!OrderRefunds.nonempty(p.get("payment_no"))||!OrderRefunds.nonempty(p.get("channel_payment_no")))throw conflict();
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE payment_id=? AND channel=? AND outcome='PAID' AND event_hash<>'' AND is_deleted=0",Long.class,p.get("id"),p.get("channel"))!=1L)throw conflict();
        channels.get(p.get("channel").toString());return p;
    }
    public Map<String,Object> detail(VehicleOwner owner,MerchantActor actor,long id){return db.reads.execute(tx->{
        Map<String,Object> o;if(owner!=null){db.owner(owner,false);o=db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());}
        else{manager(actor);db.merchant(actor,false);o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());}
        return view(o,refund(id,false));
    });}
    Map<String,Object> view(Map<String,Object> o,Map<String,Object> r){
        var out=new LinkedHashMap<String,Object>();out.put("order_id",o.get("id"));out.put("order_status",o.get("status"));out.put("can_request",ELIGIBLE.contains(o.get("status"))&&(r==null||Set.of("REJECTED","WITHDRAWN").contains(r.get("status"))));
        Map<String,Object> result=null;if(r!=null){result=new LinkedHashMap<>();for(String k:List.of("refund_no","payment_id","amount","currency","status","revision","from_status","reason","decision_note"))result.put(k,k.equals("amount")?r.get(k).toString():r.get(k));result.put("refund_id",r.get("id"));for(String k:List.of("owner_consented_at","merchant_consented_at","refunded_at"))result.put(k,ReservationStore.iso(r.get(k)));var p=db.one("SELECT channel FROM payment WHERE id=?",r.get("payment_id"));result.put("test_mode","LOCAL_TEST".equals(p.get("channel")));}
        out.put("refund",result);return out;
    }
    public JsonNode request(VehicleOwner owner,String key,long id,String reason){
        String note=text(reason);return db.writes.execute(new Actor("user",owner.id()),"POST","/api/order/"+id+"/refund",key,db.mapper.valueToTree(Map.of("reason",note)),()->lock(owner,null,id),()->{
            var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var p=payment(o);var r=refund(id,true);var before=view(o,r);
            if(r!=null&&(!Set.of("REJECTED","WITHDRAWN").contains(r.get("status"))||!Objects.equals(r.get("payment_id"),p.get("id"))))throw conflict();
            if(r==null)db.insert("INSERT INTO refund(order_id,payment_id,refund_no,amount,currency,status,revision,from_status,reason,owner_consented_at,requested_by) VALUES(?,?,?,?,'CNY','REQUESTED',1,?,?,?,?)",id,p.get("id"),"R"+UUID.randomUUID().toString().replace("-",""),p.get("amount"),o.get("status"),note,ReservationStore.time(db.now()),owner.id());
            else db.jdbc.update("UPDATE refund SET status='REQUESTED',revision=revision+1,from_status=?,reason=?,owner_consented_at=?,requested_by=?,merchant_consented_at=NULL,merchant_actor=NULL,decision_note=NULL WHERE id=?",o.get("status"),note,ReservationStore.time(db.now()),owner.id(),r.get("id"));
            var after=view(o,refund(id,true));return new Change("REFUND_REQUEST","refund",ReservationStore.number(refund(id,true),"id"),before,after,after);
        });
    }
    public JsonNode withdraw(VehicleOwner owner,String key,long id,long revision){return db.writes.execute(new Actor("user",owner.id()),"POST","/api/order/"+id+"/refund/withdraw",key,db.mapper.valueToTree(Map.of("revision",revision)),()->lock(owner,null,id),()->{
        var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var r=refund(id,true);if(r==null||!"REQUESTED".equals(r.get("status"))||ReservationStore.number(r,"revision")!=revision)throw conflict();var before=view(o,r);
        db.jdbc.update("UPDATE refund SET status='WITHDRAWN',revision=revision+1 WHERE id=?",r.get("id"));var after=view(o,refund(id,true));return new Change("REFUND_WITHDRAW","refund",ReservationStore.number(r,"id"),before,after,after);
    });}
    public JsonNode decide(MerchantActor actor,String key,long id,long revision,String decision,String note){
        manager(actor);if(decision==null||!Set.of("ACCEPT","REJECT").contains(decision))throw ReservationInput.bad();String text=note==null?null:text(note);if("REJECT".equals(decision)&&text==null)throw ReservationInput.bad();
        var body=new LinkedHashMap<String,Object>();body.put("revision",revision);body.put("decision",decision);if(text!=null)body.put("note",text);
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/orders/"+id+"/refund/decision",key,db.mapper.valueToTree(body),()->lock(null,actor,id),()->{
            var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var p="ACCEPT".equals(decision)?payment(o):null;var r=refund(id,true);
            if(r==null||!"REQUESTED".equals(r.get("status"))||ReservationStore.number(r,"revision")!=revision)throw conflict();var before=view(o,r);
            if("REJECT".equals(decision))db.jdbc.update("UPDATE refund SET status='REJECTED',revision=revision+1,merchant_actor=?,decision_note=? WHERE id=?",actor.staffId(),text,r.get("id"));
            else{
                if(!Objects.equals(r.get("payment_id"),p.get("id"))||!Objects.equals(r.get("from_status"),o.get("status"))||r.get("owner_consented_at")==null||((BigDecimal)r.get("amount")).compareTo((BigDecimal)p.get("amount"))!=0)throw conflict();
                var now=ReservationStore.time(db.now());db.jdbc.update("UPDATE refund SET status='PENDING',revision=revision+1,merchant_actor=?,merchant_consented_at=?,decision_note=?,next_attempt_at=? WHERE id=?",actor.staffId(),now,text,now,r.get("id"));
                db.jdbc.update("UPDATE `order` SET status='CLOSED',close_reason='REFUND_CANCELLED',closed_at=? WHERE id=?",now,id);
                db.jdbc.update("UPDATE payment SET status='CLOSED',closed_at=? WHERE order_id=? AND status IN ('CREATED','PENDING')",now,id);
                db.jdbc.update("UPDATE appointment_slot SET reserved_count=? WHERE id=?",db.occupied(ReservationStore.number(o,"slot_id"),db.now()),o.get("slot_id"));
                db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,note,occurred_at) VALUES(?,?,?,'CLOSED','ORDER_REFUND_CANCEL','staff_account',?,?,?)",id,actor.merchantId(),o.get("status"),actor.staffId(),text,now);
            }
            var after=view(db.one("SELECT * FROM `order` WHERE id=?",id),refund(id,true));return new Change("ACCEPT".equals(decision)?"REFUND_APPROVE":"REFUND_REJECT","refund",ReservationStore.number(r,"id"),before,after,after);
        });
    }
}
