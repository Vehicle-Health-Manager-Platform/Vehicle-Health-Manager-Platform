package com.autocare.platform.gateway.identity;

import java.time.Instant;
import java.util.Optional;

public interface AuthSessionRepository {
    record Session(String id, String subjectType, long subjectId, String role, String appId,
                   Long bindingId, Long merchantId) {}

    void create(Session session, String refreshHash, Instant expiresAt);
    boolean active(String sessionId);
    Optional<Session> rotate(String oldHash, String newHash);
    void revoke(String sessionId);
}
