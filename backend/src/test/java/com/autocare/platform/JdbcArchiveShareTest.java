package com.autocare.platform;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
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
class JdbcArchiveShareTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static final ObjectMapper mapper = new ObjectMapper();
    ArchiveShareService service;
    VehicleOwner owner, recipient;
    @BeforeAll static void schema() throws Exception {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            for (String name : List.of("V001__baseline.sql", "V003__auth_lifecycle.sql", "V005__vehicle_archive_files.sql", "V027__archive_report_shares.sql", "V027__archive_report_shares.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs/sql/migrations", name)));
        }
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_share_audit");
        for (String table : List.of("archive_report_share", "vehicle_archive", "audit_log", "idempotency_record", "vehicle", "auth_session", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'share-owner',1),(2,'share-recipient',1)");
        owner = new VehicleOwner(1, UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        recipient = new VehicleOwner(2, UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        for (var user : List.of(owner, recipient)) jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))", user.session(), user.id(), UUID.randomUUID().toString());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage,plate_no,vin) VALUES (101,1,100,'粤B12345','secret-vin'),(201,2,200,'粤B22222','other-vin')");
        jdbc.update("INSERT INTO vehicle_archive(vehicle_id,archive_type,input_type,content,recorded_at) VALUES (101,1,3,?,UTC_TIMESTAMP())", "{\"title\":\"客户13800138000\",\"notes\":\"secret\",\"mileage\":120}");
        service = service("a".repeat(32));
    }
    ArchiveShareService service(String secret) {
        var tx = new DataSourceTransactionManager(jdbc.getDataSource());
        return new ArchiveShareService(jdbc, new WriteIntegrityService(jdbc, mapper, tx), new ArchiveReportService(jdbc, mapper, tx), mapper, tx, secret);
    }
    ArchiveShareInput input() throws java.io.IOException { return ArchiveShareInput.parse(mapper.readTree("{\"vehicle_id\":101}")); }
    String key() { return UUID.randomUUID().toString(); }
    long create() throws Exception { return service.create(owner, key(), input()).path("data").path("share_id").asLong(); }
    String token(long id) { return service.link(owner, id).get("path").toString().split("token=")[1]; }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value(); }
    @Test void idempotentCreationFrozenSummaryRevocationAndNoRawTokenPersistence() throws Exception {
        String key = key(); var receipt = service.create(owner, key, input()); long id = receipt.path("data").path("share_id").asLong();
        assertEquals(receipt, service.create(owner, key, input()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_report_share", Integer.class));
        String token = token(id);
        jdbc.update("INSERT INTO vehicle_archive(vehicle_id,archive_type,input_type,content,recorded_at) VALUES (101,2,3,'{}',UTC_TIMESTAMP())");
        var data = mapper.valueToTree(service.view(recipient, token)); assertEquals(1, data.path("summary").path("entries").size());
        assertFalse(data.toString().contains("secret")); assertFalse(data.toString().contains("13800138000"));
        assertFalse(receipt.toString().contains(token));
        for (String table : List.of("audit_log", "idempotency_record", "archive_report_share")) {
            String dump = jdbc.queryForList("SELECT * FROM " + table).toString(); assertFalse(dump.contains(token));
        }
        assertEquals(404, status(() -> service.revoke(recipient, id, key(), mapper.createObjectNode())));
        assertEquals(404, status(() -> service.link(recipient, id)));
        assertEquals(404, status(() -> service.list(recipient, 101)));
        String revokeKey = key(); var revoked = service.revoke(owner, id, revokeKey, mapper.createObjectNode());
        assertEquals("REVOKED", revoked.path("data").path("status").asText());
        assertEquals(revoked, service.revoke(owner, id, revokeKey, mapper.createObjectNode()));
        assertEquals(410, status(() -> service.view(recipient, token)));
        assertEquals(410, status(() -> service.link(owner, id)));
    }
    @Test void expiryAccountVehicleSessionAndSecretRotationFailClosed() throws Exception {
        long id = create(); String token = token(id);
        assertEquals(410, status(() -> service("b".repeat(32)).view(recipient, token)));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?", recipient.session());
        assertEquals(401, status(() -> service.view(recipient, token)));
        jdbc.update("UPDATE auth_session SET revoked_at=NULL WHERE id=?", recipient.session());
        jdbc.update("UPDATE user SET status=0 WHERE id=1"); assertEquals(410, status(() -> service.view(recipient, token)));
        jdbc.update("UPDATE user SET status=1 WHERE id=1");
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101"); assertEquals(410, status(() -> service.view(recipient, token)));
        jdbc.update("UPDATE vehicle SET user_id=1 WHERE id=101");
        jdbc.update("UPDATE archive_report_share SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?", id);
        assertEquals(410, status(() -> service.view(recipient, token)));
        assertEquals("EXPIRED", mapper.valueToTree(service.list(owner, 101)).path("list").get(0).path("status").asText());
    }
    @Test void auditFailureRollsBackCreateRevokeAndRead() throws Exception {
        long id = create(); String token = token(id);
        jdbc.execute("CREATE TRIGGER reject_share_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit fail'");
        assertEquals(503, status(() -> { try { service.create(owner, key(), input()); } catch (java.io.IOException e) { throw new RuntimeException(e); } }));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_report_share", Integer.class));
        assertEquals(503, status(() -> service.revoke(owner, id, key(), mapper.createObjectNode())));
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM archive_report_share WHERE id=?", java.sql.Timestamp.class, id));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.view(recipient, token));
    }
    @Test void concurrentIdenticalRequestsCreateExactlyOneGrantAndChangedPayloadCannotReplay() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String key = key(); var input = input();
        try {
            var one = pool.submit(() -> service.create(owner, key, input));
            var two = pool.submit(() -> service.create(owner, key, input));
            assertEquals(one.get(20, java.util.concurrent.TimeUnit.SECONDS), two.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_report_share", Integer.class));
            var changed = ArchiveShareInput.parse(mapper.readTree("{\"vehicle_id\":101,\"expires_in_hours\":1}"));
            assertEquals(400, status(() -> service.create(owner, key, changed)));
        } finally { pool.shutdownNow(); }
    }
    @Test void readWaitsForRevokeCommitAndThenRejects() throws Exception {
        long id = create(); String token = token(id);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("UPDATE archive_report_share SET revoked_at=UTC_TIMESTAMP() WHERE id=?")) {
                statement.setLong(1, id); statement.executeUpdate();
            }
            var started = new java.util.concurrent.CountDownLatch(1);
            var reading = pool.submit(() -> { started.countDown(); return status(() -> service.view(recipient, token)); });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> reading.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            connection.commit();
            assertEquals(410, reading.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
