package com.autocare.platform.common.write;

import com.autocare.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** All callbacks must use this service's datasource and perform no external side effects. */
public class WriteIntegrityService {
    public record Actor(String type, long id) {
        public Actor {
            if (!("user".equals(type) || "staff_account".equals(type)) || id <= 0) {
                throw new IllegalArgumentException("Invalid server-side actor");
            }
        }
    }
    public record Change(String action, String resourceType, long resourceId,
                         Map<String, Object> before, Map<String, Object> after, Map<String, Object> data,
                         boolean auditRequired) {
        public Change(String action, String resourceType, long resourceId,
                      Map<String, Object> before, Map<String, Object> after, Map<String, Object> data) {
            this(action, resourceType, resourceId, before, after, data, true);
        }
    }
    private record Stored(String hash, String response, boolean live) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public WriteIntegrityService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
        // Own the commit: never return a successful result before its transaction commits.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(15);
    }

    public JsonNode execute(Actor actor, String method, String path, String key, JsonNode request,
                            Runnable authorizeAndLock, Supplier<Change> mutate) {
        String canonicalKey = normalizeKey(key);
        if (!"POST".equals(method) || path == null || !path.startsWith("/api/") || path.length() > 256) {
            throw new IllegalArgumentException("Invalid server-side write scope");
        }
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(canonical(request))));
            return tx.execute(status -> {
                // Also run on a cache hit. Callers lock resources before idempotency records.
                authorizeAndLock.run();
                int inserted = jdbc.update("INSERT IGNORE INTO idempotency_record "
                    + "(actor_type,actor_id,request_method,request_path,idempotency_key,request_hash,expires_at) "
                    + "VALUES (?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(), INTERVAL 24 HOUR))",
                    actor.type(), actor.id(), method, path, canonicalKey, hash);
                Stored stored = jdbc.queryForObject("SELECT request_hash,response_body,"
                    + "(expires_at>UTC_TIMESTAMP() AND is_deleted=0) AS live_record FROM idempotency_record "
                    + "WHERE actor_type=? AND actor_id=? AND request_method=? AND request_path=? "
                    + "AND idempotency_key=? FOR UPDATE", (rs, row) ->
                        new Stored(rs.getString(1), rs.getString(2), rs.getBoolean(3)),
                    actor.type(), actor.id(), method, path, canonicalKey);
                if (inserted == 0 && stored.live()) {
                    if (!stored.hash().equals(hash)) throw badRequest("同一幂等键不能用于不同请求");
                    if (stored.response() == null) throw unavailable();
                    return read(stored.response());
                }
                Change change = mutate.get();
                if (change == null || change.resourceId() <= 0 || change.action() == null
                    || !change.action().matches("[A-Z_]{1,64}") || change.resourceType() == null
                    || !change.resourceType().matches("[a-z_]{1,64}")) {
                    throw new IllegalStateException("Invalid write audit metadata");
                }
                JsonNode response = read(json(ApiResponse.success(change.data())));
                if (!change.auditRequired() && !java.util.Objects.equals(change.before(), change.after())) {
                    throw new IllegalStateException("Unaudited change must preserve resource state");
                }
                if (change.auditRequired()) jdbc.update("INSERT INTO audit_log (actor_type,actor_id,action,resource_type,resource_id,"
                    + "before_state,after_state,request_id) VALUES (?,?,?,?,?,?,?,?)",
                    actor.type(), actor.id(), change.action(), change.resourceType(), change.resourceId(),
                    json(change.before()), json(change.after()), response.path("request_id").asText());
                jdbc.update("UPDATE idempotency_record SET request_hash=?,response_body=?,"
                    + "expires_at=DATE_ADD(UTC_TIMESTAMP(), INTERVAL 24 HOUR),is_deleted=0 "
                    + "WHERE actor_type=? AND actor_id=? AND request_method=? AND request_path=? AND idempotency_key=?",
                    hash, json(response), actor.type(), actor.id(), method, path, canonicalKey);
                return response;
            });
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            // Do not expose JDBC errors, response payloads or serialization exception details.
            throw unavailable();
        }
    }

    public static String normalizeKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw badRequest("Idempotency-Key 必须是 UUID");
        }
        return UUID.fromString(key).toString();
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null) throw badRequest("请求正文无效");
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> sorted.set(field, canonical(node.get(field))));
            return sorted;
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (Exception exception) { throw unavailable(); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw unavailable(); }
    }
    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "写入服务暂不可用，请使用原幂等键重试");
    }
}
