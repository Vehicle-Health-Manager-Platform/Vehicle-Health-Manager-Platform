package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class ReservationStore {
    final JdbcTemplate jdbc;final ObjectMapper mapper;final WriteIntegrityService writes;final Clock clock;final TransactionTemplate reads,transactions;
    public ReservationStore(JdbcTemplate jdbc,ObjectMapper mapper,WriteIntegrityService writes,Clock clock,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.mapper=mapper;this.writes=writes;this.clock=clock;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions=new TransactionTemplate(manager);transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transactions.setTimeout(15);
    }
    Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);}
    static Timestamp time(Instant instant){return Timestamp.from(instant);}
    static Instant instant(Object value){if(value instanceof Timestamp stamp)return stamp.toInstant();if(value instanceof LocalDateTime local)return local.toInstant(ZoneOffset.UTC);throw new IllegalArgumentException("预约时间数据无效");}
    static String iso(Object stamp){return stamp==null?null:instant(stamp).toString();}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"预约资源不存在或不可用");}
    void owner(VehicleOwner owner,boolean lock){
        String tail=lock?" FOR UPDATE":"";
        var rows=jdbc.query("SELECT s.id FROM auth_session s JOIN user u ON u.id=s.subject_id WHERE s.id=? AND s.subject_id=? AND s.subject_type='user' AND s.role='OWNER' AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP() AND u.status=1 AND u.is_deleted=0"+tail,(r,n)->r.getString(1),owner.session(),owner.id());
        if(rows.isEmpty() || !Instant.now().isBefore(owner.expires()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");
    }
    void merchant(MerchantActor actor,boolean lock){
        String tail=lock?" FOR UPDATE":"";
        // 店长与店员共用履约执行面：角色必须与令牌一致，未知角色一律拒绝。
        var session=jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' AND subject_id=? AND role=? AND app_id='merchant-account' AND merchant_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()"+tail,(r,n)->r.getString(1),actor.session(),actor.staffId(),actor.role(),actor.merchantId());
        var staff=jdbc.query("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role=? AND status='ACTIVE' AND is_deleted=0"+tail,(r,n)->r.getLong(1),actor.staffId(),actor.merchantId(),actor.role());
        var shop=jdbc.query("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0"+tail,(r,n)->r.getLong(1),actor.merchantId());
        if(session.isEmpty() || staff.isEmpty() || shop.isEmpty() || !Instant.now().isBefore(actor.expires()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"门店登录已失效，请重新登录");
    }
    void lockShop(long id){if(jdbc.query("SELECT id FROM merchant WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),id).isEmpty())throw missing();}
    void technician(TechnicianActor actor,String appId,boolean lock){
        String tail=lock?" FOR UPDATE":"";
        var session=jdbc.queryForList("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' AND subject_id=? AND role='TECHNICIAN' AND app_id=? AND merchant_id=? AND binding_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()"+tail,actor.session(),actor.staffId(),appId,actor.merchantId(),actor.bindingId());
        // Serialize identity mutations and assignments on the merchant first.
        var shop=jdbc.queryForList("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0"+tail,actor.merchantId());
        var staff=jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role='TECHNICIAN' AND status='ACTIVE' AND is_deleted=0"+tail,actor.staffId(),actor.merchantId());
        var binding=jdbc.queryForList("SELECT id FROM staff_wechat_identity WHERE id=? AND staff_account_id=? AND app_id=? AND status='ACTIVE' AND is_deleted=0 AND unbound_at IS NULL"+tail,actor.bindingId(),actor.staffId(),appId);
        if(!appId.equals(actor.appId()) || session.isEmpty() || shop.isEmpty() || staff.isEmpty() || binding.isEmpty() || !Instant.now().isBefore(actor.expires()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"技师登录已失效，请重新登录");
    }
    Map<String,Object> one(String query,Object... args){var rows=jdbc.queryForList(query,args);if(rows.isEmpty())throw missing();return rows.get(0);}
    static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    long insert(String sql,Object... args){
        var holder=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int n=0;n<args.length;n++)statement.setObject(n+1,args[n]);return statement;},holder);return holder.getKey().longValue();
    }
    String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("预约序列化失败");}}
    JsonNode parse(Object value){try{return value==null?mapper.nullNode():mapper.readTree(value.toString());}catch(Exception error){throw new IllegalStateException("预约快照无效");}}
    static Map<String,Object> page(List<?> items,long count,int page,int size){return Map.of("items",items,"total",count,"page",page,"page_size",size);}
    // Future paid/service statuses continue consuming capacity. Only closed,
    // deleted and already expired unpaid orders cease to consume a place.
    long occupied(long slot,Instant now){
        String filter=" FROM `order` WHERE slot_id=? AND is_deleted=0 AND status<>'CLOSED' AND (status<>'PENDING_PAYMENT' OR expires_at IS NULL OR expires_at>?)";
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())return jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,slot,time(now));
        return jdbc.queryForList("SELECT id"+filter+" FOR UPDATE",slot,time(now)).size();
    }
    Map<String,Object> slotRow(Map<String,Object> row,Instant now){
        var result=new LinkedHashMap<String,Object>();long id=number(row,"id");
        result.put("slot_id",id);result.put("standard_project_id",number(row,"project_id"));result.put("starts_at",iso(row.get("starts_at")));result.put("ends_at",iso(row.get("ends_at")));result.put("capacity",number(row,"capacity"));result.put("capacity_left",Math.max(0,number(row,"capacity")-occupied(id,now)));result.put("open",number(row,"is_open")==1);result.put("project_name",row.getOrDefault("project_name",""));return result;
    }
    Map<String,Object> quote(long id,boolean lock){
        var q=one("SELECT merchant_id,project_id FROM merchant_project WHERE id=?",id);
        long shop=number(q,"merchant_id"),project=number(q,"project_id");
        if(lock){lockShop(shop);one("SELECT id FROM standard_project WHERE id=? FOR UPDATE",project);one("SELECT id FROM merchant_project WHERE id=? FOR UPDATE",id);}
        Long latest=lock?number(one("SELECT id FROM merchant_project_version WHERE merchant_project_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",id),"id"):null;
        var args=lock?new Object[]{id,latest}:new Object[]{id};
        var row=one("SELECT q.id,q.merchant_id,q.project_id,m.name,m.address,p.project_name,p.service_content,v.id AS version_id,v.version,v.price FROM merchant_project q JOIN merchant m ON m.id=q.merchant_id JOIN standard_project p ON p.id=q.project_id JOIN merchant_project_version v ON v.merchant_project_id=q.id WHERE q.id=? AND q.is_deleted=0 AND q.on_shelf=1 AND m.status=1 AND m.is_deleted=0 AND p.status=1 AND p.is_deleted=0"+(lock?" AND v.id=? FOR UPDATE":" AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id)"),args);
        if(((java.math.BigDecimal)row.get("price")).signum()<=0)throw missing();return row;
    }
    Map<String,Object> quoteResult(Map<String,Object> q){return Map.of("merchant_project_id",number(q,"id"),"quote_version_id",number(q,"version_id"),"version",number(q,"version"),"merchant_id",number(q,"merchant_id"),"standard_project_id",number(q,"project_id"),"merchant_name",q.get("name"),"address",q.get("address"),"project_name",q.get("project_name"),"price",q.get("price").toString());}
    Map<String,Object> orderRow(Map<String,Object> row,boolean detail){
        var result=new LinkedHashMap<String,Object>();result.put("order_id",number(row,"id"));result.put("order_no",row.get("order_no"));result.put("status",row.get("status"));result.put("amount_due",row.get("pay_amount").toString());result.put("created_at",iso(row.get("created_at")));result.put("expires_at",iso(row.get("expires_at")));result.put("closed_at",iso(row.get("closed_at")));result.put("close_reason",row.get("close_reason"));result.put("project_snapshot",parse(row.get("project_snapshot")));result.put("merchant_snapshot",parse(row.get("merchant_snapshot")));result.put("appointment_snapshot",parse(row.get("appointment_snapshot")));
        var payments=jdbc.queryForList("SELECT p.id,p.channel,p.status,p.amount,p.currency,p.expires_at,p.paid_at,EXISTS(SELECT 1 FROM payment_exception e WHERE e.payment_id=p.id) AS requires_review FROM payment p WHERE p.order_id=? AND p.is_deleted=0 ORDER BY CASE WHEN p.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM payment_exception e WHERE e.payment_id=p.id) THEN 0 ELSE 1 END,p.id DESC LIMIT 1",number(row,"id"));
        if(payments.isEmpty())result.put("payment_summary",null);else{var p=payments.get(0);var summary=new LinkedHashMap<String,Object>();summary.put("payment_id",p.get("id"));summary.put("channel",p.get("channel"));summary.put("test_mode","LOCAL_TEST".equals(p.get("channel")));summary.put("status",p.get("status"));summary.put("amount",p.get("amount").toString());summary.put("currency",p.get("currency"));summary.put("expires_at",iso(p.get("expires_at")));summary.put("paid_at",iso(p.get("paid_at")));Object review=p.get("requires_review");summary.put("requires_review",review instanceof Boolean flag?flag:((Number)review).intValue()!=0);result.put("payment_summary",summary);}
        if(detail){result.put("vehicle_id",number(row,"vehicle_id"));result.put("price_snapshot",parse(row.get("price_snapshot")));}return result;
    }
}
