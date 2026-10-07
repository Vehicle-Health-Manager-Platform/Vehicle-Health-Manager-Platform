package com.autocare.platform;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.LocalMileageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

// Required in CI: absence of Docker is a failure, never a silent skip.
@Testcontainers
class JdbcWriteIntegrityTest {
    // Only this disposable container permits triggers for fault injection.
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static final ObjectMapper mapper = new ObjectMapper();
    WriteIntegrityService integrity;
    LocalMileageService vehicles;
    String key;

    @BeforeAll static void schema() throws Exception {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs", "sql", "migrations", "V001__baseline.sql")));
        }
    }
    @BeforeEach void setUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_audit");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_cache");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM vehicle");
        jdbc.update("INSERT INTO vehicle (id,user_id,current_mileage) VALUES (1001,1001,10),(2001,2001,10),(1002,1001,10)");
        integrity = new WriteIntegrityService(jdbc, mapper, new DataSourceTransactionManager(jdbc.getDataSource()));
        vehicles = new LocalMileageService(jdbc, integrity, mapper);
        key = UUID.randomUUID().toString();
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int mileage() { return jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=1001", Integer.class); }

    @Test void sameRequestReplaysOriginalResponseAndAuditIsSafe() {
        JsonNode first = vehicles.update(1001, 1001, key, 20);
        assertEquals(first, vehicles.update(1001, 1001, key.toUpperCase(), 20));
        assertEquals(20, mileage());
        assertEquals(1, count("idempotency_record"));
        assertEquals(1, count("audit_log"));
        assertEquals(first.path("request_id").asText(), jdbc.queryForObject("SELECT request_id FROM audit_log", String.class));
        assertEquals(mapper.valueToTree(Map.of("current_mileage", 10)),
            mapperValue("SELECT before_state FROM audit_log"));
        assertEquals(mapper.valueToTree(Map.of("current_mileage", 20)),
            mapperValue("SELECT after_state FROM audit_log"));
        assertEquals("user", jdbc.queryForObject("SELECT actor_type FROM audit_log", String.class));
        assertEquals("VEHICLE_MILEAGE_UPDATE", jdbc.queryForObject("SELECT action FROM audit_log", String.class));
        assertEquals(1001L, jdbc.queryForObject("SELECT resource_id FROM audit_log", Long.class));
        long ttl = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),expires_at) FROM idempotency_record", Long.class);
        assertTrue(ttl >= 86390 && ttl <= 86400);
    }
    JsonNode mapperValue(String sql) {
        try { return mapper.readTree(jdbc.queryForObject(sql, String.class)); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @Test void explicitNoOpCachesResponseWithoutAnotherSuccessAudit() {
        var current = Map.<String, Object>of("current_mileage", 10);
        var actor = new WriteIntegrityService.Actor("user", 1001);
        var body = mapper.valueToTree(current);
        JsonNode response = integrity.execute(actor, "POST", "/api/test/no-op", key, body,
            () -> {}, () -> new WriteIntegrityService.Change("TEST_REPLAY", "vehicle", 1001,
                current, current, Map.of("changed", false), false));
        assertEquals(response, integrity.execute(actor, "POST", "/api/test/no-op", key, body,
            () -> {}, () -> { throw new AssertionError("Must replay cached response"); }));
        assertEquals(10, mileage());
        assertEquals(1, count("idempotency_record"));
        assertEquals(0, count("audit_log"));
    }

    @Test void changedStateCannotSuppressAuditAndRollsBackWrite() {
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> integrity.execute(
            new WriteIntegrityService.Actor("user", 1001), "POST", "/api/test/no-op", key,
            mapper.valueToTree(Map.of("current_mileage", 20)), () -> {}, () -> {
                jdbc.update("UPDATE vehicle SET current_mileage=20 WHERE id=1001");
                return new WriteIntegrityService.Change("TEST_WRITE", "vehicle", 1001,
                    Map.of("current_mileage", 10), Map.of("current_mileage", 20), Map.of(), false);
            })).getStatusCode().value());
        assertEquals(10, mileage());
        assertEquals(0, count("idempotency_record"));
        assertEquals(0, count("audit_log"));
    }

    @Test void changedPayloadWithSameKeyCannotWrite() {
        vehicles.update(1001, 1001, key, 20);
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> vehicles.update(1001, 1001, key, 30)).getStatusCode().value());
        assertEquals(20, mileage());
        assertEquals(1, count("audit_log"));
    }

    @Test void concurrentIdenticalRequestsOnlyCommitOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var gate = new CountDownLatch(1);
        try {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                gate.await();
                return vehicles.update(1001, 1001, key, 20);
            })).toList();
            gate.countDown();
            JsonNode first = tasks.get(0).get(20, TimeUnit.SECONDS);
            for (var task : tasks) assertEquals(first, task.get(20, TimeUnit.SECONDS));
            assertEquals(1, count("audit_log"));
            assertEquals(1, count("idempotency_record"));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDifferentPayloadsWithSameKeyHaveOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { gate.await(); return outcome(20); });
            var second = pool.submit(() -> { gate.await(); return outcome(30); });
            gate.countDown();
            assertEquals(java.util.Set.of(200, 400), java.util.Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            assertEquals(1, count("audit_log"));
        } finally { pool.shutdownNow(); }
    }
    int outcome(int value) {
        try { vehicles.update(1001, 1001, key, value); return 200; }
        catch (ResponseStatusException error) { return error.getStatusCode().value(); }
    }

    @Test void expiredKeyExecutesAgainAndConcurrentExpiryStillCommitsOnce() throws Exception {
        JsonNode first = vehicles.update(1001, 1001, key, 20);
        jdbc.update("UPDATE idempotency_record SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { gate.await(); return vehicles.update(1001, 1001, key, 30); });
            var b = pool.submit(() -> { gate.await(); return vehicles.update(1001, 1001, key, 30); });
            gate.countDown();
            JsonNode next = a.get(20, TimeUnit.SECONDS);
            assertEquals(next, b.get(20, TimeUnit.SECONDS));
            assertNotEquals(first.path("request_id"), next.path("request_id"));
            assertEquals(2, count("audit_log"));
            assertEquals(1, count("idempotency_record"));
        } finally { pool.shutdownNow(); }
    }

    @Test void replayChecksCurrentOwnershipAndDeletionBeforeReturningCachedData() {
        vehicles.update(1001, 1001, key, 20);
        jdbc.update("UPDATE vehicle SET user_id=2001 WHERE id=1001");
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> vehicles.update(1001, 1001, key, 20)).getStatusCode().value());
        jdbc.update("UPDATE vehicle SET is_deleted=1 WHERE id=1001");
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> vehicles.update(1001, 1001, key, 20)).getStatusCode().value());
        assertEquals(1, count("audit_log"));
    }

    @Test void businessFailureRollsBackKeyAndCanRetry() {
        assertThrows(ResponseStatusException.class, () -> vehicles.update(1001, 1001, key, 5));
        assertEquals(0, count("idempotency_record"));
        assertEquals(0, count("audit_log"));
        assertEquals(10, mileage());
        vehicles.update(1001, 1001, key, 20);
        assertEquals(1, count("audit_log"));
    }

    @Test void auditFailureRollsBackBusinessAndCache() {
        jdbc.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON audit_log FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private audit detail'");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> vehicles.update(1001, 1001, key, 20));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getReason().contains("private"));
        assertEquals(10, mileage());
        assertEquals(0, count("audit_log"));
        assertEquals(0, count("idempotency_record"));
        jdbc.execute("DROP TRIGGER reject_audit");
        vehicles.update(1001, 1001, key, 20);
        assertEquals(20, mileage());
    }

    @Test void cacheSaveFailureAlsoRollsBackAuditAndBusiness() {
        jdbc.execute("CREATE TRIGGER reject_cache BEFORE UPDATE ON idempotency_record FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cache failed'");
        assertEquals(503, assertThrows(ResponseStatusException.class,
            () -> vehicles.update(1001, 1001, key, 20)).getStatusCode().value());
        assertEquals(10, mileage());
        assertEquals(0, count("audit_log"));
        assertEquals(0, count("idempotency_record"));
    }

    @Test void actorAndPathScopesAreIndependent() {
        vehicles.update(1001, 1001, key, 20);
        vehicles.update(1001, 1002, key, 20);
        vehicles.update(2001, 2001, key, 20);
        assertEquals(3, count("idempotency_record"));
        assertEquals(3, count("audit_log"));
    }

    @Test void serializationFailureRollsBackEarlierBusinessWrite() {
        Object unserializable = new Object() {
            public String getValue() { throw new IllegalStateException("private serialization detail"); }
        };
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> integrity.execute(
            new WriteIntegrityService.Actor("user", 1001), "POST", "/api/test/write", key,
            mapper.valueToTree(Map.of("current_mileage", 20)), () -> {}, () -> {
                jdbc.update("UPDATE vehicle SET current_mileage=20 WHERE id=1001");
                return new WriteIntegrityService.Change("TEST_WRITE", "vehicle", 1001,
                    Map.of("current_mileage", 10), Map.of("current_mileage", 20), Map.of("value", unserializable));
            }));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getReason().contains("private"));
        assertEquals(10, mileage());
        assertEquals(0, count("audit_log"));
        assertEquals(0, count("idempotency_record"));
    }

    @Test void canonicalJsonAndActorTypeScopeWorkForReusableService() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var change = new WriteIntegrityService.Change("TEST_WRITE", "vehicle", 1001,
            Map.of(), Map.of("current_mileage", 20), Map.of("ok", true));
        var actor = new WriteIntegrityService.Actor("user", 1001);
        JsonNode first = integrity.execute(actor, "POST", "/api/test/write", key,
            mapper.readTree("{\"b\":2,\"a\":{\"y\":1,\"x\":0}}"), () -> {}, () -> { executions.incrementAndGet(); return change; });
        JsonNode replay = integrity.execute(actor, "POST", "/api/test/write", key,
            mapper.readTree("{\"a\":{\"x\":0,\"y\":1},\"b\":2}"), () -> {}, () -> { throw new AssertionError("Must not execute on replay"); });
        assertEquals(first, replay);
        integrity.execute(new WriteIntegrityService.Actor("staff_account", 1001), "POST", "/api/test/write", key,
            mapper.readTree("{}"), () -> {}, () -> change);
        assertEquals(1, executions.get());
        assertEquals(2, count("idempotency_record"));
    }
}
