package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.autocare.platform.order.ReservationStore.*;

/** Captured diagnosis recipients and purposes; notification metadata never grants body access. */
public class DiagnosisNotifications {
 public static final List<String> KINDS=List.of("DIAGNOSIS_CREATED","DIAGNOSIS_MESSAGE","DIAGNOSIS_ASSIGNED","DIAGNOSIS_REPLIED","DIAGNOSIS_CLOSED");
 private final ReservationStore db;private final DiagnosisServicePolicies policies;
 public DiagnosisNotifications(ReservationStore db,DiagnosisServicePolicies policies){this.db=db;this.policies=policies;}
 static void enqueue(ReservationStore db,long caseId,long revision,String kind){
  if(!KINDS.contains("DIAGNOSIS_"+kind)||!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Diagnosis notice requires its business transaction");
  var c=db.one("SELECT owner_id,assigned_operator_id FROM diagnosis_case WHERE id=? AND revision=? FOR UPDATE",caseId,revision);
  var source=db.one("SELECT id,occurred_at FROM diagnosis_case_event WHERE case_id=? AND case_revision=? AND kind=? FOR UPDATE",caseId,revision,kind);
  if(!db.jdbc.queryForList("SELECT id FROM diagnosis_notification_outbox WHERE source_event_id=? FOR UPDATE",source.get("id")).isEmpty())return;
  // The caller holds diagnosis_control. Current reads also cover independent permission changes.
  var operators=db.jdbc.queryForList("SELECT id,can_diagnose,can_diagnose_manage FROM operator_account WHERE status='ACTIVE' AND (can_diagnose_manage=1 OR id=?) ORDER BY id FOR UPDATE",c.get("assigned_operator_id"));
  long outbox=db.insert("INSERT INTO diagnosis_notification_outbox(event_id,source_event_id,case_id,case_revision,kind,occurred_at,next_attempt_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),source.get("id"),caseId,revision,"DIAGNOSIS_"+kind,source.get("occurred_at"),time(db.now()));
  target(db,outbox,"user",number(c,"owner_id"),true,false);
  for(var op:operators){boolean detail=Objects.equals(c.get("assigned_operator_id"),op.get("id"))&&number(op,"can_diagnose")==1,queue=number(op,"can_diagnose_manage")==1;if(detail||queue)target(db,outbox,"operator_account",number(op,"id"),detail,queue);}
 }
 private static void target(ReservationStore db,long outbox,String type,long id,boolean detail,boolean queue){db.jdbc.update("INSERT INTO diagnosis_notification_target(outbox_id,recipient_type,recipient_id,can_detail,can_queue) VALUES(?,?,?,?,?)",outbox,type,id,detail?1:0,queue?1:0);}
 @Scheduled(fixedDelayString="${SERVICE_NOTIFICATION_DELAY_MS:5000}") public void recover(){
  var ids=db.jdbc.queryForList("SELECT id FROM diagnosis_notification_outbox WHERE status='PENDING' AND next_attempt_at<=? ORDER BY id LIMIT 20",Long.class,time(db.now()));
  for(long id:ids)try{deliver(id);}catch(RuntimeException failure){try{db.transactions.executeWithoutResult(tx->db.jdbc.update("UPDATE diagnosis_notification_outbox SET next_attempt_at=DATE_ADD(?,INTERVAL LEAST(300,5*POW(2,LEAST(attempts,6))) SECOND),attempts=LEAST(attempts+1,1000000),last_error='DELIVERY_UNAVAILABLE' WHERE id=? AND status='PENDING'",time(db.now()),id));}catch(RuntimeException ignored){/* Durable PENDING source remains retryable. */}}
 }
 boolean deliver(long id){return Boolean.TRUE.equals(db.transactions.execute(tx->{
  var rows=db.jdbc.queryForList("SELECT * FROM diagnosis_notification_outbox WHERE id=? AND status='PENDING' AND next_attempt_at<=? FOR UPDATE SKIP LOCKED",id,time(db.now()));if(rows.isEmpty())return false;var e=rows.get(0);
  for(var t:db.jdbc.queryForList("SELECT recipient_type,recipient_id FROM diagnosis_notification_target WHERE outbox_id=? ORDER BY recipient_type,recipient_id",id))db.jdbc.update("INSERT INTO notification(event_id,recipient_type,recipient_id,template_key,payload,created_at) VALUES(?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE id=id",e.get("event_id"),t.get("recipient_type"),t.get("recipient_id"),e.get("kind"),e.get("occurred_at"));
  db.jdbc.update("UPDATE diagnosis_notification_outbox SET status='DONE',completed_at=?,last_error=NULL WHERE id=?",time(db.now()),id);return true;
 }));}
 private record Recipient(String type,long id,Actor actor){}
 private Recipient actor(Object a,boolean lock){
  if(a instanceof VehicleOwner u){db.owner(u,lock);return new Recipient("user",u.id(),new Actor("user",u.id()));}
  if(a instanceof OperatorActor op){policies.operators.authorize(op,OperatorIdentity.Permission.NONE,lock);var flags=db.one("SELECT can_diagnose,can_diagnose_manage FROM operator_account WHERE id=?"+(lock?" FOR UPDATE":""),op.id());if(number(flags,"can_diagnose")!=1&&number(flags,"can_diagnose_manage")!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"当前账号没有诊断消息权限");return new Recipient("operator_account",op.id(),new Actor("operator_account",op.id()));}
  throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权读取诊断消息");
 }
 private String detail(Recipient r){return r.type().equals("user")?"1=1":"t.can_detail=1 AND c.assigned_operator_id=n.recipient_id AND a.can_diagnose=1";}
 private String source(Recipient r){return " FROM notification n JOIN diagnosis_notification_outbox e ON e.event_id=n.event_id AND e.status='DONE' AND e.kind=n.template_key JOIN diagnosis_case_event s ON s.id=e.source_event_id AND s.case_id=e.case_id AND s.case_revision=e.case_revision AND CONCAT('DIAGNOSIS_',s.kind)=e.kind JOIN diagnosis_notification_target t ON t.outbox_id=e.id AND t.recipient_type=n.recipient_type AND t.recipient_id=n.recipient_id JOIN diagnosis_case c ON c.id=e.case_id"+(r.type().equals("user")?"":" JOIN operator_account a ON a.id=n.recipient_id AND a.status='ACTIVE'")+" WHERE n.recipient_type=? AND n.recipient_id=? AND n.is_deleted=0 AND "+(r.type().equals("user")?"c.owner_id=n.recipient_id AND t.can_detail=1":"(("+detail(r)+") OR (t.can_queue=1 AND a.can_diagnose_manage=1))");}
 public Map<String,Object> list(Object a,int p){if(p<1||p>1000000)throw ReservationInput.bad();return db.reads.execute(tx->{var r=actor(a,false);String from=source(r);long total=db.jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,r.type(),r.id()),unread=db.jdbc.queryForObject("SELECT COUNT(*)"+from+" AND n.read_at IS NULL",Long.class,r.type(),r.id());
  var items=new ArrayList<Map<String,Object>>();for(var row:db.jdbc.queryForList("SELECT n.id,n.read_at,e.kind,e.case_id,e.occurred_at,c.status AS case_status,CASE WHEN "+detail(r)+" THEN 'CASE' ELSE 'QUEUE' END AS target"+from+" ORDER BY e.occurred_at DESC,n.id DESC LIMIT 20 OFFSET ?",r.type(),r.id(),(p-1)*20L)){var item=new LinkedHashMap<String,Object>();for(String k:List.of("id","kind","case_id","case_status","target"))item.put(k.equals("id")?"notification_id":k,row.get(k));item.put("occurred_at",iso(row.get("occurred_at")));item.put("read_at",iso(row.get("read_at")));items.add(item);}return Map.of("unread_count",unread,"notifications",page(items,total,p,20));
 });}
 public JsonNode read(Object a,long id,String key){com.autocare.platform.service.ServiceCatalog.validateId(id);var r=actor(a,false);return db.writes.execute(r.actor(),"PUT","/api/"+(r.type().equals("user")?"owner":"admin")+"/diagnosis/notifications/"+id+"/read",key,db.mapper.createObjectNode(),()->{
  if(a instanceof VehicleOwner u)db.owner(u,true);policies.guard();actor(a,true);db.one("SELECT n.id"+source(r)+" AND n.id=? FOR UPDATE",r.type(),r.id(),id);
 },()->{var n=db.one("SELECT read_at FROM notification WHERE id=? FOR UPDATE",id);boolean changed=n.get("read_at")==null;var at=changed?time(db.now()):n.get("read_at");if(changed)db.jdbc.update("UPDATE notification SET read_at=? WHERE id=?",at,id);var result=Map.<String,Object>of("notification_id",id,"read_at",iso(at));return new Change("DIAGNOSIS_NOTIFICATION_READ","notification",id,changed?Map.of():result,result,result,changed);});}
}
