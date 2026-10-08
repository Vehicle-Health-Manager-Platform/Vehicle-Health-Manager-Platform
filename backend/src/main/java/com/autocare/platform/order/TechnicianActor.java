package com.autocare.platform.order;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Staff IDs and binding IDs are separate identity dimensions. */
public record TechnicianActor(long staffId,long merchantId,long bindingId,String appId,String session,Instant expires) {
    public static TechnicianActor from(Jwt jwt,String appId) {
        try {
            if(jwt==null || !"staff_account".equals(jwt.getClaimAsString("subject_type"))
                || !"TECHNICIAN".equals(jwt.getClaimAsString("role")) || appId==null || appId.isBlank()
                || !appId.equals(jwt.getClaimAsString("app_id")) || jwt.getId()==null || jwt.getId().isBlank()
                || jwt.getExpiresAt()==null)throw new IllegalArgumentException();
            long staff=Long.parseLong(jwt.getSubject());
            long shop=integer(jwt.getClaim("merchant_id")),binding=integer(jwt.getClaim("binding_id"));
            if(staff<=0 || staff>9007199254740991L)throw new IllegalArgumentException();
            return new TechnicianActor(staff,shop,binding,appId,jwt.getId(),jwt.getExpiresAt());
        }catch(RuntimeException error){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅已绑定的技师账号可操作本人工单");}
    }
    private static long integer(Object value){
        if(!(value instanceof Number n) || !(n instanceof Long || n instanceof Integer) || n.longValue()<=0 || n.longValue()>9007199254740991L)throw new IllegalArgumentException();
        return n.longValue();
    }
}
