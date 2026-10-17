package com.autocare.platform.order;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import static com.autocare.platform.order.ReservationStore.*;
/** Persistent retries derive from committed facts and cannot change cash states. */
public class CouponRecovery {
 final ReservationStore db;
 public CouponRecovery(ReservationStore db){this.db=db;}
 void reconcile(long order){db.transactions.executeWithoutResult(t->{
  var meta=db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND is_deleted=0",order);
  db.lockShop(number(meta,"merchant_id"));db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",meta.get("slot_id"));
  var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",order);var a=CouponTransactions.allocation(db,order,false);
  if(a==null||Set.of("RELEASED","RETURNED").contains(a.get("state")))return;
  if(a.get("recovery_after")!=null&&instant(a.get("recovery_after")).isAfter(db.now()))return;
  db.jdbc.update("UPDATE order_coupon_allocation SET recovery_after=?,recovery_error=NULL WHERE order_id=?",time(db.now().plusSeconds(30)),order);
  if("CLOSED".equals(o.get("status"))&&Set.of("PAYMENT_EXPIRED","OWNER_CANCELLED").contains(o.get("close_reason"))){CouponTransactions.release(db,order);return;}
  var paid=db.jdbc.queryForList("SELECT p.id FROM payment p JOIN payment_event e ON e.payment_id=p.id AND e.channel=p.channel WHERE p.order_id=? AND p.status='SUCCEEDED' AND p.is_deleted=0 AND e.outcome='PAID' AND e.is_deleted=0",order);
  if(paid.size()!=1)return;
  if("RESERVED".equals(a.get("state")))CouponTransactions.consume(db,order,number(paid.get(0),"id"));
  var returned=db.jdbc.queryForList("SELECT r.id FROM refund r JOIN refund_event e ON e.refund_id=r.id WHERE r.order_id=? AND r.status='SUCCEEDED' AND r.is_deleted=0 AND e.outcome='SUCCEEDED' AND e.is_deleted=0",order);
  if(returned.size()==1)CouponTransactions.returned(db,order,number(returned.get(0),"id"));
 });}
 @Scheduled(fixedDelayString="${COUPON_RECOVERY_INTERVAL_MS:30000}") public void sweep(){
  try{
   var rows=db.jdbc.queryForList("SELECT a.order_id FROM order_coupon_allocation a JOIN `order` o ON o.id=a.order_id WHERE o.is_deleted=0 AND (a.recovery_after IS NULL OR a.recovery_after<=?) AND ((a.state='RESERVED' AND o.status<>'PENDING_PAYMENT') OR (a.state='CONSUMED' AND EXISTS(SELECT 1 FROM refund r WHERE r.order_id=a.order_id AND r.status='SUCCEEDED' AND r.is_deleted=0))) ORDER BY COALESCE(a.recovery_after,a.updated_at),a.order_id LIMIT 100",time(db.now()));
   for(var r:rows)try{reconcile(number(r,"order_id"));}catch(RuntimeException unavailable){
    try{db.jdbc.update("UPDATE order_coupon_allocation SET recovery_error='FACTS_UNAVAILABLE',recovery_after=? WHERE order_id=? AND state IN ('RESERVED','CONSUMED')",time(db.now().plusSeconds(300)),r.get("order_id"));}catch(RuntimeException outage){/* next pass retries */}
   }
  }catch(RuntimeException outage){/* database outage, no raw source payload logging */}
 }
}
