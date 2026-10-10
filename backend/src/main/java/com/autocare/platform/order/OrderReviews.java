package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Immutable owner feedback. Completion evidence is historical; payment anomalies are live. */
public class OrderReviews {
    private final ReservationStore db;
    public OrderReviews(ReservationStore db){this.db=db;}
    private Map<String,Object> order(VehicleOwner owner,long id,boolean lock){
        db.owner(owner,lock);
        return db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id,owner.id());
    }
    private Map<String,Object> stored(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM order_review WHERE order_id=?"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> receipt(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM order_redemption WHERE order_id=?"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static boolean present(Object s){return s instanceof String text && !text.isBlank();}
    String unavailable(Map<String,Object> o,Map<String,Object> r,boolean lock){
        if(!OrderStatus.COMPLETED.equals(o.get("status")))return "ORDER_NOT_COMPLETED";
        long id=ReservationStore.number(o,"id");String tail=lock?" FOR UPDATE":"";
        if(!db.jdbc.queryForList("SELECT id FROM order_dispute WHERE order_id=? AND status='OPEN'"+tail,id).isEmpty())return "OPEN_DISPUTE";
        if(r==null || !Objects.equals(r.get("merchant_id"),o.get("merchant_id")) || r.get("redeemed_at")==null
            || db.jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role IN ('MERCHANT','STAFF')",r.get("staff_id"),o.get("merchant_id")).size()!=1)return "REDEMPTION_UNVERIFIED";
        if(!db.jdbc.queryForList("SELECT id FROM payment_exception WHERE order_id=?"+tail,id).isEmpty())return "PAYMENT_UNVERIFIED";
        var payments=db.jdbc.queryForList("SELECT * FROM payment WHERE order_id=? AND status='SUCCEEDED' AND is_deleted=0 ORDER BY id"+tail,id);
        if(payments.size()!=1)return "PAYMENT_UNVERIFIED";var p=payments.get(0);String channel=String.valueOf(p.get("channel"));
        if(!Objects.equals(r.get("payment_id"),p.get("id")) || !Set.of("LOCAL_TEST","WECHAT").contains(channel)
            || ReservationStore.number(r,"test_mode")!=(channel.equals("LOCAL_TEST")?1:0) || !"CNY".equals(p.get("currency"))
            || ((BigDecimal)p.get("amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0 || !present(p.get("payment_no"))
            || !present(p.get("channel_payment_no")) || p.get("paid_at")==null)return "PAYMENT_UNVERIFIED";
        var events=db.jdbc.queryForList("SELECT event_hash FROM payment_event WHERE payment_id=? AND channel=? AND outcome='PAID' AND is_deleted=0"+tail,p.get("id"),channel);
        return events.size()==1 && present(events.get(0).get("event_hash"))?null:"PAYMENT_UNVERIFIED";
    }
    private void files(VehicleOwner owner,JsonNode body){
        var ids=new ArrayList<Long>();body.get("photo_file_ids").forEach(n->ids.add(n.longValue()));
        for(long id:ids.stream().sorted().toList())if(db.jdbc.queryForList("SELECT id FROM file_object WHERE id=? AND owner_type='user' AND owner_id=? AND scan_status='CLEAN' AND is_deleted=0 AND content_type IN ('image/jpeg','image/png') AND size_bytes BETWEEN 1 AND 10485760 FOR UPDATE",id,owner.id()).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"评价图片非本人安全文件或已不可用");
    }
    private Map<String,Object> view(Map<String,Object> r){
        return Map.of("review_id",r.get("id"),"rating",r.get("rating"),"content",r.get("content"),
            "photo_file_ids",db.jdbc.query("SELECT file_id FROM order_review_file WHERE review_id=? ORDER BY position",(rs,n)->rs.getLong(1),r.get("id")),
            "submitted_at",ReservationStore.iso(r.get("submitted_at")),"test_mode",ReservationStore.number(r,"test_mode")==1);
    }
    private Map<String,Object> data(long order,Map<String,Object> r){return Map.of("order_id",order,"review",view(r));}
    private Map<String,Object> audit(long order,Map<String,Object> r){
        return Map.of("order_id",order,"review_id",r.get("id"),"rating",r.get("rating"),"photo_count",((List<?>)view(r).get("photo_file_ids")).size(),"test_mode",ReservationStore.number(r,"test_mode")==1,"submitted_at",ReservationStore.iso(r.get("submitted_at")));
    }
    private boolean same(Map<String,Object> r,JsonNode b){
        var v=db.mapper.valueToTree(view(r));return v.get("rating").asInt()==b.get("rating").asInt() && v.get("content").equals(b.get("content")) && v.get("photo_file_ids").equals(b.get("photo_file_ids"));
    }
    public JsonNode submit(VehicleOwner owner,String key,JsonNode raw){
        WriteIntegrityService.normalizeKey(key);var b=OrderReviewInput.normalize(raw);long id=b.get("order_id").longValue();
        return db.writes.execute(new Actor("user",owner.id()),"POST","/api/order/review",key,b,()->{
            var o=order(owner,id,true);var r=receipt(id,true);String reason=unavailable(o,r,true);
            if(reason!=null)throw new FulfillmentConflict(44001,"订单未完成可信核销、存在争议或付款尚未核实");
            var existing=stored(id,true);if(existing!=null && (!Objects.equals(existing.get("user_id"),o.get("user_id")) || !Objects.equals(existing.get("redemption_id"),r.get("id")) || !Objects.equals(existing.get("test_mode"),r.get("test_mode"))))throw new FulfillmentConflict(44001,"评价来源与核销记录不一致");
            files(owner,b);
        },()->{
            var existing=stored(id,true);
            if(existing!=null){if(!same(existing,b))throw new FulfillmentConflict(44002,"订单已评价，不能修改或重复提交不同内容");var projection=audit(id,existing);return new Change("ORDER_REVIEW_REPLAY","order_review",ReservationStore.number(existing,"id"),projection,projection,data(id,existing),false);}
            var receipt=receipt(id,true);long record=db.insert("INSERT INTO order_review(order_id,user_id,redemption_id,rating,content,test_mode,submitted_at) VALUES(?,?,?,?,?,?,?)",id,owner.id(),receipt.get("id"),b.get("rating").intValue(),b.get("content").textValue(),receipt.get("test_mode"),ReservationStore.time(db.now()));
            int position=0;for(var file:b.get("photo_file_ids"))db.jdbc.update("INSERT INTO order_review_file(review_id,file_id,position) VALUES(?,?,?)",record,file.longValue(),position++);
            ServiceArchiveJobs.enqueue(db,id,record);
            var after=stored(id,true);return new Change("ORDER_REVIEW_CREATE","order_review",record,Map.of("order_id",id),audit(id,after),data(id,after));
        });
    }
    public Map<String,Object> detail(VehicleOwner owner,long id){
        ServiceCatalog.validateId(id);return db.reads.execute(tx->{
            var o=order(owner,id,false);var review=stored(id,false);var receipt=receipt(id,false);String reason=review==null?unavailable(o,receipt,false):"ALREADY_REVIEWED";
            var result=new LinkedHashMap<String,Object>();result.put("order_id",id);result.put("can_submit",reason==null);result.put("unavailable_reason",reason);
            Boolean testMode=null;
            if(review!=null)testMode=ReservationStore.number(review,"test_mode")==1;
            else if(reason==null)testMode=ReservationStore.number(receipt,"test_mode")==1;
            result.put("test_mode",testMode);
            result.put("review",review==null?null:view(review));return result;
        });
    }
}
