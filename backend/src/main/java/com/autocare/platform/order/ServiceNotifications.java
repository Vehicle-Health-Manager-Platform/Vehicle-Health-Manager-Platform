package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.autocare.platform.order.ReservationStore.*;

/** Persisted source facts, atomic fanout, and current-scope inbox projection. */
public class ServiceNotifications {
 public static final List<String> KINDS=List.of("ORDER_PAID","ORDER_CHECK_IN","ORDER_ASSIGN","ORDER_TECH_ACCEPT","ORDER_SERVICE_FINISH","ORDER_COMPLETE","ORDER_CLOSED","REFUND_REQUESTED","REFUND_WITHDRAWN","REFUND_REJECTED","REFUND_ACCEPTED","REFUND_SUCCEEDED","REFUND_FAILED","DISPUTE_OPENED","DISPUTE_HANDLED","DISPUTE_REJECTED","DISPUTE_RESOLVED");
 private final ReservationStore db;private final String appId;
 public ServiceNotifications(ReservationStore db,String appId){this.db=db;this.appId=appId;}
 static void enqueue(ReservationStore db,long order,String kind){
  if(kind.startsWith("REFUND_")||kind.startsWith("DISPUTE_"))throw new IllegalArgumentException("Repeatable event requires a source identity");
  enqueue(db,order,kind,"ONCE");
 }
 static void enqueue(ReservationStore db,long order,String kind,String source){
  if(source==null||!source.matches("[A-Za-z0-9:_-]{1,96}"))throw new IllegalArgumentException("Invalid event source");
  if(!KINDS.contains(kind)||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Notification requires its business transaction");
  // Current locking reads must not reuse an earlier repeatable-read snapshot.
  // Business writers already lock the order before its assignment.
  var o=db.one("SELECT user_id,merchant_id FROM `order` WHERE id=? AND is_deleted=0 FOR UPDATE",order);
  var assigned=kind.startsWith("REFUND_")?List.<Map<String,Object>>of():db.jdbc.queryForList("SELECT technician_id FROM technician_assignment WHERE order_id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",order,o.get("merchant_id"));
  // Source uniqueness is a backstop; business idempotency normally returns before this call.
  db.jdbc.update("INSERT INTO service_notification_outbox(event_id,kind,order_id,owner_id,merchant_id,technician_id,occurred_at,source_key,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",UUID.randomUUID().toString(),kind,order,o.get("user_id"),o.get("merchant_id"),assigned.size()==1?assigned.get(0).get("technician_id"):null,time(db.now()),source,time(db.now()));
 }
 @Scheduled(fixedDelayString="${SERVICE_NOTIFICATION_DELAY_MS:5000}") public void recover(){
  var ids=db.jdbc.queryForList("SELECT id FROM service_notification_outbox WHERE status='PENDING' AND next_attempt_at<=? ORDER BY id LIMIT 20",Long.class,time(db.now()));
  for(long id:ids)try{deliver(id);}catch(RuntimeException e){
   try{db.transactions.executeWithoutResult(tx->db.jdbc.update("UPDATE service_notification_outbox SET next_attempt_at=DATE_ADD(?,INTERVAL LEAST(300,5*POW(2,LEAST(attempts,6))) SECOND),attempts=LEAST(attempts+1,1000000),last_error='DELIVERY_UNAVAILABLE' WHERE id=? AND status='PENDING'",time(db.now()),id));}catch(RuntimeException ignored){/* Pending row remains recoverable after storage returns. */}
  }
 }
 boolean deliver(long id){return Boolean.TRUE.equals(db.transactions.execute(tx->{
  var rows=db.jdbc.queryForList("SELECT * FROM service_notification_outbox WHERE id=? AND status='PENDING' AND next_attempt_at<=? FOR UPDATE SKIP LOCKED",id,time(db.now()));if(rows.isEmpty())return false;var e=rows.get(0);
  insert(e,"user",number(e,"owner_id"));insert(e,"merchant",number(e,"merchant_id"));if(e.get("technician_id")!=null)insert(e,"technician",number(e,"technician_id"));
  db.jdbc.update("UPDATE service_notification_outbox SET status='DONE',completed_at=?,last_error=NULL WHERE id=?",time(db.now()),id);return true;
 }));}
 private void insert(Map<String,Object> e,String type,long id){db.jdbc.update("INSERT INTO notification(event_id,recipient_type,recipient_id,template_key,payload,created_at) VALUES(?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE id=id",e.get("event_id"),type,id,e.get("kind"),e.get("occurred_at"));}
 private record Recipient(String type,long id,Actor actor){}
 private Recipient actor(Object a,boolean lock){
  if(a instanceof VehicleOwner u){db.owner(u,lock);return new Recipient("user",u.id(),new Actor("user",u.id()));}
  if(a instanceof MerchantActor m){db.merchant(m,lock);return new Recipient("merchant",m.merchantId(),new Actor("staff_account",m.staffId()));}
  if(a instanceof TechnicianActor t){db.technician(t,appId,lock);return new Recipient("technician",t.staffId(),new Actor("staff_account",t.staffId()));}
  throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权读取通知");
 }
 private String source(Recipient r){return " FROM notification n JOIN service_notification_outbox e ON e.event_id=n.event_id AND e.status='DONE' AND e.kind=n.template_key JOIN `order` o ON o.id=e.order_id AND o.is_deleted=0 WHERE n.recipient_type=? AND n.recipient_id=? AND n.is_deleted=0 AND "+switch(r.type()){
  case "user"->"e.owner_id=n.recipient_id AND o.user_id=n.recipient_id";
  case "merchant"->"e.merchant_id=n.recipient_id AND o.merchant_id=n.recipient_id";
  default->"e.technician_id=n.recipient_id AND o.merchant_id=e.merchant_id AND EXISTS(SELECT 1 FROM technician_assignment a JOIN staff_account s ON s.id=a.technician_id WHERE a.order_id=o.id AND a.technician_id=n.recipient_id AND a.merchant_id=o.merchant_id AND a.is_deleted=0 AND s.merchant_id=o.merchant_id)";
 };}
 public Map<String,Object> list(Object a,int page){if(page<1||page>1000000)throw ReservationInput.bad();return db.reads.execute(tx->{var r=actor(a,false);String from=source(r);var args=new Object[]{r.type(),r.id()};long total=db.jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args),unread=db.jdbc.queryForObject("SELECT COUNT(*)"+from+" AND n.read_at IS NULL",Long.class,args);
  var rows=db.jdbc.queryForList("SELECT n.id,n.read_at,e.kind,e.order_id,e.occurred_at,o.order_no,o.status AS order_status"+from+" ORDER BY e.occurred_at DESC,n.id DESC LIMIT 20 OFFSET ?",r.type(),r.id(),(page-1)*20L);var items=new ArrayList<Map<String,Object>>();for(var row:rows){var v=new LinkedHashMap<String,Object>();for(String k:List.of("id","kind","order_id","order_no","order_status"))v.put(k.equals("id")?"notification_id":k,row.get(k));v.put("occurred_at",iso(row.get("occurred_at")));v.put("read_at",iso(row.get("read_at")));items.add(v);}return Map.of("unread_count",unread,"notifications",page(items,total,page,20));
 });}
 public JsonNode read(Object a,long id,String key){com.autocare.platform.service.ServiceCatalog.validateId(id);var r=actor(a,false);return db.writes.execute(r.actor(),"PUT","/api/"+route(r)+"/notifications/"+id+"/read",key,db.mapper.createObjectNode(),()->{actor(a,true);db.one("SELECT n.id"+source(r)+" AND n.id=? FOR UPDATE",r.type(),r.id(),id);},()->{
  var n=db.one("SELECT read_at FROM notification WHERE id=? FOR UPDATE",id);boolean changed=n.get("read_at")==null;var readAt=changed?time(db.now()):n.get("read_at");if(changed)db.jdbc.update("UPDATE notification SET read_at=? WHERE id=?",readAt,id);var after=Map.<String,Object>of("notification_id",id,"read_at",iso(readAt));return new Change("NOTIFICATION_READ","notification",id,changed?Map.of():after,after,after,changed);
 });}
 private String route(Recipient r){return switch(r.type()){case "user"->"owner";case "merchant"->"merchant";default->"tech";};}
}
