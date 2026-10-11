package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Booking freezes policy; completion/refund append accrued facts in the caller transaction. */
public class CommissionLedger {
    static final String RULE="R2C_V1";
    static final BigDecimal RATE=new BigDecimal("3.00"),ZERO=new BigDecimal("0.00");
    final ReservationStore db;
    public CommissionLedger(ReservationStore db){this.db=db;}
    static Instant waivedUntil(Instant approved){return approved.atZone(ReservationInput.ZONE).plusMonths(3).toInstant();}
    static BigDecimal fee(BigDecimal base,boolean waived){return waived?ZERO:base.multiply(RATE).divide(new BigDecimal("100")).setScale(2,RoundingMode.HALF_UP);}
    static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("佣金事实必须在业务写事务内记录");}
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"佣金原始事实不一致，需核对");}
    static void initialize(ReservationStore db,long merchant,long application,Instant approved){
        transaction();db.jdbc.update("INSERT INTO merchant_commission_policy(merchant_id,rate_percent,waived_until,effective_from,rule_version,source_application_id) VALUES(?,3.00,?,?,?,?)",merchant,ReservationStore.time(waivedUntil(approved)),ReservationStore.time(approved),RULE,application);
    }
    static Map<String,Object> policy(ReservationStore db,long merchant,Instant now){
        var rows=db.jdbc.queryForList("SELECT * FROM merchant_commission_policy WHERE merchant_id=? AND effective_from<=? AND is_deleted=0 ORDER BY effective_from DESC,id DESC LIMIT 1",merchant,ReservationStore.time(now));
        if(rows.isEmpty())return null;var p=rows.get(0);
        if(!RULE.equals(p.get("rule_version"))||((BigDecimal)p.get("rate_percent")).compareTo(RATE)!=0||p.get("waived_until")==null||p.get("source_application_id")==null)return null;
        Instant start=ReservationStore.instant(p.get("effective_from"));if(!waivedUntil(start).equals(ReservationStore.instant(p.get("waived_until"))))return null;
        var sources=db.jdbc.queryForList("SELECT r.id FROM merchant_application a JOIN merchant_application_review r ON r.application_id=a.id WHERE a.id=? AND a.status='APPROVED' AND a.is_deleted=0 AND a.merchant_id=? AND r.merchant_id=? AND r.revision=a.revision AND r.decision='APPROVED' AND r.decided_at=? AND a.reviewed_at=r.decided_at AND a.reviewed_by=r.operator_id",p.get("source_application_id"),merchant,merchant,p.get("effective_from"));
        if(sources.size()!=1||db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application_review WHERE merchant_id=? AND decision='APPROVED'",Long.class,merchant)!=1)return null;
        return p;
    }
    static void booking(ReservationStore db,long id){
        transaction();var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);insertSnapshot(db,o,false);
    }
    static Map<String,Object> snapshot(ReservationStore db,Map<String,Object> o){
        var rows=db.jdbc.queryForList("SELECT * FROM order_commission_snapshot WHERE order_id=? FOR UPDATE",o.get("id"));
        if(rows.isEmpty())insertSnapshot(db,o,true);
        var s=db.one("SELECT * FROM order_commission_snapshot WHERE order_id=? FOR UPDATE",o.get("id"));
        if(!Objects.equals(s.get("merchant_id"),o.get("merchant_id"))||!RULE.equals(s.get("rule_version"))||!"CNY".equals(s.get("currency"))||((BigDecimal)s.get("base_amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0||!ReservationStore.instant(s.get("booked_at")).equals(ReservationStore.instant(o.get("created_at"))))throw conflict();
        if("READY".equals(s.get("state"))){
            if(s.get("policy_id")==null||s.get("rate_percent")==null||((BigDecimal)s.get("rate_percent")).compareTo(RATE)!=0||s.get("waived_until")==null)throw conflict();
            boolean waived=ReservationStore.instant(s.get("booked_at")).isBefore(ReservationStore.instant(s.get("waived_until")));
            if(!(waived?"NEW_MERCHANT":"NONE").equals(s.get("exemption"))||s.get("commission_amount")==null||fee((BigDecimal)s.get("base_amount"),waived).compareTo((BigDecimal)s.get("commission_amount"))!=0)throw conflict();
        }else if(!Set.of("WAIT_POLICY","WAIT_LEGACY","WAIT_COUPON").contains(s.get("state"))||!s.get("state").equals(s.get("exemption"))||s.get("commission_amount")!=null)throw conflict();
        return s;
    }
    static void insertSnapshot(ReservationStore db,Map<String,Object> o,boolean legacy){
        var base=(BigDecimal)o.get("pay_amount");if(base==null||base.signum()<=0||o.get("created_at")==null)throw conflict();
        Instant booked=ReservationStore.instant(o.get("created_at"));var p=legacy?null:policy(db,ReservationStore.number(o,"merchant_id"),booked);
        String state=legacy?"WAIT_LEGACY":o.get("coupon_id")!=null?"WAIT_COUPON":p==null?"WAIT_POLICY":"READY";
        boolean ready="READY".equals(state),waived=ready&&booked.isBefore(ReservationStore.instant(p.get("waived_until")));
        db.jdbc.update("INSERT INTO order_commission_snapshot(order_id,merchant_id,policy_id,booked_at,base_amount,rate_percent,waived_until,state,exemption,commission_amount,rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?)",o.get("id"),o.get("merchant_id"),ready?p.get("id"):null,o.get("created_at"),base,ready?RATE:null,ready?p.get("waived_until"):null,state,ready?(waived?"NEW_MERCHANT":"NONE"):state,ready?fee(base,waived):null,RULE);
    }
    static Map<String,Object> payment(ReservationStore db,Map<String,Object> o,long id){
        var p=db.one("SELECT * FROM payment WHERE id=? AND is_deleted=0 FOR UPDATE",id);
        if(!Objects.equals(p.get("order_id"),o.get("id"))||!"SUCCEEDED".equals(p.get("status"))||!"CNY".equals(p.get("currency"))||((BigDecimal)p.get("amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0||p.get("paid_at")==null||!OrderRefunds.nonempty(p.get("payment_no"))||!OrderRefunds.nonempty(p.get("channel_payment_no"))||!Set.of("WECHAT","LOCAL_TEST").contains(p.get("channel"))||db.jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE payment_id=? AND channel=? AND outcome='PAID' AND event_hash<>'' AND is_deleted=0",Long.class,id,p.get("channel"))!=1)throw conflict();
        return p;
    }
    static Map<String,Object> entry(ReservationStore db,long order,String event){var rows=db.jdbc.queryForList("SELECT * FROM merchant_commission_entry WHERE order_id=? AND event_key=? FOR UPDATE",order,event);return rows.isEmpty()?null:rows.get(0);}
    static void append(ReservationStore db,Map<String,Object> o,Map<String,Object> p,Map<String,Object> s,String event,String kind,Object refund,Object reference,BigDecimal amount,String exemption,Object occurred){
        long id=db.insert("INSERT INTO merchant_commission_entry(merchant_id,order_id,payment_id,refund_id,event_key,kind,reference_entry_id,base_amount,rate_percent,exemption,amount,occurred_at,test_mode,rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",o.get("merchant_id"),o.get("id"),p.get("id"),refund,event,kind,reference,s.get("base_amount"),s.get("rate_percent"),exemption,amount,occurred,"LOCAL_TEST".equals(p.get("channel"))?1:0,RULE);
        db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,?,'merchant_commission_entry',?,'{}',?,?)","COMMISSION_"+kind,id,db.json(Map.of("order_id",o.get("id"),"kind",kind,"test_mode","LOCAL_TEST".equals(p.get("channel")),"amount",amount==null?"UNKNOWN":amount.toPlainString())),UUID.randomUUID().toString());
    }
    static void sameSource(Map<String,Object> entry,Map<String,Object> order,Map<String,Object> payment,Map<String,Object> snapshot){
        if(!Objects.equals(entry.get("merchant_id"),order.get("merchant_id"))||!Objects.equals(entry.get("payment_id"),payment.get("id"))||!RULE.equals(entry.get("rule_version"))||!"CNY".equals(entry.get("currency"))||((BigDecimal)entry.get("base_amount")).compareTo((BigDecimal)snapshot.get("base_amount"))!=0||!Objects.equals(entry.get("rate_percent"),snapshot.get("rate_percent"))||ReservationStore.number(entry,"test_mode")!=("LOCAL_TEST".equals(payment.get("channel"))?1:0))throw conflict();
    }
    static void completed(ReservationStore db,long id){
        transaction();var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);if(!"COMPLETED".equals(o.get("status")))throw conflict();
        var r=db.one("SELECT * FROM order_redemption WHERE order_id=? FOR UPDATE",id);var p=payment(db,o,ReservationStore.number(r,"payment_id"));
        if(!Objects.equals(r.get("merchant_id"),o.get("merchant_id"))||ReservationStore.number(r,"test_mode")!=("LOCAL_TEST".equals(p.get("channel"))?1:0)||r.get("redeemed_at")==null)throw conflict();
        var s=snapshot(db,o);var prior=entry(db,id,"COMPLETION");if(prior!=null){sameSource(prior,o,p,s);if(prior.get("refund_id")!=null||prior.get("reference_entry_id")!=null)throw conflict();return;}
        boolean ready="READY".equals(s.get("state"))&&o.get("coupon_id")==null;
        append(db,o,p,s,"COMPLETION",ready?"ACCRUAL":"WAIT_REVIEW",null,null,ready?(BigDecimal)s.get("commission_amount"):null,o.get("coupon_id")!=null?"WAIT_COUPON":s.get("exemption").toString(),r.get("redeemed_at"));
    }
    static void refunded(ReservationStore db,Map<String,Object> o,Map<String,Object> supplied,Map<String,Object> r){
        transaction();if(!"SUCCEEDED".equals(r.get("status"))||!"CLOSED".equals(o.get("status"))||!"REFUND_CANCELLED".equals(o.get("close_reason"))||!Objects.equals(r.get("order_id"),o.get("id"))||!Objects.equals(r.get("payment_id"),supplied.get("id"))||r.get("refunded_at")==null||r.get("owner_consented_at")==null||r.get("merchant_consented_at")==null||((BigDecimal)r.get("amount")).compareTo((BigDecimal)o.get("pay_amount"))!=0)throw conflict();
        var p=payment(db,o,ReservationStore.number(supplied,"id"));var s=snapshot(db,o);long order=ReservationStore.number(o,"id");var prior=entry(db,order,"REFUND");if(prior!=null){sameSource(prior,o,p,s);if(!Objects.equals(prior.get("refund_id"),r.get("id"))||!Objects.equals(prior.get("payment_id"),p.get("id")))throw conflict();return;}
        var original=entry(db,order,"COMPLETION");BigDecimal amount=ZERO;String kind="REFUND_NO_CHARGE",reason="REFUND_BEFORE_COMPLETION";Object reference=null;
        if(original!=null){
            sameSource(original,o,p,s);
            if(!Objects.equals(original.get("merchant_id"),o.get("merchant_id"))||!Objects.equals(original.get("payment_id"),p.get("id"))||ReservationStore.number(original,"test_mode")!=("LOCAL_TEST".equals(p.get("channel"))?1:0))throw conflict();reference=original.get("id");reason=original.get("exemption").toString();
            if("ACCRUAL".equals(original.get("kind"))){var fee=(BigDecimal)original.get("amount");if(fee==null||fee.signum()<0||!"READY".equals(s.get("state"))||fee.compareTo((BigDecimal)s.get("commission_amount"))!=0)throw conflict();amount=fee.negate();kind="REFUND_REVERSAL";}
            else if("WAIT_REVIEW".equals(original.get("kind"))){amount=null;kind="WAIT_REVIEW";}else throw conflict();
        }
        append(db,o,p,s,"REFUND",kind,r.get("id"),reference,amount,reason,r.get("refunded_at"));
    }
    static void manager(MerchantActor actor){if(!actor.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅本店店长可查看佣金账单");}
    static String money(Object value){return value==null?null:((BigDecimal)value).setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    Map<String,Object> policyView(long merchant){var p=policy(db,merchant,db.now());var out=new LinkedHashMap<String,Object>();out.put("state",p==null?"UNCONFIGURED":"CONFIGURED");out.put("rate_percent",p==null?null:"3.00");out.put("effective_from",p==null?null:ReservationStore.iso(p.get("effective_from")));out.put("waived_until",p==null?null:ReservationStore.iso(p.get("waived_until")));out.put("rule_version",p==null?null:RULE);return out;}
    Map<String,Object> totals(long merchant,boolean test){var row=db.one("SELECT COALESCE(SUM(CASE WHEN kind='ACCRUAL' THEN amount ELSE 0 END),0) accrued,COALESCE(SUM(CASE WHEN kind='REFUND_REVERSAL' THEN amount ELSE 0 END),0) reversed,COUNT(DISTINCT CASE WHEN kind='WAIT_REVIEW' THEN order_id ELSE NULL END) pending_count FROM merchant_commission_entry WHERE merchant_id=? AND test_mode=?",merchant,test?1:0);BigDecimal accrued=(BigDecimal)row.get("accrued"),reversed=(BigDecimal)row.get("reversed");return Map.of("accrued",money(accrued),"reversed",money(reversed),"net",money(accrued.add(reversed)),"pending_count",row.get("pending_count"));}
    Map<String,Object> view(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(String k:List.of("entry_id","order_id","payment_id","refund_id","kind","exemption","rule_version","reference_entry_id"))out.put(k,row.get(k));for(String k:List.of("base_amount","rate_percent","amount"))out.put(k,money(row.get(k)));out.put("currency",row.get("currency"));out.put("test_mode",ReservationStore.number(row,"test_mode")==1);for(String k:List.of("occurred_at","booked_at","waived_until"))out.put(k,ReservationStore.iso(row.get(k)));return out;}
    public Map<String,Object> list(MerchantActor actor,int page,int size){manager(actor);if(page<1||page>1000000||size<1||size>50)throw ReservationInput.bad();return db.reads.execute(tx->{db.merchant(actor,false);long merchant=actor.merchantId();long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_commission_entry WHERE merchant_id=?",Long.class,merchant);var rows=db.jdbc.queryForList("SELECT e.*,e.id AS entry_id,s.booked_at,s.waived_until FROM merchant_commission_entry e JOIN order_commission_snapshot s ON s.order_id=e.order_id WHERE e.merchant_id=? ORDER BY e.occurred_at DESC,e.id DESC LIMIT ? OFFSET ?",merchant,size,(page-1)*size);return Map.of("merchant_id",merchant,"policy",policyView(merchant),"summary",Map.of("real",totals(merchant,false),"test",totals(merchant,true)),"page",page,"page_size",size,"total",count,"items",rows.stream().map(this::view).toList());});}
}
