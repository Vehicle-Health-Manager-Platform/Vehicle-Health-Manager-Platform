package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Read-only comparison of exact quotation evidence and the explicitly published range. */
public class PriceComparisons {
 private final ReservationStore db;
 private final PricePolicies policies;
 private final OperatorIdentity operators;
 private static final String CANDIDATES="(SELECT q.id AS quote_id"+PricePolicies.FROM+" UNION SELECT quote_id FROM price_monitor_policy WHERE status='PUBLISHED') candidates";
 public PriceComparisons(ReservationStore db,PricePolicies policies,OperatorIdentity operators){this.db=db;this.policies=policies;this.operators=operators;}
 private void authorize(OperatorActor a){operators.authorize(a,OperatorIdentity.Permission.PRICE_MONITOR,false);}
 private JsonNode tree(Object value){return db.parse(db.json(value));}

 private Map<String,Object> validPolicy(Map<String,Object> row,Instant now){
  if(row==null)return null;
  try{
   var view=policies.view(row);var node=tree(view);var body=db.mapper.createObjectNode();for(String k:PricePolicyInput.FIELDS)body.set(k,node.get(k));PricePolicyInput.body(body.toString(),false);
   if(!PricePolicies.RULE.equals(row.get("rule_version"))||number(row,"id")<=0||number(row,"revision")<=0||!Set.of("DRAFT","PUBLISHED","STOPPED").contains(row.get("status")))return null;
   var source=node.get("source_snapshot");CouponInput.exact(source,"quote_id","quote_version_id","quote_version","price","merchant","project");
   for(String k:List.of("quote_id","quote_version_id","quote_version"))CouponInput.id(source.get(k));if(source.path("quote_id").asLong()!=number(row,"quote_id"))return null;
   String price=source.path("price").asText();if(!source.path("price").isTextual()||!price.matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")||new BigDecimal(price).signum()<=0)return null;
   var merchant=source.get("merchant");CouponInput.exact(merchant,"id","name","region_code");CouponInput.id(merchant.get("id"));DiagnosisServicePolicies.text(merchant.get("name"),1,128);if(!merchant.path("region_code").isTextual())return null;
   var project=source.get("project");CouponInput.exact(project,"id","revision","project_name","service_content","quality_standard","source_note");CouponInput.id(project.get("id"));CouponInput.id(project.get("revision"));for(String k:List.of("project_name","service_content","quality_standard","source_note"))if(!project.path(k).isTextual())return null;if(project.path("service_content").asText().isBlank()||project.path("source_note").asText().isBlank())return null;
   if(!instant(row.get("created_at")).isBefore(instant(row.get("valid_from")))||instant(row.get("created_at")).isAfter(now)||row.get("published_at")!=null&&instant(row.get("published_at")).isAfter(now)||row.get("stopped_at")!=null&&instant(row.get("stopped_at")).isAfter(now))return null;
   if("DRAFT".equals(row.get("status"))){if(row.get("published_at")!=null||row.get("stopped_at")!=null)return null;}
   else if(row.get("published_at")==null||instant(row.get("published_at")).isBefore(instant(row.get("created_at")))||!instant(row.get("published_at")).isBefore(instant(row.get("valid_from"))))return null;
   if("STOPPED".equals(row.get("status"))){if(row.get("stopped_at")==null||instant(row.get("stopped_at")).isBefore(instant(row.get("published_at"))))return null;}
   else if(row.get("stopped_at")!=null)return null;
   return view;
  }catch(org.springframework.dao.DataAccessException e){throw e;}catch(RuntimeException invalid){return null;}
 }
 private boolean publicationVerified(Map<String,Object> row,Map<String,Object> policy){
  var audits=db.jdbc.queryForList("SELECT id,after_state FROM audit_log WHERE is_deleted=0 AND actor_type='operator_account' AND action='PRICE_POLICY_STATUS' AND resource_type='price_monitor_policy' AND resource_id=? AND JSON_UNQUOTE(JSON_EXTRACT(after_state,'$.status'))='PUBLISHED' AND JSON_EXTRACT(after_state,'$.revision')=?",row.get("id"),row.get("revision"));
  // Include invalidated later entries when locating the last event, so evidence cannot roll back.
  var latest=db.jdbc.queryForList("SELECT id FROM audit_log WHERE actor_type='operator_account' AND action='PRICE_POLICY_STATUS' AND resource_type='price_monitor_policy' AND resource_id=? ORDER BY id DESC LIMIT 1",row.get("id"));
  if(audits.size()!=1||latest.size()!=1||!Objects.equals(audits.get(0).get("id"),latest.get(0).get("id")))return false;
  // Capability changed after c1. It is not part of the frozen business evidence.
  var expected=tree(policy);var proof=db.parse(audits.get(0).get("after_state"));if(!proof.isObject())return false;
  ((com.fasterxml.jackson.databind.node.ObjectNode)expected).remove("comparison_available");((com.fasterxml.jackson.databind.node.ObjectNode)proof).remove("comparison_available");return expected.equals(proof);
 }
 private Map<String,Object> compare(long id,Instant now){
  Map<String,Object> current=null;try{current=policies.source(id,false,now);}catch(ResponseStatusException e){if(e.getStatusCode().value()!=404)throw e;}
  var rows=db.jdbc.queryForList("SELECT * FROM price_monitor_policy WHERE quote_id=? ORDER BY (status='PUBLISHED') DESC,id DESC LIMIT 1",id);var row=rows.isEmpty()?null:rows.get(0);var policy=validPolicy(row,now);
  String reason=current==null?"SOURCE_UNAVAILABLE":!"READY".equals(current.get("state"))?"SOURCE_UNKNOWN":row==null?"POLICY_MISSING":policy==null?"POLICY_SOURCE_UNKNOWN":"DRAFT".equals(row.get("status"))?"POLICY_NOT_PUBLISHED":"STOPPED".equals(row.get("status"))?"POLICY_STOPPED":!publicationVerified(row,policy)?"POLICY_SOURCE_UNKNOWN":instant(row.get("valid_from")).isAfter(now)?"NOT_STARTED":!instant(row.get("valid_to")).isAfter(now)?"EXPIRED":!Objects.equals(current.get("source_hash"),row.get("source_hash"))||!tree(current.get("source")).equals(tree(policy.get("source_snapshot")))?"SOURCE_CHANGED":"VERIFIED";
  String state="UNKNOWN";boolean review=false;
  if(reason.equals("VERIFIED")){var price=new BigDecimal(((Map<?,?>)current.get("source")).get("price").toString());int low=price.compareTo((BigDecimal)row.get("lower_price")),high=price.compareTo((BigDecimal)row.get("upper_price"));state=low<0?"BELOW_RANGE":high>0?"ABOVE_RANGE":"WITHIN_RANGE";review=low<0||high>0;}
  var out=new LinkedHashMap<String,Object>();out.put("quote_id",id);out.put("state",state);out.put("reason",reason);out.put("review_required",review);out.put("current",current);out.put("policy",policy);return out;
 }
 public Map<String,Object> list(OperatorActor a,int page){
  if(page<1||page>1000000)throw ReservationInput.bad();return db.reads.execute(tx->{authorize(a);Instant now=db.now();long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM "+CANDIDATES,Long.class);var rows=db.jdbc.queryForList("SELECT quote_id FROM "+CANDIDATES+" ORDER BY quote_id DESC LIMIT 20 OFFSET ?",(page-1)*20L);return Map.of("observed_at",now.toString(),"comparisons",ReservationStore.page(rows.stream().map(r->compare(number(r,"quote_id"),now)).toList(),total,page,20));});
 }
 public Map<String,Object> detail(OperatorActor a,long id){
  com.autocare.platform.service.ServiceCatalog.validateId(id);return db.reads.execute(tx->{authorize(a);if(db.jdbc.queryForObject("SELECT COUNT(*) FROM "+CANDIDATES+" WHERE quote_id=?",Long.class,id)!=1)throw missing();Instant now=db.now();return Map.of("observed_at",now.toString(),"comparison",compare(id,now));});
 }
}
