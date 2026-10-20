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
    // Candidates are read without locks and then locked one primary key at a time: a single
    // range `FOR UPDATE` over `order` also takes a gap lock on the neighbouring slot range,
    // even when nothing is expired, and concurrent creations in adjacent slots deadlocked on
    // it (A4c-2 evidence: idx_slot_status_expiry gap lock vs INSERT INTO `order`).
    // A hold that commits after this transaction's snapshot is not closed here; the seat
    // counter still counts it, so the miss costs availability, never overbooking, and the
    // next expiry pass closes it.
    // Returns how many holds were closed so callers can keep the slot counter in step
    // without re-reading the order table inside the same transaction.
    int expireSlot(long slot,Instant now){
        var candidates=db.jdbc.queryForList("SELECT id FROM `order` WHERE slot_id=? AND status='PENDING_PAYMENT' AND expires_at<=? AND is_deleted=0 ORDER BY id",slot,ReservationStore.time(now));
        int closed=0;
        for(var candidate:candidates){
            long id=((Number)candidate.get("id")).longValue();var row=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
            if(!"PENDING_PAYMENT".equals(row.get("status")) || row.get("expires_at")==null || ReservationStore.instant(row.get("expires_at")).isAfter(now))continue;
            var before=db.orderRow(row,true);if(closeOrder(id,slot,"PAYMENT_EXPIRED",now)==0)continue;closed++;var after=db.orderRow(db.one("SELECT * FROM `order` WHERE id=?",id),true);
            db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,'ORDER_EXPIRE','order',?,?,?,?)",id,db.json(before),db.json(after),UUID.randomUUID().toString());
        }
        return closed;
    }
    int closeOrder(long id,long slot,String reason,Instant now){
        int changed=db.jdbc.update("UPDATE `order` SET status='CLOSED',close_reason=?,closed_at=? WHERE id=? AND status='PENDING_PAYMENT'",reason,ReservationStore.time(now),id);
        // The seat counter is decremented, never recomputed: a recompute needs a read over
        // `order`, and every caller already holds the slot row lock, which is what makes
        // the counter authoritative (see ReservationStore.occupied).
        if(changed==1){ServiceNotifications.enqueue(db,id,"ORDER_CLOSED");db.jdbc.update("UPDATE payment SET status='CLOSED',closed_at=? WHERE order_id=? AND status IN ('CREATED','PENDING')",ReservationStore.time(now),id);CouponTransactions.release(db,id);db.jdbc.update("UPDATE appointment_slot SET reserved_count=GREATEST(reserved_count-1,0) WHERE id=?",slot);}
        return changed;
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
