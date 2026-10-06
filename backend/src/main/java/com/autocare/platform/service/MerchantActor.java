package com.autocare.platform.service;

import com.autocare.platform.gateway.identity.AuthTokens;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record MerchantActor(long staffId,long merchantId,String session,Instant expires) {
    public static MerchantActor from(Jwt jwt) {
        try {
            if(jwt==null || !"staff_account".equals(jwt.getClaimAsString("subject_type"))
                || !"MERCHANT".equals(jwt.getClaimAsString("role")) || !AuthTokens.MERCHANT_APP_ID.equals(jwt.getClaimAsString("app_id"))
                || jwt.hasClaim("binding_id") || jwt.getId()==null || jwt.getExpiresAt()==null) throw new IllegalArgumentException();
            long staff=Long.parseLong(jwt.getSubject()); Number merchant=jwt.getClaim("merchant_id");
            if(staff<=0 || merchant==null || merchant.longValue()<=0) throw new IllegalArgumentException();
            return new MerchantActor(staff,merchant.longValue(),jwt.getId(),jwt.getExpiresAt());
        } catch(RuntimeException error) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅本店商家账号可维护报价"); }
    }
}
