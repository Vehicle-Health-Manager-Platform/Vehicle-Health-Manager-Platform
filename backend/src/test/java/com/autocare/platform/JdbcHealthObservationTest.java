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
class JdbcHealthObservationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper = new ObjectMapper();
    HealthObservationService service;
    VehicleOwner owner, other;
    @BeforeAll static void schema() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) {
            for (String name : List.of("V001__baseline.sql", "V003__auth_lifecycle.sql", "V030__vehicle_health_observations.sql", "V030__vehicle_health_observations.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs/sql/migrations", name)));
        }
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reminder_audit");
        for (String table : List.of("vehicle_health_observation_history", "vehicle_health_observation", "vehicle_archive", "audit_log", "idempotency_record", "vehicle", "auth_session", "user")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'reminder-one',1),(2,'reminder-two',1)");
        owner = new VehicleOwner(1, key(), Instant.now().plusSeconds(600)); other = new VehicleOwner(2, key(), Instant.now().plusSeconds(600));
        for (var actor : List.of(owner, other)) jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))", actor.session(), actor.id(), key());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES (101,1,999),(201,2,200)");
        jdbc.update("INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES "
            + "(11,101,1,3,'{}','2026-10-01'),(12,101,3,1,'{}','2026-10-01'),(13,101,7,3,'{}','2026-10-01'),"
            + "(14,101,2,3,'{}','2026-10-02'),(21,201,1,3,'{}','2026-10-01'),(31,101,2,4,'{}','2026-10-01')");
        var tx = new DataSourceTransactionManager(jdbc.getDataSource());
        service = new HealthObservationService(jdbc, new WriteIntegrityService(jdbc, mapper, tx), mapper, tx, Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
    }
    String key() { return UUID.randomUUID().toString(); }
    HealthObservationInput input(String raw) throws java.io.IOException { return HealthObservationInput.parse(mapper.readTree(raw)); }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value(); }


    HealthObservationInput reading(String metric,int version,boolean replace) throws Exception {
        return input("{\"metric\":\""+metric+"\",\"expected_revision\":"+version+",\"replace_source\":"+replace+",\"measurement\":{\"measurement_name\":\"本人读数\",\"value\":\"12.50\",\"unit\":\"V\",\"measured_on\":\"2026-10-12\",\"method\":\"本人仪表记录\",\"provenance\":\"OWNER_MEASURED\"}}");
    }
    com.fasterxml.jackson.databind.JsonNode row(VehicleOwner actor,long vehicle,int index){return mapper.valueToTree(service.list(actor,vehicle)).path("list").get(index);}
    @Test void fixedSixSlotsPersistReplayAndClearWithoutLegacyChanges() throws Exception {
        assertEquals(6,mapper.valueToTree(service.list(owner,101)).path("list").size());
        assertEquals("OIL",row(owner,101,0).path("metric").asText());assertEquals("NOT_SET",row(owner,101,5).path("status").asText());
        var input=reading("AUX_BATTERY",0,false);String key=key();var receipt=service.save(owner,11,key,input);
        assertEquals(receipt,service.save(owner,11,key,input));assertEquals("12.50",row(owner,101,5).path("measurement").path("value").asText());
        assertEquals("RECORDED_UNVERIFIED",row(owner,101,5).path("status").asText());
        assertEquals("{}",jdbc.queryForObject("SELECT content FROM vehicle_archive WHERE id=11",String.class));
        assertFalse(jdbc.queryForObject("SELECT after_state FROM audit_log",String.class).contains("仪表"));
        assertFalse(jdbc.queryForObject("SELECT response_body FROM idempotency_record",String.class).contains("12.50"));
        service.save(owner,11,key(),input("{\"metric\":\"AUX_BATTERY\",\"expected_revision\":1,\"measurement\":{}}"));
        assertEquals("NOT_SET",row(owner,101,5).path("status").asText());assertEquals(2,row(owner,101,5).path("revision").asInt());
        assertTrue(row(owner,101,5).path("measurement").isEmpty());
    }
    @Test void sourceAndOwnerChangesRedactOrRejectIncludingReplays() throws Exception {
        assertEquals(404,status(()->service.context(owner,12,"OIL")));assertEquals(404,status(()->service.context(other,11,"OIL")));
        var input=reading("AUX_BATTERY",0,false);String key=key();service.save(owner,11,key,input);
        jdbc.update("UPDATE vehicle_archive SET is_deleted=1 WHERE id=11");
        assertEquals("SOURCE_UNAVAILABLE",row(owner,101,5).path("status").asText());assertTrue(row(owner,101,5).path("archive_id").isNull());assertTrue(row(owner,101,5).path("measurement").isEmpty());
        assertEquals(404,status(()->service.save(owner,11,key,input)));
        jdbc.update("UPDATE vehicle_archive SET is_deleted=0 WHERE id=11");jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");
        assertEquals(404,status(()->service.save(owner,11,key,input)));assertEquals("NOT_SET",row(other,101,5).path("status").asText());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",other.session());assertEquals(401,status(()->service.list(other,101)));
    }
    @Test void sourceReplacementNeedsConfirmationAndAuditFailureRollsBack() throws Exception {
        service.save(owner,11,key(),reading("AUX_BATTERY",0,false));
        var replace=reading("AUX_BATTERY",1,false);assertEquals(409,status(()->service.save(owner,14,key(),replace)));
        service.save(owner,14,key(),reading("AUX_BATTERY",1,true));assertEquals(14,row(owner,101,5).path("archive_id").asLong());
        jdbc.execute("CREATE TRIGGER reject_reminder_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit fail'");
        var clear=input("{\"metric\":\"AUX_BATTERY\",\"expected_revision\":2,\"measurement\":{}}");
        assertEquals(503,status(()->service.save(owner,14,key(),clear)));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_health_observation_history",Integer.class));assertEquals(2,row(owner,101,5).path("revision").asInt());assertFalse(row(owner,101,5).path("measurement").isEmpty());
    }
    @Test void versionRaceHasOneWinnerAndDifferentMetricsAreIndependent() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var input=reading("AUX_BATTERY",0,false);
        try{
            var calls=pool.invokeAll(List.of(()->{try{service.save(owner,11,key(),input);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}},
                ()->{try{service.save(owner,14,key(),input);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            var results=List.of(calls.get(0).get(),calls.get(1).get());assertTrue(results.contains(200));assertTrue(results.contains(409));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_health_observation_history",Integer.class));
            service.save(owner,11,key(),reading("TRACTION_BATTERY",0,false));assertEquals(1,row(owner,101,3).path("revision").asInt());
        }finally{pool.shutdownNow();}
    }

    com.fasterxml.jackson.databind.JsonNode history(VehicleOwner actor,Integer before,int limit){return mapper.valueToTree(service.history(actor,101,"AUX_BATTERY",before,limit));}
    HealthObservationInput correction(int version,String value) throws Exception {
        var node=mapper.valueToTree(reading("AUX_BATTERY",version,false).canonical());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("operation","CORRECT");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("measurement")).put("value",value);
        return HealthObservationInput.parse(node);
    }
    @Test void immutableCorrectionChainClearAndCursorPagination() throws Exception {
        var first=reading("AUX_BATTERY",0,false);String originalKey=key();service.save(owner,11,originalKey,first);
        var correction=correction(1,"12.6");String correctionKey=key();var receipt=service.save(owner,11,correctionKey,correction);
        assertEquals(receipt,service.save(owner,11,correctionKey,correction));
        service.save(owner,11,key(),correction(2,"12.7"));
        service.save(owner,11,key(),input("{\"metric\":\"AUX_BATTERY\",\"expected_revision\":3,\"measurement\":{}}"));
        var page=history(owner,null,2);assertTrue(page.path("has_more").asBoolean());assertEquals(3,page.path("next_before_revision").asInt());
        assertEquals("CLEAR",page.path("list").get(0).path("operation").asText());assertTrue(page.path("list").get(0).path("measurement").isEmpty());
        var older=history(owner,3,2);assertFalse(older.path("has_more").asBoolean());assertTrue(older.path("next_before_revision").isNull());
        assertEquals("12.50",older.path("list").get(1).path("measurement").path("value").asText());
        assertTrue(older.path("list").get(1).path("corrected").asBoolean());assertTrue(older.path("list").get(0).path("corrected").asBoolean());
        assertEquals(1,older.path("list").get(0).path("supersedes_revision").asInt());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_health_observation_history",Integer.class));
        assertEquals(409,status(()->service.save(owner,11,key(),correction)));
        var afterClear=correction(4,"12.8");assertEquals(409,status(()->service.save(owner,11,key(),afterClear)));
        var repeatedClear=input("{\"metric\":\"AUX_BATTERY\",\"expected_revision\":4,\"measurement\":{}}");assertEquals(409,status(()->service.save(owner,11,key(),repeatedClear)));
        assertEquals(400,status(()->service.history(owner,101,"AUX_BATTERY",0,20)));assertEquals(400,status(()->service.history(owner,101,"AUX_BATTERY",null,51)));
        assertTrue(history(owner,1,20).path("list").isEmpty());
    }
    @Test void historyAlwaysRechecksSourcesAndNeverPassesToNewOwner() throws Exception {
        service.save(owner,11,key(),reading("AUX_BATTERY",0,false));var correction=correction(1,"13");
        assertEquals(409,status(()->service.save(owner,14,key(),correction)));
        service.save(owner,14,key(),reading("AUX_BATTERY",1,true));
        jdbc.update("UPDATE vehicle_archive SET is_deleted=1 WHERE id=11");
        var old=history(owner,null,20).path("list").get(1);assertEquals("SOURCE_UNAVAILABLE",old.path("status").asText());
        assertTrue(old.path("archive_id").isNull());assertTrue(old.path("recorded_date").isNull());assertTrue(old.path("measurement").isEmpty());
        jdbc.update("UPDATE vehicle_archive SET archive_type=1 WHERE id=14");
        var changedType=correction(2,"13");assertEquals(409,status(()->service.save(owner,14,key(),changedType)));
        assertTrue(history(owner,null,20).path("list").get(0).path("measurement").isEmpty());
        jdbc.update("UPDATE vehicle_archive SET archive_type=2,vehicle_id=201 WHERE id=14");assertTrue(history(owner,null,20).path("list").get(0).path("measurement").isEmpty());
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");assertEquals(404,status(()->service.history(owner,101,"AUX_BATTERY",null,20)));
        assertTrue(history(other,null,20).path("list").isEmpty());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",other.session());assertEquals(401,status(()->service.history(other,101,"AUX_BATTERY",null,20)));
    }
    @Test void migrationAddsOnlyOneHonestCurrentSnapshotAndCanReplay() throws Exception {
        jdbc.update("INSERT INTO vehicle_health_observation(user_id,vehicle_id,metric,archive_id,archive_type,measurement_json,revision) VALUES (1,101,'AUX_BATTERY',11,1,?,9)",mapper.writeValueAsString(reading("AUX_BATTERY",0,false).measurement()));
        try(var connection=jdbc.getDataSource().getConnection()){
            for(int i=0;i<2;i++)ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations/V031__vehicle_health_observation_history.sql")));
        }
        var page=history(owner,null,20);assertEquals(1,page.path("list").size());assertEquals("MIGRATED_SNAPSHOT",page.path("list").get(0).path("operation").asText());
        assertEquals(9,page.path("list").get(0).path("revision").asInt());assertTrue(page.path("list").get(0).path("supersedes_revision").isNull());
        service.save(owner,11,key(),correction(9,"13"));assertEquals(2,history(owner,null,20).path("list").size());
    }
}
