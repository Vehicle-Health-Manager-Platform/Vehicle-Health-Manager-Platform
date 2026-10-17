package com.autocare.platform.gateway.identity;

import com.autocare.platform.gateway.SecurityConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class OperatorIdentity {
    /** Fixed, non-overlapping operator permissions. A missing column value is always denied. */
    public enum Permission { NONE, REVIEW, ONBOARD, CATALOG, COUPON, INVITE, DIAGNOSE, DIAGNOSE_MANAGE, POINTS, ASSESSMENT, ANALYTICS, PRICE_MONITOR, COMMUNITY_CURATE, COMMUNITY_ANSWER, COMMUNITY_MANAGE }
    private final JdbcTemplate jdbc; private final JwtEncoder encoder; private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    private final String dummy=passwords.encode("not-an-operator-password"); private final SecureRandom random=new SecureRandom();
    public OperatorIdentity(JdbcTemplate jdbc,JwtEncoder encoder,PlatformTransactionManager manager) { this.jdbc=jdbc;this.encoder=encoder;tx=new TransactionTemplate(manager);tx.setTimeout(15); }
    private Map<String,Object> verified(String account,String password) {
        var rows=jdbc.queryForList("SELECT * FROM operator_account WHERE account=? FOR UPDATE",OperatorInput.account(account));
        var row=rows.isEmpty()?null:rows.get(0); boolean match=passwords.matches(OperatorInput.password(password),row==null?dummy:(String)row.get("password_hash"));
        if(row==null || !"ACTIVE".equals(row.get("status")) || !match || !Objects.toString(row.get("phone"),"").matches("1[3-9][0-9]{9}"))throw invalid();return row;
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号、密码或验证码无效"); }
    private static long number(Map<String,Object> r,String k) { return ((Number)r.get(k)).longValue(); }
    public void code(String account,String password,OperatorSmsSender sender) {
        tx.executeWithoutResult(t->{var row=verified(account,password);if(sender==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"运营短信服务尚未配置");
            String purpose="O"+number(row,"id"),phone=(String)row.get("phone");
            String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
            jdbc.update("UPDATE sms_code SET consumed_at=UTC_TIMESTAMP() WHERE phone=? AND purpose=? AND consumed_at IS NULL",phone,purpose);
            jdbc.update("INSERT INTO sms_code(phone,purpose,code_hash,expires_at) VALUES(?,?,?,?)",phone,purpose,passwords.encode(code),Timestamp.from(Instant.now().plusSeconds(300)));
            sender.sendLoginCode(phone,code);
        });
    }
    public Map<String,Object> login(String account,String password,String code) {
        OperatorInput.account(account);OperatorInput.password(password);if(code==null || !code.matches("[0-9]{6}"))throw OperatorInput.bad();
        return tx.execute(t->{var row=verified(account,password);long id=number(row,"id");
            var codes=jdbc.queryForList("SELECT id,code_hash FROM sms_code WHERE phone=? AND purpose=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP() AND is_deleted=0 ORDER BY id DESC LIMIT 1 FOR UPDATE",row.get("phone"),"O"+id);
            if(codes.isEmpty() || !passwords.matches(code,(String)codes.get(0).get("code_hash")))throw invalid();
            jdbc.update("UPDATE sms_code SET consumed_at=UTC_TIMESTAMP() WHERE id=?",codes.get(0).get("id"));
            Instant now=Instant.now();String session=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',?,'OPERATOR',?,?,?)",session,id,OperatorActor.APP,AuthTokens.sha256(UUID.randomUUID().toString()),Timestamp.from(now.plusSeconds(900)));
            jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('operator_account',?,'OPERATOR_LOGIN','operator_account',?,'{}','{\"second_factor\":true}',?)",id,id,UUID.randomUUID().toString());
            var claims=JwtClaimsSet.builder().issuer(SecurityConfig.issuer()).subject(Long.toString(id)).id(session).issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("subject_type","operator_account").claim("role","OPERATOR").claim("app_id",OperatorActor.APP).build();
            String access=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
            var user=new LinkedHashMap<String,Object>();user.put("id",id);user.put("role","operator");for(String k:List.of("can_review","can_onboard","can_catalog","can_coupon","can_invite","can_diagnose","can_diagnose_manage","can_points","can_assessment","can_analytics","can_price_monitor","can_community_curate","can_community_answer","can_community_manage"))user.put(k,number(row,k)==1);
            return Map.of("access_token",access,"token_type","Bearer","expires_in",900,"user",user);
        });
    }
    public void authorize(OperatorActor a,boolean review,boolean lock) {
        authorize(a,review?Permission.REVIEW:Permission.NONE,lock);
    }
    public void authorize(OperatorActor a,Permission permission,boolean lock) {
        String tail=lock?" FOR UPDATE":"";
        // Account management and login also lock the account before sessions.
        if(lock)jdbc.queryForList("SELECT id FROM operator_account WHERE id=? FOR UPDATE",a.id());
        var rows=jdbc.queryForList("SELECT o.can_review,o.can_onboard,o.can_catalog,o.can_coupon,o.can_invite,o.can_diagnose,o.can_diagnose_manage,o.can_points,o.can_assessment,o.can_analytics,o.can_price_monitor,o.can_community_curate,o.can_community_answer,o.can_community_manage FROM auth_session s JOIN operator_account o ON o.id=s.subject_id WHERE s.id=? AND s.subject_type='operator_account' AND s.subject_id=? AND s.role='OPERATOR' AND s.app_id=? AND s.binding_id IS NULL AND s.merchant_id IS NULL AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP() AND o.status='ACTIVE'"+tail,a.session(),a.id(),OperatorActor.APP);
        if(rows.isEmpty() || !Instant.now().isBefore(a.expires()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"运营会话已失效，请重新登录");
        if(permission==Permission.COMMUNITY_ANSWER && number(rows.get(0),"can_community_answer")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有私有问答处理权限");
        if(permission==Permission.COMMUNITY_MANAGE && number(rows.get(0),"can_community_manage")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有私有问答分配权限");
        if(permission==Permission.COMMUNITY_CURATE && number(rows.get(0),"can_community_curate")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有运营精选权限");
        if(permission==Permission.PRICE_MONITOR && number(rows.get(0),"can_price_monitor")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有价格监控权限");
        if(permission==Permission.ANALYTICS && number(rows.get(0),"can_analytics")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有经营看板权限");
        if(permission==Permission.ASSESSMENT && number(rows.get(0),"can_assessment")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有考核政策权限");
        if(permission==Permission.POINTS && number(rows.get(0),"can_points")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有积分规则权限");
        if(permission==Permission.DIAGNOSE && number(rows.get(0),"can_diagnose")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有诊断处理权限");
        if(permission==Permission.DIAGNOSE_MANAGE && number(rows.get(0),"can_diagnose_manage")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前账号没有诊断管理权限");
        if(permission==Permission.REVIEW && number(rows.get(0),"can_review")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前运营账号没有经验审核权限");
        if(permission==Permission.ONBOARD && number(rows.get(0),"can_onboard")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前运营账号没有入驻审核权限");
        if(permission==Permission.CATALOG && number(rows.get(0),"can_catalog")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前运营账号没有目录维护权限");
        if(permission==Permission.INVITE && number(rows.get(0),"can_invite")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前运营账号没有邀请规则权限");
        if(permission==Permission.COUPON && number(rows.get(0),"can_coupon")!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前运营账号没有券池管理权限");
    }
    public boolean valid(Jwt jwt) { try{authorize(OperatorActor.from(jwt),false,false);return true;}catch(RuntimeException e){return false;} }
    public void logout(OperatorActor a) { tx.executeWithoutResult(t->{authorize(a,false,true);jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",a.session());}); }
}
