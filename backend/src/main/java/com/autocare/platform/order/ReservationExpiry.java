package com.autocare.platform.order;

import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

public class ReservationExpiry {
    private static final Logger log=LoggerFactory.getLogger(ReservationExpiry.class);
    final ReservationStore db;
    public ReservationExpiry(ReservationStore db){this.db=db;}
    // Caller holds merchant and slot locks. All order locks follow those locks.
    void expireSlot(long slot,Instant now){
        var rows=db.jdbc.queryForList("SELECT * FROM `order` WHERE slot_id=? AND status='PENDING_PAYMENT' AND expires_at<=? AND is_deleted=0 ORDER BY id FOR UPDATE",slot,ReservationStore.time(now));
        for(var row:rows){long id=ReservationStore.number(row,"id");var before=db.orderRow(row,true);closeOrder(id,slot,"PAYMENT_EXPIRED",now);var after=db.orderRow(db.one("SELECT * FROM `order` WHERE id=?",id),true);
            db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,'ORDER_EXPIRE','order',?,?,?,?)",id,db.json(before),db.json(after),UUID.randomUUID().toString());
        }
    }
    void closeOrder(long id,long slot,String reason,Instant now){
        int changed=db.jdbc.update("UPDATE `order` SET status='CLOSED',close_reason=?,closed_at=? WHERE id=? AND status='PENDING_PAYMENT'",reason,ReservationStore.time(now),id);
        if(changed==1){long occupied=db.occupied(slot,now);db.jdbc.update("UPDATE appointment_slot SET reserved_count=? WHERE id=?",occupied,slot);}
    }
    @Scheduled(fixedDelayString="${ORDER_EXPIRY_INTERVAL_MS:30000}")
    public void sweep(){
        List<Map<String,Object>> rows;
        try{rows=db.jdbc.queryForList("SELECT id,merchant_id,slot_id FROM `order` WHERE status='PENDING_PAYMENT' AND expires_at<=? AND slot_id IS NOT NULL AND is_deleted=0 ORDER BY expires_at,id LIMIT 100",ReservationStore.time(db.now()));}
        catch(RuntimeException error){log.warn("订单过期候选查询暂不可用");return;}
        for(var row:rows)try{db.transactions.executeWithoutResult(tx->{long slot=ReservationStore.number(row,"slot_id");db.lockShop(ReservationStore.number(row,"merchant_id"));db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",slot);expireSlot(slot,db.now());});}
        catch(RuntimeException error){log.warn("订单过期事务失败，将重试");}
    }
}
