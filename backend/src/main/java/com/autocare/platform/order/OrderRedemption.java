package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Final code verification, completion and both audit records are server owned. */
public class OrderRedemption {
    static final class RateLimited extends ResponseStatusException {
        final int retry;RateLimited(int retry){super(HttpStatus.TOO_MANY_REQUESTS,"核销码尝试过于频繁，请稍后重试");this.retry=retry;}
    }
    private final ReservationStore db;
    private final ServiceWork work;
    private final PaymentChannels channels;
    public OrderRedemption(ReservationStore db,ServiceWork work,PaymentChannels channels){this.db=db;this.work=work;this.channels=channels;}
    static String code(JsonNode body){
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("code") || !body.get("code").isTextual() || !body.get("code").textValue().matches("[0-9]{6}"))throw ReservationInput.bad();
        return body.get("code").textValue();
    }
    private Map<String,Object> order(MerchantActor actor,long id){
        db.merchant(actor,true);var o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());
        if(o.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",o.get("slot_id"));
        return db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,actor.merchantId());
    }
    private Map<String,Object> receipt(long id){var rows=db.jdbc.queryForList("SELECT * FROM order_redemption WHERE order_id=? FOR UPDATE",id);return rows.isEmpty()?null:rows.get(0);}
    private static boolean nonempty(Object value){return value instanceof String s && !s.isBlank();}
    private Map<String,Object> payment(Map<String,Object> o){
        long id=ReservationStore.number(o,"id");
        if(!db.jdbc.queryForList("SELECT id FROM payment_exception WHERE order_id=? FOR UPDATE",id).isEmpty())throw new FulfillmentConflict(43009,"付款存在异常，暂不可核销");
        var rows=db.jdbc.queryForList("SELECT * FROM payment WHERE order_id=? AND status='SUCCEEDED' AND is_deleted=0 ORDER BY id FOR UPDATE",id);
        if(rows.size()!=1)throw new FulfillmentConflict(43009,"缺少唯一可信付款记录");var p=rows.get(0);
        if(!"CNY".equals(p.get("currency")) || ((BigDecimal)p.get("amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0 || !nonempty(p.get("payment_no")) || !nonempty(p.get("channel_payment_no")) || p.get("paid_at")==null)throw new FulfillmentConflict(43009,"付款凭据不完整或金额不符");
        var events=db.jdbc.queryForList("SELECT id,event_hash FROM payment_event WHERE payment_id=? AND channel=? AND outcome='PAID' AND is_deleted=0 FOR UPDATE",p.get("id"),p.get("channel"));
        if(events.size()!=1 || !nonempty(events.get(0).get("event_hash")))throw new FulfillmentConflict(43009,"付款事件尚未核实");
        channels.create(String.valueOf(p.get("channel"))); // LOCAL_TEST retains its profile, switch and secret guard; WECHAT is unavailable.
        return p;
    }
    private Map<String,Object> ready(Map<String,Object> o){
        if(!Set.of(OrderStatus.PENDING_VERIFY,OrderStatus.COMPLETED).contains(o.get("status")))throw new FulfillmentConflict(40905,"当前订单不可核销");
        work.redemptionGuard(o);var p=payment(o);var r=receipt(ReservationStore.number(o,"id"));
        if(OrderStatus.COMPLETED.equals(o.get("status"))){
            if(r==null || !Objects.equals(r.get("merchant_id"),o.get("merchant_id")) || !Objects.equals(r.get("payment_id"),p.get("id")) || ReservationStore.number(r,"test_mode")!=("LOCAL_TEST".equals(p.get("channel"))?1:0) || r.get("redeemed_at")==null)throw new FulfillmentConflict(40905,"历史完成订单缺少可信核销记录");
        }else if(r!=null)throw new FulfillmentConflict(40905,"订单与核销记录不一致");
        return p;
    }
    private static boolean matches(String code,Object expected){return expected instanceof String s && s.matches("[0-9]{6}") && MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),s.getBytes(StandardCharsets.US_ASCII));}
    private void guess(MerchantActor actor,long id,String code){
        long start=Math.floorDiv(db.now().getEpochSecond(),600)*600;String hash=AuthTokens.sha256(actor.merchantId()+":"+id);
        int result=db.transactions.execute(tx->{
            var o=order(actor,id);ready(o);var window=ReservationStore.time(Instant.ofEpochSecond(start));
            db.jdbc.update("INSERT IGNORE INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('redeem_code',?,?,0)",hash,window);
            int attempts=db.jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='redeem_code' AND key_hash=? AND window_start=? FOR UPDATE",Integer.class,hash,window);
            if(attempts>=5)return 429;
            if(!matches(code,o.get("verify_code"))){db.jdbc.update("UPDATE auth_rate_limit SET attempts=attempts+1 WHERE scope='redeem_code' AND key_hash=? AND window_start=?",hash,window);return 422;}
            return 0;
        });
        if(result==429)throw new RateLimited(Math.max(1,(int)(start+600-db.now().getEpochSecond())));
        if(result!=0)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"核销码无效");
    }
    public JsonNode redeem(MerchantActor actor,String key,long id,JsonNode body){
        ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);String code=code(body);guess(actor,id,code);
        var hashed=db.mapper.valueToTree(Map.of("order_id",id,"code_digest",AuthTokens.sha256(code)));
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/orders/"+id+"/redeem",key,hashed,()->{
            var o=order(actor,id);ready(o);
            if(!matches(code,o.get("verify_code")))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"核销码无效");
            // The shared counter may have changed after the first transaction committed.
            long start=Math.floorDiv(db.now().getEpochSecond(),600)*600;
            var attempts=db.jdbc.queryForList("SELECT attempts FROM auth_rate_limit WHERE scope='redeem_code' AND key_hash=? AND window_start=? FOR UPDATE",AuthTokens.sha256(actor.merchantId()+":"+id),ReservationStore.time(Instant.ofEpochSecond(start)));
            if(!attempts.isEmpty() && ReservationStore.number(attempts.get(0),"attempts")>=5)throw new RateLimited(Math.max(1,(int)(start+600-db.now().getEpochSecond())));
        },()->{
            var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var p=payment(o);var existing=receipt(id);
            if(existing!=null){var current=result(id,existing,false);return new Change("ORDER_REDEEM_REPLAY","order",id,current,current,current,false);}
            var now=ReservationStore.time(db.now());boolean test="LOCAL_TEST".equals(p.get("channel"));
            db.jdbc.update("INSERT INTO order_redemption(order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES(?,?,?,?,?,?)",id,actor.merchantId(),actor.staffId(),p.get("id"),test?1:0,now);
            if(!OrderStatus.can(OrderStatus.PENDING_VERIFY,OrderStatus.COMPLETED) || db.jdbc.update("UPDATE `order` SET status='COMPLETED' WHERE id=? AND status='PENDING_VERIFY'",id)!=1)throw new FulfillmentConflict(40905,"当前订单不可核销");
            db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES(?,?,'PENDING_VERIFY','COMPLETED','ORDER_COMPLETE','staff_account',?,?)",id,actor.merchantId(),actor.staffId(),now);
            var after=result(id,receipt(id),true);return new Change("ORDER_COMPLETE","order",id,Map.of("order_id",id,"order_status",OrderStatus.PENDING_VERIFY),after,after);
        });
    }
    private Map<String,Object> result(long id,Map<String,Object> r,boolean changed){return Map.of("order_id",id,"order_status",OrderStatus.COMPLETED,"redeemed_at",ReservationStore.iso(r.get("redeemed_at")),"test_mode",ReservationStore.number(r,"test_mode")==1,"changed",changed);}
    public Map<String,Object> detail(Object actor,long id){
        ServiceCatalog.validateId(id);return db.reads.execute(tx->{
            if(actor instanceof MerchantActor shop){db.merchant(shop,false);db.one("SELECT id FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,shop.merchantId());}
            else if(actor instanceof VehicleOwner owner){db.owner(owner,false);db.one("SELECT id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());}
            else throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看核销记录");
            var rows=db.jdbc.queryForList("SELECT redeemed_at,test_mode FROM order_redemption WHERE order_id=?",id);var out=new LinkedHashMap<String,Object>();out.put("order_id",id);out.put("redemption",rows.isEmpty()?null:Map.of("redeemed_at",ReservationStore.iso(rows.get(0).get("redeemed_at")),"test_mode",ReservationStore.number(rows.get(0),"test_mode")==1));return out;
        });
    }
}
