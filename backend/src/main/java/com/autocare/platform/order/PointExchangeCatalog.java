package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Operator configuration only. Spending and coupon issuance use a separate transaction service. */
public class PointExchangeCatalog {
 public static final String PATH="/api/admin/point/exchange-catalog";
 private final ReservationStore db; private final CouponInventory coupons; private final OperatorIdentity operators;
 public PointExchangeCatalog(ReservationStore db,CouponInventory coupons,OperatorIdentity operators){this.db=db;this.coupons=coupons;this.operators=operators;}
 static JsonNode input(String raw,boolean update){
  var b=CouponInput.parse(raw);var fields=new ArrayList<>(List.of("coupon_id","publication_revision","pool_revision","points_cost","per_owner_limit","source_note","valid_from","valid_to"));if(update)fields.add("expected_revision");CouponInput.exact(b,fields.toArray(String[]::new));
  for(String k:List.of("coupon_id","publication_revision","pool_revision"))CouponInput.id(b.get(k));
  for(String k:List.of("points_cost","per_owner_limit"))if(!b.path(k).isIntegralNumber()||!b.path(k).canConvertToInt()||b.path(k).intValue()<1||b.path(k).intValue()>1000000)throw ReservationInput.bad();
  DiagnosisServicePolicies.text(b.get("source_note"),2,256);if(!CouponInput.time(b.get("valid_to")).isAfter(CouponInput.time(b.get("valid_from"))))throw ReservationInput.bad();if(update)CouponInput.id(b.get("expected_revision"));return b;
 }
 private static org.springframework.web.server.ResponseStatusException conflict(){return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"兑换目录、券来源或有效期已变化，请重新读取");}
 private void authorize(OperatorActor a,boolean lock){operators.authorize(a,OperatorIdentity.Permission.POINTS,lock);}
 private void pageNumber(int p){if(p<1||p>1000000)throw ReservationInput.bad();}
 Map<String,Object> row(long id,boolean lock){return db.one("SELECT * FROM point_exchange_catalog WHERE id=?"+(lock?" FOR UPDATE":""),id);}
 Map<String,Object> view(Map<String,Object> r){var n=new LinkedHashMap<String,Object>();for(String k:List.of("id","coupon_id","publication_revision","pool_revision","points_cost","per_owner_limit","source_note","revision","status","rule_version"))n.put(k,r.get(k));n.put("terms",db.parse(r.get("terms_snapshot")));n.put("limit_scope","OWNER_COUPON_LIFETIME");for(String k:List.of("valid_from","valid_to","created_at","published_at"))n.put(k,iso(r.get(k)));return n;}
 private void sourceMatches(Map<String,Object> s,long publication,long pool,Instant from,Instant to,Instant now){
  if(!"PUBLISHED".equals(s.get("status"))||s.get("publication_revision")==null||number(s,"publication_revision")!=publication||number(s,"pool_selected")!=1||number(s,"pool_revision")!=pool||!instant(s.get("valid_to")).isAfter(now)||!to.isAfter(now)||from.isBefore(instant(s.get("valid_from")))||to.isAfter(instant(s.get("valid_to"))))throw conflict();
 }
 private Map<String,Object> source(JsonNode b){var s=coupons.source(b.path("coupon_id").asLong(),true);sourceMatches(s,b.path("publication_revision").asLong(),b.path("pool_revision").asLong(),CouponInput.time(b.get("valid_from")),CouponInput.time(b.get("valid_to")),db.now());return s;}
 public Map<String,Object> list(OperatorActor a,int p){pageNumber(p);return db.reads.execute(tx->{authorize(a,false);long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM point_exchange_catalog",Long.class);return page(db.jdbc.queryForList("SELECT * FROM point_exchange_catalog ORDER BY id DESC LIMIT 20 OFFSET ?",(p-1)*20L).stream().map(this::view).toList(),count,p,20);});}
 public Map<String,Object> sources(OperatorActor a,int p){pageNumber(p);return db.reads.execute(tx->{authorize(a,false);String filter=coupons.active().replace(" AND c.valid_from<=?","").replace(" AND c.issued_quantity<c.total_quantity","")+" AND s.pool_selected=1";return coupons.list(filter,List.of(time(db.now())),p,20);});}
 private String hash(JsonNode b){var sorted=new TreeMap<String,JsonNode>();b.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));return AuthTokens.sha256(db.json(sorted));}
 public JsonNode create(OperatorActor a,String key,JsonNode b){String normalized=WriteIntegrityService.normalizeKey(key),hash=hash(b);return db.writes.execute(new Actor("operator_account",a.id()),"POST",PATH,key,b,()->authorize(a,true),()->{
  var prior=db.jdbc.queryForList("SELECT id,submission_hash,creation_result FROM point_exchange_catalog WHERE created_by=? AND submission_key=?",a.id(),normalized);
  if(!prior.isEmpty()){var r=prior.get(0);if(!hash.equals(r.get("submission_hash")))throw ReservationInput.bad();var saved=db.parse(r.get("creation_result"));if(!saved.isObject())throw new IllegalStateException("Exchange catalog creation receipt missing");Map<String,Object> result=db.mapper.convertValue(saved,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return new Change("POINT_CATALOG_CREATE","point_exchange_catalog",number(r,"id"),Map.of(),Map.of(),result,false);}
  var s=source(b);long id=db.insert("INSERT INTO point_exchange_catalog(coupon_id,publication_revision,pool_revision,terms_snapshot,points_cost,per_owner_limit,source_note,valid_from,valid_to,created_by,submission_key,submission_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",b.path("coupon_id").asLong(),b.path("publication_revision").asLong(),b.path("pool_revision").asLong(),s.get("published_terms").toString(),b.path("points_cost").asInt(),b.path("per_owner_limit").asInt(),b.path("source_note").asText(),time(CouponInput.time(b.get("valid_from"))),time(CouponInput.time(b.get("valid_to"))),a.id(),normalized,hash,time(db.now()));var after=view(row(id,true));db.jdbc.update("UPDATE point_exchange_catalog SET creation_result=? WHERE id=?",db.json(after),id);return new Change("POINT_CATALOG_CREATE","point_exchange_catalog",id,Map.of(),after,after);
 });}
 public JsonNode update(OperatorActor a,long id,String key,JsonNode b){com.autocare.platform.service.ServiceCatalog.validateId(id);return db.writes.execute(new Actor("operator_account",a.id()),"PUT",PATH+"/"+id,key,b,()->authorize(a,true),()->{
  var s=source(b);var r=row(id,true);if(!"DRAFT".equals(r.get("status"))||number(r,"revision")!=b.path("expected_revision").asLong())throw conflict();sourceMatches(s,b.path("publication_revision").asLong(),b.path("pool_revision").asLong(),CouponInput.time(b.get("valid_from")),CouponInput.time(b.get("valid_to")),db.now());var before=view(r);
  db.jdbc.update("UPDATE point_exchange_catalog SET coupon_id=?,publication_revision=?,pool_revision=?,terms_snapshot=?,points_cost=?,per_owner_limit=?,source_note=?,valid_from=?,valid_to=?,revision=revision+1 WHERE id=?",b.path("coupon_id").asLong(),b.path("publication_revision").asLong(),b.path("pool_revision").asLong(),s.get("published_terms").toString(),b.path("points_cost").asInt(),b.path("per_owner_limit").asInt(),b.path("source_note").asText(),time(CouponInput.time(b.get("valid_from"))),time(CouponInput.time(b.get("valid_to"))),id);var after=view(row(id,true));return new Change("POINT_CATALOG_UPDATE","point_exchange_catalog",id,before,after,after);
 });}
 public JsonNode status(OperatorActor a,long id,String key,JsonNode b){com.autocare.platform.service.ServiceCatalog.validateId(id);return db.writes.execute(new Actor("operator_account",a.id()),"PUT",PATH+"/"+id+"/status",key,b,()->authorize(a,true),()->{
  String target=b.path("status").asText();var first=row(id,false);Map<String,Object> s=null;if(target.equals("PUBLISHED"))s=coupons.source(number(first,"coupon_id"),true);var r=row(id,true);
  if(number(r,"revision")!=b.path("expected_revision").asLong()||!(target.equals("PUBLISHED")?"DRAFT".equals(r.get("status")):target.equals("STOPPED")&&"PUBLISHED".equals(r.get("status"))))throw conflict();
  if(s!=null){if(number(s,"id")!=number(r,"coupon_id"))throw conflict();sourceMatches(s,number(r,"publication_revision"),number(r,"pool_revision"),instant(r.get("valid_from")),instant(r.get("valid_to")),db.now());}
  var before=view(r);try{db.jdbc.update("UPDATE point_exchange_catalog SET status=?,revision=revision+1,published_at=? WHERE id=?",target,target.equals("PUBLISHED")?time(db.now()):r.get("published_at"),id);}catch(org.springframework.dao.DuplicateKeyException e){throw conflict();}var after=view(row(id,true));return new Change("POINT_CATALOG_STATUS","point_exchange_catalog",id,before,after,after);
 });}
}
