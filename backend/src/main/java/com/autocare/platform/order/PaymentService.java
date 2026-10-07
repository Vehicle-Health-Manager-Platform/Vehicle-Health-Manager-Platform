package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PaymentService {
    final ReservationStore db;final ReservationExpiry expiry;final PaymentChannels channels;
    public PaymentService(ReservationStore db,ReservationExpiry expiry,PaymentChannels channels){this.db=db;this.expiry=expiry;this.channels=channels;}
    Map<String,Object> lockOrder(long id,VehicleOwner owner){
        var meta=owner==null?db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND is_deleted=0",id):db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());
        db.lockShop(ReservationStore.number(meta,"merchant_id"));if(meta.get("slot_id")==null)throw conflict();
        db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",meta.get("slot_id"));
        return db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
    }
    public JsonNode create(VehicleOwner owner,String key,long id,String channel){
        PaymentChannel adapter=channels.create(channel);
        return db.writes.execute(new Actor("user",owner.id()),"POST","/api/payments/create",key,db.mapper.valueToTree(Map.of("order_id",id,"channel",channel)),()->{db.owner(owner,true);lockOrder(id,owner);},()->{
            var order=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);Instant now=db.now();
            if(!"PENDING_PAYMENT".equals(order.get("status"))||order.get("expires_at")==null||!ReservationStore.instant(order.get("expires_at")).isAfter(now)||!ReservationStore.instant(order.get("appointment_at")).isAfter(now))throw conflict();
            var live=db.jdbc.queryForList("SELECT * FROM payment WHERE order_id=? AND status IN ('CREATED','PENDING') AND is_deleted=0 ORDER BY id DESC FOR UPDATE",id);
            if(!live.isEmpty()){var row=live.get(0);if(!channel.equals(row.get("channel")))throw conflict();var result=row(row);result.put("channel_payload",adapter.prepare(row.get("payment_no").toString(),ReservationStore.instant(row.get("expires_at"))));return new Change("PAYMENT_CREATE_REPLAY","payment",ReservationStore.number(row,"id"),Map.of(),row(row),result);}
            String no="P"+UUID.randomUUID().toString().replace("-","");Instant deadline=ReservationStore.instant(order.get("expires_at"));
            var payload=adapter.prepare(no,deadline);long payment=db.insert("INSERT INTO payment(order_id,channel,payment_no,currency,amount,status,expires_at,created_at) VALUES(?,?,?,'CNY',?,'PENDING',?,?)",id,channel,no,order.get("pay_amount"),ReservationStore.time(deadline),ReservationStore.time(now));
            var row=db.one("SELECT * FROM payment WHERE id=? FOR UPDATE",payment);var result=row(row);result.put("channel_payload",payload);
            return new Change("PAYMENT_CREATE","payment",payment,Map.of(),row(row),result);
        });
    }
    public Map<String,Object> detail(VehicleOwner owner,long id){return db.reads.execute(tx->{db.owner(owner,false);return row(db.one("SELECT p.* FROM payment p JOIN `order` o ON o.id=p.order_id WHERE p.id=? AND o.user_id=? AND o.is_deleted=0 AND p.is_deleted=0",id,owner.id()));});}
    Map<String,Object> row(Map<String,Object> p){
        var result=new LinkedHashMap<String,Object>();long id=ReservationStore.number(p,"id");result.put("payment_id",id);result.put("payment_no",p.get("payment_no"));result.put("order_id",p.get("order_id"));result.put("channel",p.get("channel"));result.put("test_mode","LOCAL_TEST".equals(p.get("channel")));result.put("amount",p.get("amount").toString());result.put("currency",p.get("currency"));result.put("status",p.get("status"));result.put("expires_at",ReservationStore.iso(p.get("expires_at")));result.put("paid_at",ReservationStore.iso(p.get("paid_at")));result.put("requires_review",db.jdbc.queryForObject("SELECT COUNT(*) FROM payment_exception WHERE payment_id=?",Long.class,id)>0);return result;
    }
    public void notify(PaymentNotice n){
        try{db.transactions.executeWithoutResult(tx->{
            var meta=db.one("SELECT order_id FROM payment WHERE id=? AND channel='LOCAL_TEST' AND is_deleted=0",n.paymentId());long orderId=ReservationStore.number(meta,"order_id");
            var order=lockOrder(orderId,null);var p=db.one("SELECT * FROM payment WHERE id=? FOR UPDATE",n.paymentId());
            if(!n.orderNo().equals(order.get("order_no"))||!n.currency().equals(p.get("currency"))||n.amount().compareTo((BigDecimal)p.get("amount"))!=0||n.amount().compareTo((BigDecimal)order.get("pay_amount"))!=0)throw ReservationInput.bad();
            var events=db.jdbc.queryForList("SELECT * FROM payment_event WHERE channel='LOCAL_TEST' AND event_id=? FOR UPDATE",n.eventId());
            if(!events.isEmpty()){if(!n.hash().equals(events.get(0).get("event_hash")))throw conflict();return;}
            String status=p.get("status").toString(),outcome;Instant now=db.now();var before=row(p);
            if("SUCCEEDED".equals(status)){
                if(!n.transaction().equals(p.get("channel_payment_no")))throw conflict();outcome=n.status().equals("SUCCEEDED")?"DUPLICATE":"IGNORED";
            }else if("FAILED".equals(n.status())){
                if(Set.of("CREATED","PENDING").contains(status)){db.jdbc.update("UPDATE payment SET status='FAILED',failed_at=? WHERE id=?",ReservationStore.time(now),n.paymentId());audit("PAYMENT_FAILED",n.paymentId(),before,row(db.one("SELECT * FROM payment WHERE id=? FOR UPDATE",n.paymentId())));outcome="FAILED";}else outcome="IGNORED";
            }else{
                long slot=ReservationStore.number(order,"slot_id");
                if("PENDING_PAYMENT".equals(order.get("status"))&&!ReservationStore.instant(order.get("expires_at")).isAfter(now)){expiry.expireSlot(slot,now);order=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",orderId);}
                String reason=null;
                if("PAID".equals(order.get("status")))reason="DUPLICATE_PAYMENT";
                else if("CLOSED".equals(order.get("status")))reason="OWNER_CANCELLED".equals(order.get("close_reason"))?"ORDER_CANCELLED":"PAYMENT_EXPIRED".equals(order.get("close_reason"))?"ORDER_EXPIRED":"ORDER_CLOSED";
                else if("FAILED".equals(status))reason="FAILED_PAYMENT_SUCCEEDED";
                else if(!"PENDING_PAYMENT".equals(order.get("status"))||!Set.of("CREATED","PENDING").contains(status))reason="ORDER_CLOSED";
                // Unique channel transaction numbers prevent cross-order receipts.
                db.jdbc.update("UPDATE payment SET status='SUCCEEDED',channel_payment_no=?,paid_at=? WHERE id=?",n.transaction(),ReservationStore.time(n.occurred()),n.paymentId());
                if(reason==null){var old=db.orderRow(order,true);db.jdbc.update("UPDATE `order` SET status='PAID' WHERE id=?",orderId);db.jdbc.update("UPDATE payment SET status='CLOSED',closed_at=? WHERE order_id=? AND id<>? AND status IN ('CREATED','PENDING')",ReservationStore.time(now),orderId,n.paymentId());auditOrder("ORDER_PAID",orderId,old,db.orderRow(db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",orderId),true));outcome="PAID";}
                else{db.jdbc.update("INSERT INTO payment_exception(payment_id,order_id,channel,amount,reason) VALUES(?,?,'LOCAL_TEST',?,?)",n.paymentId(),orderId,n.amount(),reason);outcome="REVIEW";}
                audit(reason==null?"PAYMENT_SUCCEEDED":"PAYMENT_REVIEW",n.paymentId(),before,row(db.one("SELECT * FROM payment WHERE id=? FOR UPDATE",n.paymentId())));
            }
            db.jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST',?,?,?,?)",n.eventId(),n.paymentId(),n.hash(),outcome);
        });}catch(ResponseStatusException error){throw error;}catch(org.springframework.dao.DuplicateKeyException error){throw conflict();}catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"支付通知暂不可用，请重试");}
    }
    void audit(String action,long id,Map<String,Object> before,Map<String,Object> after){auditResource(action,"payment",id,before,after);}
    void auditOrder(String action,long id,Map<String,Object> before,Map<String,Object> after){auditResource(action,"order",id,before,after);}
    void auditResource(String action,String type,long id,Map<String,Object> before,Map<String,Object> after){db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,?,?,?,?,?,?)",action,type,id,db.json(before),db.json(after),UUID.randomUUID().toString());}
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"支付或订单状态已变化，请刷新确认");}
}
