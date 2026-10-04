package com.autocare.platform;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcVehicleTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static ObjectMapper mapper = new ObjectMapper();
    VehicleService service;
    VehicleOwner owner, other;
    @BeforeAll static void schema() throws Exception {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        jdbc=new JdbcTemplate(source);
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations","V001__baseline.sql")));
            try(var paths=java.nio.file.Files.list(Path.of("..","docs","sql","migrations"))) {
                for(var path:paths.filter(p->p.getFileName().toString().startsWith("V003")).toList())
                    ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            }
        }
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_vehicle_audit");
        for(String table:new String[]{"audit_log","idempotency_record","vehicle","model","series","brand","auth_session","user"}) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'vehicle-test-one',1),(2,'vehicle-test-two',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        other=new VehicleOwner(2,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        for(var actor:new VehicleOwner[]{owner,other}) jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",actor.session(),actor.id(),UUID.randomUUID().toString());
        jdbc.update("INSERT INTO brand(id,name) VALUES (1,'测试品牌')");
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES (1,1,'测试车系')");
        jdbc.update("INSERT INTO model(id,series_id,year,config_name,power_type) VALUES (1,1,'2025','测试配置','燃油')");
        service=new VehicleService(jdbc,new WriteIntegrityService(jdbc,mapper,new DataSourceTransactionManager(jdbc.getDataSource())),mapper);
    }
    VehicleInput input(String plate) { return new VehicleInput(1,32000,plate,"LSVNV2180H2123456"); }
    String key() { return UUID.randomUUID().toString(); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value(); }
    @Test void createsListsAndMasksOnlyOwnVehicles() {
        var result=service.add(owner,key(),input("粤B12345"));
        assertTrue(result.path("data").path("need_archive").asBoolean());
        var list=mapper.valueToTree(service.list(owner,1,20));
        assertEquals(1,list.path("total").asInt());
        assertEquals("粤B****5",list.path("list").get(0).path("plate_no_masked").asText());
        assertFalse(list.toString().contains("LSVNV2180H2123456"));
        assertEquals(0L,service.list(other,1,20).get("total"));
        assertFalse(jdbc.queryForObject("SELECT after_state FROM audit_log",String.class).contains("粤B12345"));
        assertFalse(jdbc.queryForObject("SELECT response_body FROM idempotency_record",String.class).contains("LSVNV2180H2123456"));
    }
    @Test void sameKeyReplaysAfterCatalogRetirementButNotWithChangedPayload() {
        String key=key();var first=service.add(owner,key,input("粤B12345"));
        jdbc.update("UPDATE model SET is_deleted=1");
        assertEquals(first,service.add(owner,key.toUpperCase(),input("粤B12345")));
        assertEquals(400,status(()->service.add(owner,key,input("粤B54321"))));
        assertEquals(1,count("vehicle"));assertEquals(1,count("audit_log"));
    }
    @Test void duplicatePlateOrVinRejectedAndDifferentOwnerAllowed() {
        service.add(owner,key(),input("粤B12345"));
        assertEquals(409,status(()->service.add(owner,key(),new VehicleInput(1,0,"粤B12345",""))));
        assertEquals(409,status(()->service.add(owner,key(),input("粤B54321"))));
        service.add(other,key(),input("粤B12345"));assertEquals(2,count("vehicle"));
    }
    @Test void concurrentDifferentKeysCannotDuplicateSameOwnerVehicle() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var tasks=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{
                gate.await();try {service.add(owner,key(),input("粤B12345"));return 200;}
                catch(ResponseStatusException error){return error.getStatusCode().value();}
            })).toList();gate.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS)));
            assertEquals(1,count("vehicle"));assertEquals(1,count("audit_log"));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentSameKeyCommitsOneOriginalResponse() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);String key=key();
        try {
            var first=pool.submit(()->{gate.await();return service.add(owner,key,input("粤B12345"));});
            var second=pool.submit(()->{gate.await();return service.add(owner,key,input("粤B12345"));});gate.countDown();
            assertEquals(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));assertEquals(1,count("vehicle"));
        } finally {pool.shutdownNow();}
    }
    @Test void sessionRevocationBlocksReadsWritesAndReplay() {
        String key=key();service.add(owner,key,input("粤B12345"));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());
        assertEquals(401,status(()->service.add(owner,key,input("粤B12345"))));
        assertEquals(401,status(()->service.list(owner,1,20)));
        assertEquals(401,status(()->service.catalog(owner,"brand",0,1,20)));
    }
    @Test void foreignSessionAndDeletedUserCannotRead() {
        assertEquals(401,status(()->service.list(new VehicleOwner(owner.id(),other.session(),owner.expires()),1,20)));
        jdbc.update("UPDATE user SET is_deleted=1 WHERE id=1");assertEquals(401,status(()->service.list(owner,1,20)));
    }
    @Test void deletedCatalogAncestorsExcludeQueriesAndRejectAdd() {
        for(String table:new String[]{"model","series","brand"}) {
            jdbc.update("UPDATE "+table+" SET is_deleted=1");
            assertEquals(0L,service.catalog(owner,"model",1,1,20).get("total"));
            assertEquals(404,status(()->service.add(owner,key(),input("粤B12345"))));
            jdbc.update("UPDATE "+table+" SET is_deleted=0");
        }
        assertEquals(1L,service.catalog(owner,"brand",0,1,20).get("total"));
        assertEquals(0L,service.catalog(owner,"series",999,1,20).get("total"));
        jdbc.update("DELETE FROM model");assertEquals(0L,service.catalog(owner,"model",1,1,20).get("total"));
    }
    @Test void auditFailureRollsBackVehicleAndCachedResponse() {
        jdbc.execute("CREATE TRIGGER reject_vehicle_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");
        assertEquals(503,status(()->service.add(owner,key(),input("粤B12345"))));
        assertEquals(0,count("vehicle"));assertEquals(0,count("idempotency_record"));
    }
    @Test void paginatesAndExcludesDeletedVehicles() {
        service.add(owner,key(),new VehicleInput(1,0,"",""));service.add(owner,key(),new VehicleInput(1,1,"",""));
        var first=mapper.valueToTree(service.list(owner,1,1));var second=mapper.valueToTree(service.list(owner,2,1));
        assertEquals(2,first.path("total").asInt());
        assertTrue(first.path("list").get(0).path("vehicle_id").asLong()>second.path("list").get(0).path("vehicle_id").asLong());
        jdbc.update("UPDATE vehicle SET is_deleted=1 WHERE current_mileage=1");assertEquals(1L,service.list(owner,1,20).get("total"));
        assertEquals(400,status(()->service.list(owner,1,101)));
    }
    @Test void validatesFieldsAndNormalizesIdentifiersBeforeHashing() throws Exception {
        var parsed=VehicleInput.parse(mapper.readTree("{\"add_type\":4,\"model_id\":1,\"plate_no\":\" 粤b12345 \",\"vin\":\"lsvnv2180h2123456\"}"));
        assertEquals("粤B12345",parsed.plate());assertEquals(0,parsed.mileage());
        for(String body:new String[]{"{}","{\"add_type\":18446744073709551620,\"model_id\":1}",
            "{\"add_type\":4,\"model_id\":1,\"user_id\":2}","{\"add_type\":4,\"model_id\":1,\"current_mileage\":2147483648}",
            "{\"add_type\":4,\"model_id\":1,\"vin\":\"INVALID\"}","{\"add_type\":4,\"model_id\":0}"}) {
            var node=mapper.readTree(body);assertEquals(400,status(()->VehicleInput.parse(node)));
        }
        String key=key();assertEquals(service.add(owner,key,parsed),service.add(owner,key,new VehicleInput(1,0,"粤B12345","LSVNV2180H2123456")));
    }
}
