package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

/** Durable refund outbox: query before submit, no supplier I/O under database locks. */
public class RefundRecovery {
    final OrderRefunds refunds;final ReservationStore db;final boolean enabled;
    public RefundRecovery(OrderRefunds refunds,boolean enabled){this.refunds=refunds;db=refunds.db;this.enabled=enabled;if(enabled&&!refunds.channels.get("LOCAL_TEST").canTransact())throw new IllegalStateException("退款恢复须配置隔离供应商");}
    record Claim(long id,String token,String status,RefundChannel channel,RefundChannel.Request request) {}
    RefundChannel.Request trusted(Map<String,Object> o,Map<String,Object> p,Map<String,Object> r){
        if(!"CLOSED".equals(o.get("status"))||!"REFUND_CANCELLED".equals(o.get("close_reason"))||!"SUCCEEDED".equals(p.get("status"))||!Objects.equals(r.get("order_id"),o.get("id"))||!Objects.equals(r.get("payment_id"),p.get("id"))||!Objects.equals(p.get("order_id"),o.get("id"))||r.get("owner_consented_at")==null||r.get("merchant_consented_at")==null||r.get("refund_no")==null||!r.get("refund_no").toString().matches("R[a-f0-9]{32}")||!OrderRefunds.ELIGIBLE.contains(r.get("from_status"))||p.get("paid_at")==null||!OrderRefunds.nonempty(p.get("payment_no"))||!OrderRefunds.nonempty(p.get("channel_payment_no"))||!"CNY".equals(p.get("currency"))||!"CNY".equals(r.get("currency"))||((BigDecimal)r.get("amount")).signum()<=0||((BigDecimal)r.get("amount")).compareTo((BigDecimal)p.get("amount"))!=0||((BigDecimal)p.get("amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0)throw OrderRefunds.conflict();
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE payment_id=? AND channel=? AND outcome='PAID' AND event_hash<>'' AND is_deleted=0",Long.class,p.get("id"),p.get("channel"))!=1L)throw OrderRefunds.conflict();
        return new RefundChannel.Request(ReservationStore.number(r,"id"),r.get("refund_no").toString(),ReservationStore.number(p,"id"),p.get("payment_no").toString(),p.get("channel_payment_no").toString(),(BigDecimal)r.get("amount"),r.get("currency").toString());
    }
    Claim claim(MerchantActor actor,long id){return db.transactions.execute(tx->{
        var meta=db.one("SELECT order_id,payment_id FROM refund WHERE id=? AND is_deleted=0",id);var o=refunds.lock(null,actor,ReservationStore.number(meta,"order_id"));var p=db.one("SELECT * FROM payment WHERE id=? AND is_deleted=0 FOR UPDATE",meta.get("payment_id"));var r=refunds.refund(ReservationStore.number(meta,"order_id"),true);
        if(r==null||ReservationStore.number(r,"id")!=id)throw OrderRefunds.conflict();
        if("SUCCEEDED".equals(r.get("status")))return null;if(!Set.of("PENDING","FAILED").contains(r.get("status")))throw OrderRefunds.conflict();
        var request=trusted(o,p,r);var channel=refunds.channels.get(p.get("channel").toString());if(!channel.canTransact())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款供应商尚未配置");Instant now=db.now();
        if(r.get("next_attempt_at")!=null&&ReservationStore.instant(r.get("next_attempt_at")).isAfter(now))return null;
        String token=UUID.randomUUID().toString();db.jdbc.update("UPDATE refund SET dispatch_token=?,attempts=attempts+1,next_attempt_at=? WHERE id=?",token,ReservationStore.time(now.plusSeconds(60)),id);return new Claim(id,token,r.get("status").toString(),channel,request);
    });}
    public Map<String,Object> reconcile(MerchantActor actor,long order){OrderRefunds.manager(actor);var value=refunds.detail(null,actor,order);var r=(Map<?,?>)value.get("refund");if(r==null)throw OrderRefunds.conflict();var c=claim(actor,((Number)r.get("refund_id")).longValue());if(c!=null)process(c);return refunds.detail(null,actor,order);}
    void process(Claim c){
        String error=null;
        try{
            var result=c.channel().query(c.request());
            if(!result.exists()&&result.notice()!=null)throw ReservationInput.bad();
            if(!result.exists()&&!"FAILED".equals(c.status()))result=c.channel().submit(c.request());
            if(!result.exists())throw new IllegalStateException("退款状态未确定");
            if(result.notice()!=null){var n=result.notice();var r=c.request();if(n.refundId()!=r.refundId()||!r.refundNo().equals(n.refundNo())||n.paymentId()!=r.paymentId()||n.amount()==null||n.amount().compareTo(r.amount())!=0||!r.currency().equals(n.currency()))throw ReservationInput.bad();try{notify(n);}catch(RuntimeException failure){error="APPLY_FAILED";}}
        }catch(ResponseStatusException|IllegalArgumentException failure){error="INVALID_RESULT";}catch(RuntimeException failure){error="SUPPLIER_UNAVAILABLE";}
        db.jdbc.update("UPDATE refund SET dispatch_token=NULL,next_attempt_at=?,last_error=? WHERE id=? AND dispatch_token=?",ReservationStore.time(db.now().plusSeconds("FAILED".equals(c.status())?300:60)),error,c.id(),c.token());
    }
    public void notify(RefundChannel.Notice n){
        try{db.transactions.executeWithoutResult(tx->{
            var meta=db.one("SELECT order_id,payment_id FROM refund WHERE id=? AND is_deleted=0",n.refundId());var o=refunds.lock(null,null,ReservationStore.number(meta,"order_id"));var p=db.one("SELECT * FROM payment WHERE id=? AND is_deleted=0 FOR UPDATE",meta.get("payment_id"));var r=refunds.refund(ReservationStore.number(meta,"order_id"),true);if(r==null)throw OrderRefunds.conflict();var request=trusted(o,p,r);
            if(!"LOCAL_TEST".equals(p.get("channel"))||n.refundId()!=request.refundId()||!n.refundNo().equals(request.refundNo())||n.paymentId()!=request.paymentId()||n.amount().compareTo(request.amount())!=0||!n.currency().equals(request.currency())||!Set.of("SUCCEEDED","FAILED").contains(n.status()))throw ReservationInput.bad();
            if(!Set.of("PENDING","FAILED","SUCCEEDED").contains(r.get("status")))throw OrderRefunds.conflict();
            var events=db.jdbc.queryForList("SELECT * FROM refund_event WHERE channel='LOCAL_TEST' AND event_id=? FOR UPDATE",n.eventId());if(!events.isEmpty()){if(!n.hash().equals(events.get(0).get("event_hash")))throw OrderRefunds.conflict();return;}
            if(r.get("channel_refund_no")!=null&&!n.transaction().equals(r.get("channel_refund_no")))throw OrderRefunds.conflict();String outcome;
            if("SUCCEEDED".equals(r.get("status")))outcome="SUCCEEDED".equals(n.status())?"DUPLICATE":"IGNORED";
            else{var before=refunds.view(o,r);db.jdbc.update("UPDATE refund SET status=?,channel_refund_no=?,refunded_at=? WHERE id=?",n.status(),n.transaction(),"SUCCEEDED".equals(n.status())?ReservationStore.time(n.occurred()):null,n.refundId());if("SUCCEEDED".equals(n.status()))CommissionLedger.refunded(db,o,p,refunds.refund(ReservationStore.number(o,"id"),true));var after=refunds.view(o,refunds.refund(ReservationStore.number(o,"id"),true));db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,?,'refund',?,?,?,?)","REFUND_"+n.status(),n.refundId(),db.json(before),db.json(after),UUID.randomUUID().toString());outcome=n.status();}
            db.jdbc.update("INSERT INTO refund_event(channel,event_id,refund_id,event_hash,outcome) VALUES('LOCAL_TEST',?,?,?,?)",n.eventId(),n.refundId(),n.hash(),outcome);
        });}catch(ResponseStatusException error){throw error;}catch(org.springframework.dao.DuplicateKeyException error){throw OrderRefunds.conflict();}catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"退款通知暂不可用，请重试");}
    }
    @Scheduled(fixedDelayString="${REFUND_RECOVERY_INTERVAL_MS:60000}") public void sweep(){
        if(!enabled)return;try{
            var rows=db.jdbc.queryForList("SELECT r.id FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.refund_no IS NOT NULL AND r.is_deleted=0 AND p.channel='LOCAL_TEST' AND r.status IN ('PENDING','FAILED') AND (r.next_attempt_at IS NULL OR r.next_attempt_at<=?) ORDER BY COALESCE(r.next_attempt_at,r.created_at),r.id LIMIT 20",ReservationStore.time(db.now()));
            for(var row:rows)try{var c=claim(null,ReservationStore.number(row,"id"));if(c!=null)process(c);}catch(RuntimeException error){try{db.jdbc.update("UPDATE refund SET last_error='RECOVERY_FAILED',next_attempt_at=? WHERE id=? AND status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at<=?)",ReservationStore.time(db.now().plusSeconds(300)),row.get("id"),ReservationStore.time(db.now()));}catch(RuntimeException unavailable){/* Next pass retries database outage. */}}
        }catch(RuntimeException unavailable){/* No raw supplier response, session or SQL is logged. */}
    }
}
