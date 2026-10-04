package com.autocare.platform.file;

import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

public record UploadOwner(long id, String session, Instant tokenExpires) {
    public static UploadOwner from(Jwt jwt) {
        try {
            if (jwt == null || !"user".equals(jwt.getClaimAsString("subject_type"))
                || !"OWNER".equals(jwt.getClaimAsString("role")) || jwt.getId() == null
                || jwt.getExpiresAt() == null) throw new IllegalArgumentException();
            long id = Long.parseLong(jwt.getSubject());
            if (id <= 0) throw new IllegalArgumentException();
            return new UploadOwner(id, jwt.getId(), jwt.getExpiresAt());
        } catch (RuntimeException e) { throw new UploadHttpException(403, "仅正式车主会话可使用图片接口"); }
    }
    public FileMetadataRepository.Actor actor() { return new FileMetadataRepository.Actor("user", id); }
}
