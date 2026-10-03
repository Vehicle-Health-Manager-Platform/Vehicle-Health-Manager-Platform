package com.autocare.platform.gateway.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAuthSessionRepository implements AuthSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcAuthSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(Session session, String refreshHash, Instant expiresAt) {
        jdbc.update("INSERT INTO auth_session (id,subject_type,subject_id,role,app_id,binding_id,merchant_id,refresh_hash,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", session.id(), session.subjectType(), session.subjectId(),
            session.role(), session.appId(), session.bindingId(), session.merchantId(), refreshHash,
            Timestamp.from(expiresAt));
    }

    public boolean active(String sessionId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_session WHERE id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()",
            Integer.class, sessionId);
        return count != null && count > 0;
    }

    public Optional<Session> rotate(String oldHash, String newHash) {
        var rows = jdbc.query("SELECT id,subject_type,subject_id,role,app_id,binding_id,merchant_id FROM auth_session "
                + "WHERE refresh_hash=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP() FOR UPDATE",
            (rs, row) -> new Session(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), nullableLong(rs.getObject(6)), nullableLong(rs.getObject(7))), oldHash);
        if (rows.isEmpty()) return Optional.empty();
        Session session = rows.get(0);
        jdbc.update("UPDATE auth_session SET refresh_hash=? WHERE id=?", newHash, session.id());
        return Optional.of(session);
    }

    public void revoke(String sessionId) {
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=? AND revoked_at IS NULL", sessionId);
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
