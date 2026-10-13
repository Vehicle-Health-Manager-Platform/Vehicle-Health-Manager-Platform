package com.autocare.platform;

import com.autocare.platform.merchant.MerchantLocation;
import com.autocare.platform.service.*;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
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
class JdbcMerchantLocationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;final ObjectMapper mapper=new ObjectMapper();
    MerchantLocation location;NearbyMerchants nearby;MerchantActor manager,staff;VehicleOwner owner;
    @BeforeAll static void schema() throws Exception {
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()){for(String name:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V006__merchant_project_versions.sql","V019__experience_moderation.sql","V020__merchant_onboarding.sql","V032__merchant_location.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs/sql/migrations",name)));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_location_audit");
        for(String table:List.of("merchant_location","merchant_project_version","merchant_project","standard_project","audit_log","idempotency_record","auth_session","staff_account","merchant","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,region_code,status,lng,lat) VALUES (1,2,'合成门店一','地址一','440305',1,113,22),(2,2,'合成门店二','地址二','440305',1,113,22),(3,2,'旧坐标店','地址三','440306',1,113,22)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account) VALUES (1,1,'MERCHANT','manager'),(2,1,'STAFF','staff')");
        manager=actor(1,"MERCHANT");staff=actor(2,"STAFF");
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'nearby-owner',1)");owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session(),UUID.randomUUID().toString());
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status) VALUES(11,'测试项目',1,'测试',100,200,1)");
        for(int i=1;i<=3;i++){jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(?,?,11,?,1)",100+i,i,100-i);jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf) VALUES(?,?,1,?,1)",1000+i,100+i,100-i);}
        var tx=new DataSourceTransactionManager(jdbc.getDataSource());location=new MerchantLocation(jdbc,new WriteIntegrityService(jdbc,mapper,tx),mapper,true,tx);nearby=new NearbyMerchants(jdbc,new ServiceCatalog(jdbc,tx),tx);
    }
    MerchantActor actor(long id,String role){var a=new MerchantActor(id,1,role,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,?,'merchant-account',1,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",a.session(),id,role,UUID.randomUUID().toString());return a;}
    LocationInput.Store input(){return LocationInput.store("{\"lng\":\"113\",\"lat\":\"22\",\"coordinate_system\":\"GCJ02\",\"source\":\"MERCHANT_CONFIRMED\"}");}
    LocationInput.Nearby query(String sort,String region,int page,int size){return new LocationInput.Nearby(new LocationInput.Point(113,22),sort,region,page,size);}
    JsonNode list(String sort,String region,int page,int size){return mapper.valueToTree(nearby.list(owner,11,query(sort,region,page,size)));}
    int status(Runnable r){return assertThrows(ResponseStatusException.class,r::run).getStatusCode().value();}
    @Test void legacyCoordinatesAreNeverAdoptedAndMigrationReplaysWithoutChangingData() throws Exception {
        assertNull(location.read(manager).get("location"));assertTrue(list("distance_asc",null,1,20).path("items").get(0).path("distance_meters").isNull());
        location.update(manager,UUID.randomUUID().toString(),input());
        try(var c=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs/sql/migrations/V032__merchant_location.sql")));}
        assertEquals(1L,jdbc.queryForObject("SELECT version FROM merchant_location WHERE merchant_id=1",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_location",Integer.class));
    }
    @Test void confirmedDistanceSortUnknownLastAndDetailShareTheSameFormula(){
        location.update(manager,UUID.randomUUID().toString(),input());
        jdbc.update("INSERT INTO merchant_location(merchant_id,lng,lat,coordinate_system,source,version,actor_staff_id) VALUES(2,113,23,'GCJ02','MERCHANT_CONFIRMED',1,1)");
        var rows=list("distance_asc",null,1,20).path("items");assertEquals(1,rows.get(0).path("merchant_id").asInt());assertEquals(0,rows.get(0).path("distance_meters").asLong());
        assertEquals(2,rows.get(1).path("merchant_id").asInt());assertEquals(111195,rows.get(1).path("distance_meters").asLong());assertTrue(rows.get(2).path("distance_meters").isNull());
        assertEquals(rows.get(1).path("distance_meters").asLong(),nearby.distance(owner,2,new LocationInput.Point(113,22)).get("distance_meters"));
        assertEquals(9,rows.get(0).size());assertFalse(rows.toString().contains("coordinate_system"));assertFalse(rows.toString().contains("\"lng\""));
        assertEquals(2,list("distance_asc",null,2,1).path("items").get(0).path("merchant_id").asInt());
        assertEquals(3,list("price_asc",null,1,20).path("items").get(0).path("merchant_id").asInt());
        assertEquals(2,list("distance_asc","440305",1,20).path("total").asInt());assertEquals(0,list("distance_asc","110101",1,20).path("total").asInt());
    }
    @Test void idempotentWriteAndClearKeepVersionsAndRecheckRevocation(){
        String key=UUID.randomUUID().toString();var saved=location.update(manager,key,input());assertEquals(saved,location.update(manager,key,input()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
        location.update(manager,UUID.randomUUID().toString(),new LocationInput.Store(null,null));assertNull(location.read(manager).get("location"));assertNull(nearby.distance(owner,1,new LocationInput.Point(113,22)).get("distance_meters"));
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM merchant_location WHERE merchant_id=1",Long.class));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",manager.session());assertEquals(401,status(()->location.update(manager,key,input())));
    }
    @Test void staffReadButCannotWriteAndCrossStoreSessionIsRejected(){
        assertEquals(false,location.read(staff).get("can_edit"));assertEquals(403,status(()->location.update(staff,UUID.randomUUID().toString(),input())));
        var forged=new MerchantActor(manager.staffId(),2,manager.role(),manager.session(),manager.expires());assertEquals(401,status(()->location.read(forged)));
        jdbc.update("UPDATE merchant SET status=0 WHERE id=1");assertEquals(404,status(()->location.update(manager,UUID.randomUUID().toString(),input())));
        assertEquals(404,status(()->nearby.distance(owner,1,new LocationInput.Point(113,22))));
    }
    @Test void auditFailureRollsBackLocationAndCachedResponse(){
        jdbc.execute("CREATE TRIGGER reject_location_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        assertEquals(503,status(()->location.update(manager,UUID.randomUUID().toString(),input())));
        for(String table:List.of("merchant_location","idempotency_record","audit_log"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void inactiveOwnerOrProjectCannotQueryAndReadsNeverWriteLocationAudits(){
        nearby.distance(owner,1,new LocationInput.Point(113,22));list("distance_asc",null,1,20);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
        jdbc.update("UPDATE standard_project SET status=0 WHERE id=11");assertEquals(404,status(()->list("distance_asc",null,1,20)));
        jdbc.update("UPDATE user SET status=0 WHERE id=1");assertEquals(401,status(()->nearby.distance(owner,1,new LocationInput.Point(113,22))));
    }
    @Test void extremeCoordinatesAndEqualDistancePaginationRemainFiniteAndStable(){
        jdbc.update("INSERT INTO merchant_location(merchant_id,lng,lat,coordinate_system,source,version,actor_staff_id) VALUES(1,180,0,'GCJ02','MERCHANT_CONFIRMED',1,1),(2,180,0,'GCJ02','MERCHANT_CONFIRMED',1,1)");
        long antipodal=((Number)nearby.distance(owner,1,new LocationInput.Point(0,0)).get("distance_meters")).longValue();assertEquals(20015114,antipodal);
        assertEquals(0L,nearby.distance(owner,1,new LocationInput.Point(-180,0)).get("distance_meters"));
        assertEquals(1,list("distance_asc",null,1,1).path("items").get(0).path("merchant_id").asInt());
        assertEquals(2,list("distance_asc",null,2,1).path("items").get(0).path("merchant_id").asInt());
    }
    @Test void concurrentConfirmedWritesRemainSingleRowWithMonotonicVersion() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var a=pool.submit(()->{start.await();return location.update(manager,UUID.randomUUID().toString(),input());});
            var b=pool.submit(()->{start.await();return location.update(manager,UUID.randomUUID().toString(),new LocationInput.Store("114","23"));});start.countDown();
            a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_location",Integer.class));assertEquals(2L,jdbc.queryForObject("SELECT version FROM merchant_location",Long.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
        }finally{pool.shutdownNow();}
    }
}
