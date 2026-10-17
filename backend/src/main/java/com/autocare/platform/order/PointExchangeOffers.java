package com.autocare.platform.order;

import com.autocare.platform.vehicle.VehicleOwner;
import java.time.Instant;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** A consistent read quote, never permission to skip the atomic exchange rechecks. */
public class PointExchangeOffers {
 private final ReservationStore db;private final PointExchangeCatalog catalog;private final PointLedger ledger;private final boolean enabled;
 public PointExchangeOffers(ReservationStore db,PointExchangeCatalog catalog,PointLedger ledger,boolean enabled){this.db=db;this.catalog=catalog;this.ledger=ledger;this.enabled=enabled;}
 private static final String SELECT="""
 SELECT e.*,c.merchant_id,c.status AS coupon_status,c.is_deleted AS coupon_deleted,
 c.valid_from AS coupon_valid_from,c.valid_to AS coupon_valid_to,c.total_quantity,c.issued_quantity,
 s.project_id AS current_project_id,s.pool_selected,s.pool_revision AS current_pool_revision,
 p.revision AS current_publication_revision,p.terms_json AS current_terms,
 m.id AS active_merchant_id,m.name AS merchant_name,m.address AS merchant_address,
 sp.id AS active_project_id,sp.project_name,sp.service_content,mp.id AS active_merchant_project_id,
 (SELECT COUNT(*) FROM point_exchange_record x WHERE x.user_id=? AND x.coupon_id=e.coupon_id) AS exchanged_count
 FROM point_exchange_catalog e
 LEFT JOIN coupon c ON c.id=e.coupon_id
 LEFT JOIN coupon_source s ON s.coupon_id=c.id
 LEFT JOIN coupon_publication p ON p.coupon_id=c.id
 LEFT JOIN merchant m ON m.id=c.merchant_id AND m.status=1 AND m.is_deleted=0
 LEFT JOIN standard_project sp ON sp.id=s.project_id AND sp.status=1 AND sp.is_deleted=0
 LEFT JOIN merchant_project mp ON mp.merchant_id=c.merchant_id AND mp.project_id=s.project_id AND mp.on_shelf=1 AND mp.is_deleted=0
 WHERE e.status='PUBLISHED' ORDER BY e.id DESC LIMIT 20 OFFSET ?
 """;
 private Map<String,Object> offer(Map<String,Object> r,long balance,boolean available,Instant at){
  var terms=db.parse(r.get("terms_snapshot"));
  boolean known=r.get("current_terms")!=null&&r.get("current_publication_revision")!=null;
  boolean matching=known&&number(r,"current_publication_revision")==number(r,"publication_revision")&&r.get("current_pool_revision")!=null&&number(r,"current_pool_revision")==number(r,"pool_revision")&&r.get("current_project_id")!=null&&number(r,"current_project_id")==terms.path("project_id").asLong()&&db.parse(r.get("current_terms")).equals(terms);
  boolean active=r.get("coupon_deleted")!=null&&number(r,"coupon_deleted")==0&&"PUBLISHED".equals(r.get("coupon_status"))&&r.get("active_merchant_id")!=null&&r.get("active_project_id")!=null&&r.get("active_merchant_project_id")!=null;
  Long stock=r.get("total_quantity")==null?null:number(r,"total_quantity")-number(r,"issued_quantity");long used=number(r,"exchanged_count"),remaining=Math.max(0,number(r,"per_owner_limit")-used);
  String reason=instant(r.get("valid_from")).isAfter(at)?"NOT_STARTED":!instant(r.get("valid_to")).isAfter(at)?"EXPIRED":!known?"SOURCE_UNKNOWN":!active?"SOURCE_UNAVAILABLE":!matching||number(r,"pool_selected")!=1?"SOURCE_CHANGED":instant(r.get("coupon_valid_from")).isAfter(at)?"NOT_STARTED":!instant(r.get("coupon_valid_to")).isAfter(at)?"EXPIRED":stock==null||stock<=0?"OUT_OF_STOCK":remaining==0?"LIMIT_REACHED":balance<number(r,"points_cost")?"INSUFFICIENT_POINTS":!available?"EXCHANGE_UNAVAILABLE":"ELIGIBLE";
  var n=new LinkedHashMap<String,Object>();n.put("catalog",catalog.view(r));n.put("merchant",active&&matching?Map.of("id",r.get("merchant_id"),"name",r.get("merchant_name"),"address",Objects.toString(r.get("merchant_address"),"")):null);n.put("project",active&&matching?Map.of("id",r.get("current_project_id"),"name",r.get("project_name"),"service_content",Objects.toString(r.get("service_content"),"")):null);n.put("stock_left",stock);n.put("exchanged_count",used);n.put("remaining_limit",remaining);n.put("eligibility",reason);return n;
 }
 public Map<String,Object> list(VehicleOwner a,int p){if(p<1||p>1000000)throw ReservationInput.bad();return db.reads.execute(tx->{boolean verified=ledger.verifiedIdentity(a,false),available=enabled&&verified;var accounts=db.jdbc.queryForList("SELECT balance FROM point_verified_account WHERE user_id=?",a.id());long balance=accounts.isEmpty()?0:number(accounts.get(0),"balance");boolean legacy=db.jdbc.queryForObject("SELECT point_balance FROM user WHERE id=?",Long.class,a.id())!=0||db.jdbc.queryForObject("SELECT COUNT(*) FROM point_flow WHERE user_id=?",Long.class,a.id())>0;
  long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM point_exchange_catalog WHERE status='PUBLISHED'",Long.class);var at=db.now();var items=db.jdbc.queryForList(SELECT,a.id(),(p-1)*20L).stream().map(r->offer(r,balance,available,at)).toList();var result=new LinkedHashMap<String,Object>(page(items,count,p,20));result.put("balance",balance);result.put("legacy_state",legacy?"REVIEW_REQUIRED":"NONE");result.put("exchange_available",available);result.put("read_at",at.toString());return result;
 });}
}
