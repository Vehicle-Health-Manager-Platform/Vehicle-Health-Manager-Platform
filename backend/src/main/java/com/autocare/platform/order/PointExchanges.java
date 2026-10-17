package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** All coupon and point writes use the caller's one committed transaction. No upstream IO. */
public class PointExchanges {
 public static final String RULE="R8A_EXCHANGE_V1",PATH="/api/point/exchange";
 private final ReservationStore db;private final PointExchangeCatalog catalog;private final CouponInventory coupons;private final PointLedger ledger;private final boolean enabled;
 public PointExchanges(ReservationStore db,PointExchangeCatalog catalog,CouponInventory coupons,PointLedger ledger,boolean enabled){this.db=db;this.catalog=catalog;this.coupons=coupons;this.ledger=ledger;this.enabled=enabled;}
 static JsonNode input(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"catalog_id","catalog_revision");CouponInput.id(b.get("catalog_id"));CouponInput.id(b.get("catalog_revision"));return b;}
 private static org.springframework.web.server.ResponseStatusException conflict(){return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"兑换资格、目录、积分、次数或库存已变化，请重新核对");}
 private Map<String,Object> map(Object value){var n=db.parse(value);if(!n.isObject())throw new IllegalStateException("Point exchange receipt missing");return db.mapper.convertValue(n,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
 private String hash(JsonNode b){var sorted=new TreeMap<String,JsonNode>();b.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));return AuthTokens.sha256(db.json(sorted));}
 public JsonNode exchange(VehicleOwner a,String key,JsonNode b){String canonical=WriteIntegrityService.normalizeKey(key),hash=hash(b);return db.writes.execute(new Actor("user",a.id()),"POST",PATH,key,b,()->db.owner(a,true),()->{
  // The owner row already serializes this owner's submissions. Locking an absent
  // receipt would hold a cross-owner index gap while waiting for shared coupon locks.
  var previous=db.jdbc.queryForList("SELECT id,submission_hash,creation_result FROM point_exchange_record WHERE user_id=? AND submission_key=?",a.id(),canonical);
  if(!previous.isEmpty()){var r=previous.get(0);if(!hash.equals(r.get("submission_hash")))throw ReservationInput.bad();return new Change("POINT_EXCHANGE","point_exchange_record",number(r,"id"),Map.of(),Map.of(),map(r.get("creation_result")),false);}
  if(!enabled||!ledger.verifiedIdentity(a,true))throw conflict();
  var first=catalog.row(b.path("catalog_id").asLong(),false);
  // Only an already published snapshot can name immutable coupon/project terms.
  // Do not accept a guessed draft revision that becomes published during a lock wait.
  if(!"PUBLISHED".equals(first.get("status"))||number(first,"revision")!=b.path("catalog_revision").asLong())throw conflict();
  var source=coupons.source(number(first,"coupon_id"),true);var policy=catalog.row(b.path("catalog_id").asLong(),true);
  // Recheck the frozen source and revision after all source locks; stop may race the read.
  if(number(source,"id")!=number(policy,"coupon_id")||!"PUBLISHED".equals(policy.get("status"))||number(policy,"revision")!=b.path("catalog_revision").asLong()||!RULE.equals(policy.get("rule_version")))throw conflict();
  var accounts=db.jdbc.queryForList("SELECT balance,revision FROM point_verified_account WHERE user_id=? FOR UPDATE",a.id());if(accounts.size()!=1)throw conflict();var account=accounts.get(0);
  if(!ledger.verifiedIdentity(a,true))throw conflict();var at=db.now();
  if(instant(policy.get("valid_from")).isAfter(at)||!instant(policy.get("valid_to")).isAfter(at)||!"PUBLISHED".equals(source.get("status"))||number(source,"pool_selected")!=1||source.get("publication_revision")==null||number(source,"publication_revision")!=number(policy,"publication_revision")||number(source,"pool_revision")!=number(policy,"pool_revision")||instant(source.get("valid_from")).isAfter(at)||!instant(source.get("valid_to")).isAfter(at)||!db.parse(source.get("published_terms")).equals(db.parse(policy.get("terms_snapshot"))))throw conflict();
  long spent=number(policy,"points_cost"),balance=number(account,"balance"),issued=number(source,"issued_quantity"),total=number(source,"total_quantity");
  long used=db.jdbc.queryForObject("SELECT COUNT(*) FROM point_exchange_record WHERE user_id=? AND coupon_id=?",Long.class,a.id(),policy.get("coupon_id"));
  if(spent<1||spent>1000000||balance<spent||issued>=total||used>=number(policy,"per_owner_limit"))throw conflict();long next=balance-spent;
  long uc=db.insert("INSERT INTO user_coupon(user_id,coupon_id,status) VALUES(?,?,'AVAILABLE')",a.id(),policy.get("coupon_id"));
  var snapshot=catalog.view(policy);long id=db.insert("INSERT INTO point_exchange_record(user_id,catalog_id,catalog_revision,coupon_id,publication_revision,pool_revision,points_cost,catalog_snapshot,identity_session,submission_key,submission_hash,user_coupon_id,exchanged_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",a.id(),policy.get("id"),policy.get("revision"),policy.get("coupon_id"),policy.get("publication_revision"),policy.get("pool_revision"),spent,db.json(snapshot),a.session(),canonical,hash,uc,time(at));
  db.jdbc.update("INSERT INTO coupon_issue(user_coupon_id,coupon_id,user_id,publication_revision,issued_at,origin,points_exchange_id) VALUES(?,?,?,?,?,'POINTS',?)",uc,policy.get("coupon_id"),a.id(),policy.get("publication_revision"),time(at),id);
  db.jdbc.update("INSERT INTO point_verified_entry(user_id,exchange_id,delta,balance_after,created_at) VALUES(?,?,?,?,?)",a.id(),id,-spent,next,time(at));
  db.jdbc.update("UPDATE point_verified_account SET balance=?,revision=revision+1,updated_at=? WHERE user_id=?",next,time(at),a.id());
  db.jdbc.update("UPDATE coupon SET issued_quantity=issued_quantity+1 WHERE id=?",policy.get("coupon_id"));
  db.jdbc.update("INSERT INTO coupon_stock_event(coupon_id,user_coupon_id,quantity_delta,stock_after,occurred_at) VALUES(?,?,-1,?,?)",policy.get("coupon_id"),uc,total-issued-1,time(at));
  var result=new LinkedHashMap<String,Object>();result.put("exchange_id",id);result.put("user_coupon_id",uc);result.put("catalog_id",policy.get("id"));result.put("catalog_revision",policy.get("revision"));result.put("coupon_id",policy.get("coupon_id"));result.put("publication_revision",policy.get("publication_revision"));result.put("points_spent",spent);result.put("balance_after",next);result.put("exchanged_at",at.toString());result.put("status","SUCCESS");result.put("rule_version",RULE);result.put("catalog",snapshot);
  db.jdbc.update("UPDATE point_exchange_record SET creation_result=? WHERE id=?",db.json(result),id);
  return new Change("POINT_EXCHANGE","point_exchange_record",id,Map.of("balance",balance,"stock_left",total-issued,"template_exchanged",used),Map.of("balance",next,"stock_left",total-issued-1,"template_exchanged",used+1,"user_coupon_id",uc,"catalog_id",policy.get("id"),"catalog_revision",policy.get("revision")),result);
 });}
 public Map<String,Object> list(VehicleOwner a,int p){if(p<1||p>1000000)throw ReservationInput.bad();return db.reads.execute(tx->{db.owner(a,false);long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM point_exchange_record WHERE user_id=?",Long.class,a.id());return page(db.jdbc.queryForList("SELECT creation_result FROM point_exchange_record WHERE user_id=? ORDER BY id DESC LIMIT 20 OFFSET ?",a.id(),(p-1)*20L).stream().map(r->map(r.get("creation_result"))).toList(),count,p,20);});}
}
