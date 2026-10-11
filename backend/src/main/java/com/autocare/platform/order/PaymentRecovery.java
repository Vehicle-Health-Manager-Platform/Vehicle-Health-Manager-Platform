package com.autocare.platform.order;

import com.autocare.platform.vehicle.VehicleOwner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

/** Durable lease; supplier I/O runs after the claiming transaction has ended. */
public class PaymentRecovery {
    final PaymentService payments;final ReservationStore db;final boolean enabled;
    public PaymentRecovery(PaymentService payments,boolean enabled){
        this.payments=payments;this.db=payments.db;this.enabled=enabled;
        if(enabled&&!payments.channels.callback().canQuery())throw new IllegalStateException("支付恢复须配置隔离渠道查询");
    }
    record Claim(long id,String token,String status,PaymentChannel channel,PaymentQuery query) {}
    public Map<String,Object> reconcile(VehicleOwner owner,long id){
        var claim=claim(owner,id);if(claim!=null)process(claim);
        return payments.detail(owner,id);
    }
    Claim claim(VehicleOwner owner,long id){
        return db.transactions.execute(tx->{
            if(owner!=null)db.owner(owner,true);
            var meta=owner==null?db.one("SELECT order_id FROM payment WHERE id=? AND is_deleted=0",id):db.one("SELECT p.order_id FROM payment p JOIN `order` o ON o.id=p.order_id WHERE p.id=? AND o.user_id=? AND o.is_deleted=0 AND p.is_deleted=0",id,owner.id());
            var order=payments.lockOrder(ReservationStore.number(meta,"order_id"),owner);
            var p=db.one("SELECT * FROM payment WHERE id=? FOR UPDATE",id);
            String status=p.get("status").toString();if("SUCCEEDED".equals(status))return null;
            var channel=payments.channels.create(p.get("channel").toString());
            if(!channel.canQuery())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"支付渠道查询尚未配置");
            if(p.get("payment_no")==null)throw PaymentService.conflict();
            Instant now=db.now();if(p.get("next_query_at")!=null&&ReservationStore.instant(p.get("next_query_at")).isAfter(now))return null;
            String token=UUID.randomUUID().toString();
            db.jdbc.update("UPDATE payment SET query_token=?,next_query_at=?,last_query_at=?,query_attempts=query_attempts+1 WHERE id=?",token,ReservationStore.time(now.plusSeconds(60)),ReservationStore.time(now),id);
            return new Claim(id,token,status,channel,new PaymentQuery(id,p.get("payment_no").toString(),order.get("order_no").toString(),(BigDecimal)p.get("amount"),p.get("currency").toString()));
        });
    }
    void process(Claim c){
        String error=null;
        try{
            var result=c.channel().query(c.query());
            if(result.isPresent()){
                var n=result.get();
                if(n.paymentId()!=c.id()||!c.query().orderNo().equals(n.orderNo())||!c.query().currency().equals(n.currency())||n.amount()==null||n.amount().compareTo(c.query().amount())!=0||!Set.of("SUCCEEDED","FAILED").contains(n.status()))throw new IllegalArgumentException("查询结果不匹配");
                try{payments.notify(n);}catch(RuntimeException failure){error="APPLY_FAILED";}
            }
        }catch(ResponseStatusException|IllegalArgumentException failure){error="INVALID_RESULT";}catch(RuntimeException failure){error="QUERY_FAILED";}
        int seconds=Set.of("CLOSED","FAILED").contains(c.status())?300:60;
        db.jdbc.update("UPDATE payment SET query_token=NULL,next_query_at=?,last_query_error=? WHERE id=? AND query_token=?",ReservationStore.time(db.now().plusSeconds(seconds)),error,c.id(),c.token());
    }
    @Scheduled(fixedDelayString="${PAYMENT_QUERY_RECOVERY_INTERVAL_MS:60000}")
    public void sweep(){
        if(!enabled)return;
        try{
            var rows=db.jdbc.queryForList("SELECT id FROM payment WHERE channel='LOCAL_TEST' AND is_deleted=0 AND status IN ('CREATED','PENDING','CLOSED','FAILED') AND payment_no IS NOT NULL AND (next_query_at IS NULL OR next_query_at<=?) ORDER BY COALESCE(next_query_at,created_at),id LIMIT 20",ReservationStore.time(db.now()));
            for(var row:rows)try{var c=claim(null,ReservationStore.number(row,"id"));if(c!=null)process(c);}catch(RuntimeException failure){
                // Malformed historical rows must not permanently occupy the front of the batch.
                try{db.jdbc.update("UPDATE payment SET next_query_at=?,last_query_error='CLAIM_FAILED' WHERE id=? AND status<>'SUCCEEDED' AND (next_query_at IS NULL OR next_query_at<=?)",ReservationStore.time(db.now().plusSeconds(300)),ReservationStore.number(row,"id"),ReservationStore.time(db.now()));}catch(RuntimeException unavailable){/* Database outage: next pass retries. */}
            }
        }catch(RuntimeException failure){/* Database outage: next scheduled pass retries. */}
    }
}
