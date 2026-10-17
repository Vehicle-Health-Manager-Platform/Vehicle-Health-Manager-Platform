package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Store-local service relationships. No customer identity or archive data is projected. */
public class CustomerRelations {
 private final ServiceTaskScope scope;
 private final ReservationStore db;
 public CustomerRelations(ServiceTaskScope scope){this.scope=scope;this.db=scope.db;}
 private void authorize(MerchantActor actor){if(!actor.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅店长可读取本店服务关系");db.merchant(actor,false);}
 private void pageNumber(int page){if(page<1||page>1000000)throw ReservationInput.bad();}
 private static final String SOURCE=" FROM `order` o JOIN `user` u ON u.id=o.user_id AND u.status=1 AND u.is_deleted=0 JOIN vehicle v ON v.id=o.vehicle_id AND v.user_id=o.user_id AND v.is_deleted=0 WHERE o.merchant_id=? AND o.is_deleted=0 AND o.status IN ('PAID','RECEIVED','IN_SERVICE','PENDING_VERIFY','DISPUTED','COMPLETED') AND o.created_at<=?";
 // Actual recorded arrival, backed by the check-in transition; appointments are never service times.
 private static final String ARRIVAL="(SELECT MAX(p.arrived_at) FROM pickup_check p WHERE p.order_id=o.id AND p.merchant_id=o.merchant_id AND p.is_deleted=0 AND p.arrived_at>=o.created_at AND p.arrived_at<=o.check_in_completed_at AND EXISTS(SELECT 1 FROM order_status_transition t WHERE t.order_id=o.id AND t.merchant_id=o.merchant_id AND t.actor_type='staff_account' AND t.actor_id=p.staff_id AND t.action='ORDER_CHECK_IN' AND t.from_status='PAID' AND t.to_status='RECEIVED' AND t.occurred_at=o.check_in_completed_at) AND o.check_in_completed_at<=?)";
 private String identity(long user){var rows=db.jdbc.queryForList("SELECT identity_source,app_id,rule_version FROM invite_registration WHERE user_id=?",user);if(rows.size()!=1)return "UNKNOWN";var r=rows.get(0);if(!InvitationRegistration.RULE.equals(r.get("rule_version"))||Objects.toString(r.get("app_id"),"").isBlank())return "UNKNOWN";return Set.of("VERIFIED_WECHAT","TEST").contains(Objects.toString(r.get("identity_source"),""))?r.get("identity_source").toString():"UNKNOWN";}
 private List<Map<String,Object>> summaryRows(long shop,Instant now,Long user,int page){
  var args=new ArrayList<Object>(List.of(time(now),shop,time(now)));if(user!=null)args.add(user);args.add((page-1)*20L);
  return db.jdbc.queryForList("SELECT o.user_id,MIN(o.id) AS anchor,COUNT(*) AS order_count,MAX(o.created_at) AS last_order_at,MAX("+ARRIVAL+") AS last_service_at"+SOURCE+(user==null?"":" AND o.user_id=?")+" GROUP BY o.user_id ORDER BY last_order_at DESC,anchor DESC LIMIT 20 OFFSET ?",args.toArray());
 }
 private Map<String,Object> summary(long shop,Instant now,Map<String,Object> row){
  long user=number(row,"user_id");var latest=db.one("SELECT o.*"+SOURCE+" AND o.user_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 1",shop,time(now),user);
  var out=new LinkedHashMap<String,Object>();out.put("relationship_order_id",row.get("anchor"));out.put("order_count",row.get("order_count"));out.put("last_order_at",iso(row.get("last_order_at")));out.put("last_service_at",iso(row.get("last_service_at")));out.put("identity_source",identity(user));out.put("latest_vehicle",scope.basic(latest));return out;
 }
 public Map<String,Object> list(MerchantActor actor,int page){pageNumber(page);return db.reads.execute(tx->{authorize(actor);Instant now=db.now();long total=db.jdbc.queryForObject("SELECT COUNT(DISTINCT o.user_id)"+SOURCE,Long.class,actor.merchantId(),time(now));var rows=summaryRows(actor.merchantId(),now,null,page);return Map.of("observed_at",now.toString(),"relationships",ReservationStore.page(rows.stream().map(r->summary(actor.merchantId(),now,r)).toList(),total,page,20));});}
 public Map<String,Object> detail(MerchantActor actor,long anchor,int page){
  com.autocare.platform.service.ServiceCatalog.validateId(anchor);pageNumber(page);
  return db.reads.execute(tx->{authorize(actor);Instant now=db.now();var order=db.one("SELECT o.*"+SOURCE+" AND o.id=?",actor.merchantId(),time(now),anchor);long user=number(order,"user_id");var row=summaryRows(actor.merchantId(),now,user,1).get(0);
   // Only the current canonical anchor names a relationship; stale anchors fail closed.
   if(number(row,"anchor")!=anchor)throw missing();
   var rows=db.jdbc.queryForList("SELECT o.*,"+ARRIVAL+" AS service_at"+SOURCE+" AND o.user_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 20 OFFSET ?",time(now),actor.merchantId(),time(now),user,(page-1)*20L);var items=new ArrayList<Map<String,Object>>();
   for(var r:rows){var item=new LinkedHashMap<String,Object>();item.put("order_id",r.get("id"));item.put("order_status",r.get("status"));item.put("created_at",iso(r.get("created_at")));item.put("service_at",iso(r.get("service_at")));var project=MerchantOrders.snapshot(db.parse(r.get("project_snapshot")),"project_snapshot");item.put("project_name",project==null?null:project.get("project_name"));item.put("vehicle",scope.basic(r));item.put("service_context_available",scope.serviceActive(r));items.add(item);}
   return Map.of("observed_at",now.toString(),"relationship",summary(actor.merchantId(),now,row),"orders",ReservationStore.page(items,number(row,"order_count"),page,20));
  });
 }
}
