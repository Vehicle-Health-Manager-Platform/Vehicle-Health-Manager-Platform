package com.autocare.platform.gateway.identity;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Database-backed fixed windows shared by all backend instances. */
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;

    public AuthRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void check(String scope, String key, int maximum, long windowSeconds) {
        long start = Math.floorDiv(Instant.now().getEpochSecond(), windowSeconds) * windowSeconds;
        String hash = AuthTokens.sha256(key);
        Timestamp window = Timestamp.from(Instant.ofEpochSecond(start));
        jdbc.update("INSERT INTO auth_rate_limit (scope,key_hash,window_start,attempts) VALUES (?,?,?,1) "
            + "ON DUPLICATE KEY UPDATE attempts=attempts+1", scope, hash, window);
        Integer attempts = jdbc.queryForObject(
            "SELECT attempts FROM auth_rate_limit WHERE scope=? AND key_hash=? AND window_start=?",
            Integer.class, scope, hash, window);
        if (attempts == null || attempts > maximum) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
        }
    }
}
