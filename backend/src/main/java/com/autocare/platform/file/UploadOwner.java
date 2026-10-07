package com.autocare.platform.file;

import java.time.Instant;
import org.springframework.security.oauth2.jwt.Jwt;

public record UploadOwner(long id, String session, Instant tokenExpires, String type, long merchantId) {
    public UploadOwner(long id, String session, Instant tokenExpires) {
        this(id, session, tokenExpires, "user", 0);
    }
    public static UploadOwner merchant(Jwt jwt) {
        var actor = com.autocare.platform.service.MerchantActor.from(jwt);
        return new UploadOwner(actor.staffId(), actor.session(), actor.expires(), "staff_account", actor.merchantId());
    }
    public String path() { return "staff_account".equals(type) ? "/api/merchant/files/upload" : "/api/file/upload"; }
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
    public FileMetadataRepository.Actor actor() { return new FileMetadataRepository.Actor(type, id); }
}
