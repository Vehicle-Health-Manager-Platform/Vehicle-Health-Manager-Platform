package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Consent and read projection share the exact live source qualification. No private content is selected. */
public class PublicRatings {
    private final ReservationStore db;
    static final String SOURCE = " FROM order_review r JOIN `order` o ON o.id=r.order_id AND o.user_id=r.user_id"
        + " JOIN user u ON u.id=r.user_id JOIN merchant m ON m.id=o.merchant_id"
        + " JOIN order_redemption d ON d.id=r.redemption_id AND d.order_id=o.id AND d.merchant_id=o.merchant_id"
        + " JOIN staff_account s ON s.id=d.staff_id AND s.merchant_id=o.merchant_id AND s.role IN ('MERCHANT','STAFF')"
        + " JOIN payment p ON p.id=d.payment_id AND p.order_id=o.id";
    static final String VALID = " o.status='COMPLETED' AND o.is_deleted=0 AND u.status=1 AND u.is_deleted=0 AND m.status=1 AND m.is_deleted=0"
        + " AND r.test_mode=0 AND d.test_mode=0 AND d.redeemed_at IS NOT NULL AND r.rating BETWEEN 1 AND 5"
        + " AND p.channel='WECHAT' AND p.status='SUCCEEDED' AND p.is_deleted=0 AND p.currency='CNY' AND p.amount=o.pay_amount AND p.amount>0"
        + " AND TRIM(p.payment_no)<>'' AND TRIM(p.channel_payment_no)<>'' AND p.paid_at IS NOT NULL"
        + " AND (SELECT COUNT(*) FROM payment pp WHERE pp.order_id=o.id AND pp.status='SUCCEEDED' AND pp.is_deleted=0)=1"
        + " AND (SELECT COUNT(*) FROM payment_event e WHERE e.payment_id=p.id AND e.channel=p.channel AND e.outcome='PAID' AND e.is_deleted=0)=1"
        + " AND EXISTS(SELECT 1 FROM payment_event e WHERE e.payment_id=p.id AND e.channel=p.channel AND e.outcome='PAID' AND e.is_deleted=0 AND TRIM(e.event_hash)<>'')"
        + " AND NOT EXISTS(SELECT 1 FROM payment_exception e WHERE e.order_id=o.id)"
        + " AND NOT EXISTS(SELECT 1 FROM order_dispute x WHERE x.order_id=o.id AND x.status='OPEN')"
        + " AND NOT EXISTS(SELECT 1 FROM refund f WHERE f.order_id=o.id)";
    public PublicRatings(ReservationStore db) { this.db=db; }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"公开资格或授权版本已变化，请刷新核对");}
    Map<String,Object> owned(VehicleOwner owner,long order,boolean lock) {
        ServiceCatalog.validateId(order);db.owner(owner,lock);
        db.one("SELECT id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),order,owner.id());
        return db.one("SELECT id,test_mode FROM order_review WHERE order_id=? AND user_id=?"+(lock?" FOR UPDATE":""),order,owner.id());
    }
    boolean eligible(long review) {return db.jdbc.queryForObject("SELECT COUNT(*)"+SOURCE+" WHERE r.id=? AND "+VALID,Long.class,review)==1L;}
    private Map<String,Object> state(long order,Map<String,Object> review) {
        long id=ReservationStore.number(review,"id");var grants=db.jdbc.queryForList("SELECT consented,version FROM review_publication WHERE review_id=?",id);
        boolean consented=!grants.isEmpty() && ReservationStore.number(grants.get(0),"consented")==1,can=eligible(id);
        var out=new LinkedHashMap<String,Object>();out.put("order_id",order);out.put("version",grants.isEmpty()?0L:ReservationStore.number(grants.get(0),"version"));
        out.put("consented",consented);out.put("visible",consented&&can);out.put("can_publish",can);
        out.put("unavailable_reason",can?null:ReservationStore.number(review,"test_mode")==1?"TEST_ORDER":"PUBLIC_SOURCE_UNVERIFIED");return out;
    }
    public Map<String,Object> detail(VehicleOwner owner,long order) {return db.reads.execute(tx->state(order,owned(owner,order,false)));}
    public JsonNode update(VehicleOwner owner,long order,String key,boolean publish,long expected) {
        if(expected<0 || expected>9007199254740991L)throw ReservationInput.bad();
        return db.writes.execute(new Actor("user",owner.id()),"PUT","/api/order/"+order+"/review-publication",key,db.mapper.valueToTree(Map.of("publish",publish,"expected_version",expected)),
            ()->{var review=owned(owner,order,true);if(publish&&!eligible(ReservationStore.number(review,"id")))throw conflict();},
            ()->{var review=owned(owner,order,true);long id=ReservationStore.number(review,"id");var before=state(order,review);
                if(((Number)before.get("version")).longValue()!=expected)throw conflict();
                db.jdbc.update("INSERT INTO review_publication(review_id,consented,version) VALUES(?,?,1) ON DUPLICATE KEY UPDATE consented=VALUES(consented),version=version+1,updated_at=UTC_TIMESTAMP()",id,publish);
                var after=state(order,review);
                return new Change("REVIEW_PUBLICATION_UPDATE","order_review",id,projection(before),projection(after),after);
            });
    }
    private static Map<String,Object> projection(Map<String,Object> state) {return Map.of("consented",state.get("consented"),"version",state.get("version"));}
    public Map<String,Object> list(VehicleOwner owner,long merchant,int page,int size) {
        ServiceCatalog.validateId(merchant);if(page<1||page>1000000||size<1||size>50)throw ReservationInput.bad();
        return db.reads.execute(tx->{db.owner(owner,false);db.one("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0",merchant);
            String from=SOURCE+" JOIN review_publication g ON g.review_id=r.id AND g.consented=1 WHERE o.merchant_id=? AND "+VALID;
            var totals=db.jdbc.queryForMap("SELECT COUNT(*) AS samples,COALESCE(SUM(r.rating),0) AS rating_sum"+from,merchant);
            long count=ReservationStore.number(totals,"samples"),sum=ReservationStore.number(totals,"rating_sum");
            var items=db.jdbc.query("SELECT r.rating,DATE_FORMAT(r.submitted_at,'%Y-%m-%d') AS submitted_date"+from+" ORDER BY r.submitted_at DESC,r.id DESC LIMIT ? OFFSET ?",
                (rs,n)->Map.of("rating",rs.getInt("rating"),"submitted_date",rs.getString("submitted_date")),merchant,size,(page-1)*size);
            var summary=new LinkedHashMap<String,Object>();summary.put("count",count);summary.put("rating_sum",sum);summary.put("average_rating",count==0?null:BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP).toPlainString());
            return Map.of("merchant_id",merchant,"page",page,"page_size",size,"items",items,"summary",summary);
        });
    }
}
