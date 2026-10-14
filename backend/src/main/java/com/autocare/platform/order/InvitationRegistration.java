package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.AuthTokens;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Server-side first-registration and phone evidence. No public attribution mutation. */
public class InvitationRegistration {
    public static final String RULE = "R6A_INVITE_V1";
    private final ReservationStore db;
    public InvitationRegistration(ReservationStore db) { this.db = db; }
    public record LoginResult(long ownerId,String invitationResult) {}

    public long register(String openid, String code, String source, String appId) {
        return registerForLogin(openid,code,source,appId).ownerId();
    }
    public LoginResult registerForLogin(String openid, String code, String source, String appId) {
        if (openid == null || openid.isBlank() || openid.length() > 128
            || appId == null || appId.length() > 64 || !Set.of("VERIFIED_WECHAT", "UNKNOWN", "TEST").contains(source))
            throw new IllegalArgumentException("Invalid server identity evidence");
        if (code != null && !code.matches("[0-9a-f]{32}")) throw InvitationInput.bad();
        return db.transactions.execute(tx -> {
            // INSERT IGNORE returns 1 only for the first creator. The unique openid
            // serializes concurrent logins without upgrading existing accounts.
            int fresh = db.jdbc.update("INSERT IGNORE INTO `user`(openid) VALUES(?)", openid);
            var owner = db.one("SELECT id,status,is_deleted FROM `user` WHERE openid=? FOR UPDATE", openid);
            long id = ReservationStore.number(owner, "id");
            if (ReservationStore.number(owner,"status") != 1 || ReservationStore.number(owner,"is_deleted") != 0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号不可用");
            if (fresh == 0) return new LoginResult(id,"EXISTING_ACCOUNT");
            Map<String,Object> promo = null;
            String outcome = "NO_CODE";
            if (code != null) {
                var codes = db.jdbc.queryForList("SELECT * FROM invite_code WHERE code=?", code);
                if (codes.isEmpty()) outcome = "INVALID_CODE";
                else {
                    var candidate = codes.get(0);
                    if ("user".equals(candidate.get("actor_type")) && ReservationStore.number(candidate,"actor_id") == id) outcome="SELF";
                    else if (!active(candidate,true)) outcome="SOURCE_UNAVAILABLE";
                    else { promo=candidate; outcome="ATTRIBUTED"; }
                }
            }
            String state = promo == null ? "REJECTED" : source.equals("VERIFIED_WECHAT") ? "PENDING" : source;
            db.jdbc.update("INSERT INTO invite_registration(user_id,record_id,app_id,identity_source,invite_code_id,source_type,source_id,merchant_id,attribution_result,qualification_status,registered_at,rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,UUID.randomUUID().toString(),appId,source,promo==null?null:promo.get("id"),promo==null?null:promo.get("actor_type"),promo==null?null:promo.get("actor_id"),promo==null?null:promo.get("merchant_id"),outcome,state,ReservationStore.time(db.now()),RULE);
            audit(id,"INVITE_REGISTER",Map.of("attribution_result",outcome,"qualification_status",state,"rule_version",RULE));
            return new LoginResult(id,outcome);
        });
    }

    public void bindPhone(long owner, String phone, String appId, boolean verified) {
        bindPhone(owner,phone,appId,verified,()->{});
    }
    public void bindPhone(com.autocare.platform.vehicle.VehicleOwner actor,String phone,String appId,boolean verified) {
        bindPhone(actor.id(),phone,appId,verified,()->db.owner(actor,true));
    }
    private void bindPhone(long owner,String phone,String appId,boolean verified,Runnable guard) {
        db.transactions.executeWithoutResult(tx -> {
            guard.run(); // Revalidate session after the external phone exchange, before commit.
            var user=db.one("SELECT status,is_deleted FROM `user` WHERE id=? FOR UPDATE",owner);
            if (ReservationStore.number(user,"status")!=1 || ReservationStore.number(user,"is_deleted")!=0)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号不可用");
            try { db.jdbc.update("UPDATE `user` SET phone=? WHERE id=?",phone,owner); }
            catch (org.springframework.dao.DataIntegrityViolationException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"手机号已绑定其他账号"); }
            if (!verified) return;
            String hash=AuthTokens.sha256(phone);
            int first=db.jdbc.update("INSERT IGNORE INTO invite_phone_evidence(phone_hash,user_id,app_id,verified_at) VALUES(?,?,?,?)",hash,owner,appId,ReservationStore.time(db.now()));
            var rows=db.jdbc.queryForList("SELECT * FROM invite_registration WHERE user_id=? FOR UPDATE",owner);
            if(rows.isEmpty()) return; // legacy accounts never gain new-user eligibility.
            var r=rows.get(0);
            if(!"PENDING".equals(r.get("qualification_status")) || !appId.equals(r.get("app_id"))) return;
            if(db.now().isBefore(ReservationStore.instant(r.get("registered_at"))))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"资格时间尚不可确认，请稍后重试");
            if(first==0) { reject(owner,"PHONE_SEEN"); return; }
            var codes=db.jdbc.queryForList("SELECT * FROM invite_code WHERE id=?",r.get("invite_code_id"));
            if(codes.isEmpty() || !active(codes.get(0),true)) { reject(owner,"SOURCE_UNAVAILABLE"); return; }
            String month=db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString().substring(0,7);
            db.jdbc.update("UPDATE invite_registration SET qualification_status='QUALIFIED',qualified_at=?,reward_month=? WHERE user_id=? AND qualification_status='PENDING'",ReservationStore.time(db.now()),month,owner);
            audit(owner,"INVITE_QUALIFY",Map.of("reward_month",month,"rule_version",RULE));
        });
    }

    private void reject(long owner,String reason) {
        db.jdbc.update("UPDATE invite_registration SET qualification_status='REJECTED',qualification_reason=? WHERE user_id=? AND qualification_status='PENDING'",reason,owner);
        audit(owner,"INVITE_REJECT",Map.of("reason",reason,"rule_version",RULE));
    }

    boolean active(Map<String,Object> code,boolean lock) {
        String tail=lock?" FOR UPDATE":"";
        if("user".equals(code.get("actor_type"))) return !db.jdbc.queryForList("SELECT id FROM `user` WHERE id=? AND status=1 AND is_deleted=0"+tail,code.get("actor_id")).isEmpty();
        return !db.jdbc.queryForList("SELECT s.id FROM staff_account s JOIN merchant m ON m.id=s.merchant_id WHERE s.id=? AND s.merchant_id=? AND s.role IN ('MERCHANT','STAFF') AND s.status='ACTIVE' AND s.is_deleted=0 AND m.status=1 AND m.is_deleted=0"+tail,code.get("actor_id"),code.get("merchant_id")).isEmpty();
    }
    private void audit(long owner,String action,Map<String,Object> after) {
        db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('user',?,?,'invite_registration',?,'{}',?,?)",owner,action,owner,db.json(after),UUID.randomUUID().toString());
    }
}
