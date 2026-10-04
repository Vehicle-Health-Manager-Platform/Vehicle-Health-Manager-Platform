package com.autocare.platform.vehicle;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public record VehicleOwner(long id, String session, Instant expires) {
    public static VehicleOwner from(Jwt jwt) {
        try {
            if (jwt == null || !"user".equals(jwt.getClaimAsString("subject_type"))
                || !"OWNER".equals(jwt.getClaimAsString("role")) || jwt.getId() == null
                || jwt.getExpiresAt() == null) throw new IllegalArgumentException();
            long id = Long.parseLong(jwt.getSubject());
            if (id <= 0) throw new IllegalArgumentException();
            return new VehicleOwner(id, jwt.getId(), jwt.getExpiresAt());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅正式车主可访问车辆接口");
        }
    }
}
