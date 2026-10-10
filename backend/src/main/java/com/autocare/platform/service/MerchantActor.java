package com.autocare.platform.service;

import com.autocare.platform.gateway.identity.AuthTokens;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record MerchantActor(long staffId,long merchantId,String role,String session,Instant expires) {
    /** 既有四参构造等价于店长（MERCHANT），供旧调用点与测试沿用。 */
    public MerchantActor(long staffId,long merchantId,String session,Instant expires) {
        this(staffId,merchantId,"MERCHANT",session,expires);
    }
    /** 管理面（员工、员工码、门店资料、选品定价）仅店长可执行。 */
    public boolean manager() { return "MERCHANT".equals(role); }
    public static MerchantActor from(Jwt jwt) {
        try {
            String role=jwt==null?null:jwt.getClaimAsString("role");
            if(jwt==null || !"staff_account".equals(jwt.getClaimAsString("subject_type"))
                || !("MERCHANT".equals(role) || "STAFF".equals(role)) || !AuthTokens.MERCHANT_APP_ID.equals(jwt.getClaimAsString("app_id"))
                || jwt.hasClaim("binding_id") || jwt.getId()==null || jwt.getExpiresAt()==null) throw new IllegalArgumentException();
            long staff=Long.parseLong(jwt.getSubject()); Number merchant=jwt.getClaim("merchant_id");
            if(staff<=0 || merchant==null || merchant.longValue()<=0) throw new IllegalArgumentException();
            return new MerchantActor(staff,merchant.longValue(),role,jwt.getId(),jwt.getExpiresAt());
        } catch(RuntimeException error) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅本店门店账号可执行该操作"); }
    }
}
