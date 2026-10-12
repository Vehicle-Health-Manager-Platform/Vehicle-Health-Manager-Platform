package com.autocare.platform;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcArchiveReminderTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper = new ObjectMapper();
    ArchiveReminderService service;
    VehicleOwner owner, other;
    @BeforeAll static void schema() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) {
            for (String name : List.of("V001__baseline.sql", "V003__auth_lifecycle.sql", "V028__archive_reminders.sql", "V028__archive_reminders.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs/sql/migrations", name)));
        }
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reminder_audit");
        for (String table : List.of("archive_reminder", "vehicle_archive", "audit_log", "idempotency_record", "vehicle", "auth_session", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'reminder-one',1),(2,'reminder-two',1)");
        owner = new VehicleOwner(1, key(), Instant.now().plusSeconds(600)); other = new VehicleOwner(2, key(), Instant.now().plusSeconds(600));
        for (var actor : List.of(owner, other)) jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))", actor.session(), actor.id(), key());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES (101,1,999),(201,2,200)");
        jdbc.update("INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES "
            + "(11,101,1,3,'{}','2026-10-01'),(12,101,3,1,'{}','2026-10-01'),(13,101,7,3,'{}','2026-10-01'),"
            + "(14,101,1,3,'{}','2026-10-02'),(21,201,1,3,'{}','2026-10-01'),(31,101,2,4,'{}','2026-10-01')");
        var tx = new DataSourceTransactionManager(jdbc.getDataSource());
        service = new ArchiveReminderService(jdbc, new WriteIntegrityService(jdbc, mapper, tx), mapper, tx,
            Clock.fixed(Instant.parse("2026-10-11T16:00:00Z"), ZoneOffset.UTC));
    }
    String key() { return UUID.randomUUID().toString(); }
    ArchiveReminderInput input(String raw) throws java.io.IOException { return ArchiveReminderInput.parse(mapper.readTree(raw)); }
    ArchiveReminderInput target(int revision) throws java.io.IOException { return input("{\"expected_revision\":" + revision + ",\"active\":true,\"due_date\":\"2026-10-13\",\"due_mileage\":1000}"); }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value(); }
    com.fasterxml.jackson.databind.JsonNode row(int index) { return mapper.valueToTree(service.list(owner, 101)).path("list").get(index); }
    @Test void targetsAreStructuredAndAsiaShanghaiDateAndCurrentMileageTrigger() throws Exception {
        assertEquals("2026-10-12", service.list(owner, 101).get("today"));
        assertEquals("NOT_SET", row(0).path("status").asText());
        var target = target(0); String key = key(); var receipt = service.save(owner, 11, key, target);
        assertEquals(receipt, service.save(owner, 11, key, target)); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_reminder", Integer.class));
        assertEquals("PLANNED", row(0).path("status").asText());
        jdbc.update("UPDATE vehicle SET current_mileage=1000 WHERE id=101");
        assertEquals("DUE", row(0).path("status").asText()); assertEquals("MILEAGE", row(0).path("triggered_by").get(0).asText());
        jdbc.update("UPDATE vehicle SET current_mileage=NULL WHERE id=101");
        assertEquals("UNKNOWN", row(0).path("status").asText());
        service.save(owner, 12, key(), input("{\"expected_revision\":0,\"active\":true,\"due_date\":\"2026-10-12\"}"));
        assertEquals("DUE", row(1).path("status").asText());
        assertEquals(11, row(0).path("archive_id").asLong());
        assertEquals("{}", jdbc.queryForObject("SELECT content FROM vehicle_archive WHERE id=11", String.class));
    }
    @Test void sourcePermissionsDisabledAndInvalidSourcesFailClosed() throws Exception {
        var target = target(0);
        assertEquals(404, status(() -> service.save(other, 11, key(), target)));
        assertEquals(404, status(() -> service.context(owner, 21))); assertEquals(404, status(() -> service.context(owner, 31)));
        assertEquals(400, status(() -> service.save(owner, 12, key(), target)));
        service.save(owner, 11, key(), target);
        var disabled = input("{\"expected_revision\":1,\"active\":false}"); service.save(owner, 11, key(), disabled);
        assertEquals("INACTIVE", row(0).path("status").asText());
        service.save(owner, 11, key(), target(2));
        jdbc.update("UPDATE vehicle_archive SET is_deleted=1 WHERE id=11");
        assertEquals("UNAVAILABLE", row(0).path("status").asText()); assertTrue(row(0).path("archive_id").isNull()); assertTrue(row(0).path("due_date").isNull());
        assertEquals(404, status(() -> service.context(owner, 11)));
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101"); assertEquals(404, status(() -> service.list(owner, 101)));
        assertEquals("NOT_SET", mapper.valueToTree(service.list(other, 101)).path("list").get(0).path("status").asText());
    }
    @Test void revisionAndReplacementConfirmationProtectSourceAndAuditRollsBack() throws Exception {
        service.save(owner, 11, key(), target(0));
        var stale = target(0); assertEquals(409, status(() -> service.save(owner, 11, key(), stale)));
        var edit = target(1); assertEquals(409, status(() -> service.save(owner, 14, key(), edit)));
        var replacement = input("{\"expected_revision\":1,\"active\":true,\"due_date\":\"2026-10-14\",\"replace_source\":true}");
        service.save(owner, 14, key(), replacement); assertEquals(14, row(0).path("archive_id").asLong());
        jdbc.execute("CREATE TRIGGER reject_reminder_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit fail'");
        var disable = input("{\"expected_revision\":2,\"active\":false}");
        assertEquals(503, status(() -> service.save(owner, 14, key(), disable))); assertTrue(row(0).path("active").asBoolean()); assertEquals(2, row(0).path("revision").asInt());
    }
    @Test void concurrentSameVersionUpdatesHaveExactlyOneWinnerAndRevokedSessionsCannotReplay() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var target = target(0);
        try {
            var first = pool.submit(() -> { try { service.save(owner, 11, key(), target); return 200; } catch (ResponseStatusException e) { return e.getStatusCode().value(); } });
            var second = pool.submit(() -> { try { service.save(owner, 14, key(), target); return 200; } catch (ResponseStatusException e) { return e.getStatusCode().value(); } });
            var result = List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(result.contains(200)); assertTrue(result.contains(409));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_reminder", Integer.class));
        } finally { pool.shutdownNow(); }
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?", owner.session());
        assertEquals(401, status(() -> service.save(owner, 11, key(), target))); assertEquals(401, status(() -> service.list(owner, 101)));
    }
}
