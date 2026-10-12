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
class JdbcArchiveStructuredTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper = new ObjectMapper();
    ArchiveStructuredService service;
    VehicleOwner owner, other;
    @BeforeAll static void schema() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) {
            for (String name : List.of("V001__baseline.sql", "V003__auth_lifecycle.sql", "V029__archive_structured_details.sql", "V029__archive_structured_details.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs/sql/migrations", name)));
        }
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reminder_audit");
        for (String table : List.of("archive_structured_detail", "vehicle_archive", "audit_log", "idempotency_record", "vehicle", "auth_session", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'reminder-one',1),(2,'reminder-two',1)");
        owner = new VehicleOwner(1, key(), Instant.now().plusSeconds(600)); other = new VehicleOwner(2, key(), Instant.now().plusSeconds(600));
        for (var actor : List.of(owner, other)) jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))", actor.session(), actor.id(), key());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES (101,1,999),(201,2,200)");
        jdbc.update("INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES "
            + "(11,101,1,3,'{}','2026-10-01'),(12,101,3,1,'{}','2026-10-01'),(13,101,7,3,'{}','2026-10-01'),"
            + "(14,101,1,3,'{}','2026-10-02'),(21,201,1,3,'{}','2026-10-01'),(31,101,2,4,'{}','2026-10-01')");
        var tx = new DataSourceTransactionManager(jdbc.getDataSource());
        service = new ArchiveStructuredService(jdbc, new WriteIntegrityService(jdbc, mapper, tx), mapper, tx);
    }
    String key() { return UUID.randomUUID().toString(); }
    ArchiveStructuredInput input(String raw) throws java.io.IOException { return ArchiveStructuredInput.parse(mapper.readTree(raw)); }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value(); }

    @Test void fieldsPersistReplayClearAndDoNotChangeLegacyContent() throws Exception {
        var input=input("{\"expected_revision\":0,\"fields\":{\"provider_name\":\"本人填写单位\",\"service_items\":[\"机油\"],\"performed_mileage\":1234}}");
        String key=key(); var receipt=service.save(owner,11,key,input);
        assertEquals(receipt,service.save(owner,11,key,input));
        var row=mapper.valueToTree(service.context(owner,11));
        assertEquals(1234,row.path("fields").path("performed_mileage").asInt());
        assertEquals("OWNER_ENTERED_UNVERIFIED",row.path("status").asText());
        assertEquals("{}",jdbc.queryForObject("SELECT content FROM vehicle_archive WHERE id=11",String.class));
        assertEquals(999,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=101",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT after_state FROM audit_log",String.class).contains("本人填写单位"));
        assertFalse(jdbc.queryForObject("SELECT response_body FROM idempotency_record",String.class).contains("本人填写单位"));
        service.save(owner,11,key(),input("{\"expected_revision\":1,\"fields\":{}}"));
        var clear=mapper.valueToTree(service.context(owner,11));
        assertEquals(2,clear.path("revision").asInt()); assertEquals("NOT_SET",clear.path("status").asText());
        assertTrue(clear.path("fields").isEmpty());
    }
    @Test void permissionsSourceAndOwnershipAreRevalidatedEvenForReplay() throws Exception {
        var input=input("{\"expected_revision\":0,\"fields\":{\"insurer_name\":\"个人保险公司\",\"coverage_end\":\"2027-01-01\"}}");
        assertEquals(404,status(()->service.context(other,12)));
        assertEquals(404,status(()->service.context(owner,31)));
        assertEquals(400,status(()->service.save(owner,11,key(),input)));
        String key=key();service.save(owner,12,key,input);
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");
        assertEquals(404,status(()->service.save(owner,12,key,input)));
        assertTrue(mapper.valueToTree(service.context(other,12)).path("fields").isEmpty());
        jdbc.update("UPDATE vehicle_archive SET is_deleted=1 WHERE id=12");
        assertEquals(404,status(()->service.context(other,12)));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",other.session());
        assertEquals(401,status(()->service.context(other,13)));
    }
    @Test void optimisticVersionAndAuditRollbackProtectPrivateData() throws Exception {
        var first=input("{\"expected_revision\":0,\"fields\":{\"inspection_result\":\"UNKNOWN\"}}");
        service.save(owner,13,key(),first);
        assertEquals(409,status(()->service.save(owner,13,key(),first)));
        jdbc.execute("CREATE TRIGGER reject_reminder_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit fail'");
        var clear=input("{\"expected_revision\":1,\"fields\":{}}");
        assertEquals(503,status(()->service.save(owner,13,key(),clear)));
        assertEquals(1,mapper.valueToTree(service.context(owner,13)).path("revision").asInt());
    }
    @Test void concurrentSameVersionHasOneWinner() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var input=input("{\"expected_revision\":0,\"fields\":{\"provider_name\":\"本人填写\"}}");
        try {
            var calls=pool.invokeAll(List.of(()->{try{service.save(owner,11,key(),input);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}},
                ()->{try{service.save(owner,11,key(),input);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            var results=List.of(calls.get(0).get(),calls.get(1).get()); assertTrue(results.contains(200));assertTrue(results.contains(409));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM archive_structured_detail",Integer.class));
        } finally {pool.shutdownNow();}
    }
}
