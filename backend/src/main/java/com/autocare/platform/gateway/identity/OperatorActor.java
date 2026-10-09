package com.autocare.platform.gateway.identity;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public record OperatorActor(long id, String session, Instant expires) {
    public static final String APP = "operator-account";
    public static OperatorActor from(Jwt jwt) {
        try {
            if (jwt == null || !"operator_account".equals(jwt.getClaimAsString("subject_type"))
                || !"OPERATOR".equals(jwt.getClaimAsString("role")) || !APP.equals(jwt.getClaimAsString("app_id"))
                || jwt.getClaim("merchant_id") != null || jwt.getClaim("binding_id") != null || jwt.getId() == null || jwt.getExpiresAt() == null) throw new IllegalArgumentException();
            long id = Long.parseLong(jwt.getSubject()); if (id < 1 || id > 9007199254740991L) throw new IllegalArgumentException();
            return new OperatorActor(id, jwt.getId(), jwt.getExpiresAt());
        } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅运营身份可访问审核接口"); }
    }
}
