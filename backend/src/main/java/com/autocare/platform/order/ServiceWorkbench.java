package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Mutually exclusive buckets from current persisted order and assignment facts. */
public class ServiceWorkbench {
 public static final List<String> BUCKETS=List.of("RECEIVE","CONFIRM","DISPUTE","ASSIGN","WAIT_ACCEPT","SERVICE","VERIFY","REVIEW_SOURCE");
 private final ServiceTaskScope scope;
 public ServiceWorkbench(ServiceTaskScope scope){this.scope=scope;}
 public Map<String,Object> read(Object actor,String bucket,int page){
  if(bucket!=null&&!BUCKETS.contains(bucket)||page<1||page>1000000)throw ReservationInput.bad();var db=scope.db;
  return db.reads.execute(tx->{scope.actor(actor);boolean tech=actor instanceof TechnicianActor;long shop=tech?((TechnicianActor)actor).merchantId():((MerchantActor)actor).merchantId();
   String valid="a.id IS NOT NULL AND a.is_deleted=0 AND a.merchant_id=o.merchant_id AND a.assigned_by IS NOT NULL AND o.assigned_at IS NOT NULL AND t.merchant_id=o.merchant_id AND t.role='TECHNICIAN' AND t.status='ACTIVE' AND t.is_deleted=0 AND s.merchant_id=o.merchant_id AND t.id<>s.id AND EXISTS(SELECT 1 FROM staff_wechat_identity w WHERE w.staff_account_id=t.id AND w.app_id=? AND w.status='ACTIVE' AND w.is_deleted=0 AND w.unbound_at IS NULL)";
   String confirmed="p.id IS NOT NULL AND p.is_deleted=0 AND p.merchant_id=o.merchant_id AND o.check_in_completed_at IS NOT NULL AND o.owner_confirmed_at IS NOT NULL AND p.owner_confirm IN (1,3) AND p.confirm_at IS NOT NULL";
   String classification="CASE WHEN o.status='DISPUTED' OR EXISTS(SELECT 1 FROM order_dispute d WHERE d.order_id=o.id AND d.status='OPEN' AND d.is_deleted=0) THEN 'DISPUTE' WHEN o.status='PAID' THEN 'RECEIVE' WHEN o.status='RECEIVED' AND NOT ("+confirmed+") THEN 'CONFIRM' WHEN o.status='RECEIVED' AND a.id IS NULL AND o.assigned_at IS NULL THEN 'ASSIGN' WHEN NOT ("+valid+") THEN 'REVIEW_SOURCE' WHEN o.status='RECEIVED' AND a.status='ASSIGNED' AND a.accepted_at IS NULL THEN 'WAIT_ACCEPT' WHEN a.status='ACCEPTED' AND a.accepted_at IS NOT NULL AND o.status='IN_SERVICE' THEN 'SERVICE' WHEN a.status='ACCEPTED' AND a.accepted_at IS NOT NULL AND o.status='PENDING_VERIFY' AND o.service_report_ready_at IS NOT NULL THEN 'VERIFY' ELSE 'REVIEW_SOURCE' END";
   var args=new ArrayList<Object>();args.add(scope.appId);args.add(shop);if(tech)args.add(((TechnicianActor)actor).staffId());
   String source="(SELECT o.id AS order_id,o.status AS order_status,o.project_snapshot,o.appointment_at,"+classification+" AS bucket FROM `order` o LEFT JOIN pickup_check p ON p.order_id=o.id LEFT JOIN technician_assignment a ON a.order_id=o.id LEFT JOIN staff_account t ON t.id=a.technician_id LEFT JOIN staff_account s ON s.id=a.assigned_by WHERE o.merchant_id=? AND o.is_deleted=0 AND o.status IN ('PAID','RECEIVED','DISPUTED','IN_SERVICE','PENDING_VERIFY')"+(tech?" AND a.technician_id=? AND a.merchant_id=o.merchant_id AND a.is_deleted=0":"")+") tasks";
   var counts=new LinkedHashMap<String,Object>();for(String b:BUCKETS)counts.put(b,0L);for(var row:db.jdbc.queryForList("SELECT bucket,COUNT(*) AS amount FROM "+source+" GROUP BY bucket",args.toArray()))counts.put(row.get("bucket").toString(),number(row,"amount"));
   long total=bucket==null?counts.values().stream().mapToLong(v->((Number)v).longValue()).sum():((Number)counts.get(bucket)).longValue();
   if(bucket!=null)args.add(bucket);args.add((page-1)*20L);
   var rows=db.jdbc.queryForList("SELECT * FROM "+source+(bucket==null?"":" WHERE bucket=?")+" ORDER BY appointment_at,order_id LIMIT 20 OFFSET ?",args.toArray());var items=new ArrayList<Map<String,Object>>();
   for(var row:rows){var r=new LinkedHashMap<String,Object>();for(String k:List.of("order_id","order_status","bucket"))r.put(k,row.get(k));var p=MerchantOrders.snapshot(db.parse(row.get("project_snapshot")),"project_snapshot");r.put("project_name",p==null?null:p.get("project_name"));r.put("starts_at",iso(row.get("appointment_at")));items.add(r);}
   return Map.of("counts",counts,"tasks",page(items,total,page,20));
  });
 }
}
