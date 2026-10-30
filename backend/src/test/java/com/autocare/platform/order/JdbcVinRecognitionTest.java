package com.autocare.platform.order;

import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.vehicle.vin.VinDecoder;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated real MySQL; VINs, identity and supplier output are synthetic. */
@Testcontainers
class JdbcVinRecognitionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    static final String VIN="LTEST123456789012";
    static final String RAW="{\"vin\":\""+VIN+"\",\"consent\":true,\"consent_version\":\"VIN_VPIC_V1\"}";
    ObjectMapper mapper=new ObjectMapper();
    VehicleOwner owner; VinRecognition service; int calls;
    static final VinDecoder.Result ANSWER=new VinDecoder.Result(VinDecoder.Status.CANDIDATE,"NHTSA_VPIC",Map.of("make","Synthetic","model","Test","model_year","2026"),true);
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()){for(String name:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V022__catalog_governance.sql"))ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations",name)));}
    }
    ReservationStore db(){var tx=new DataSourceTransactionManager(jdbc.getDataSource());return new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,tx),Clock.systemUTC(),tx);}
    @BeforeEach void setup(){
        for(String table:List.of("auth_rate_limit","auth_session","vehicle","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'vin-one',1),(2,'vin-two',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','vin-test','synthetic',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(101,1,100)");
        calls=0; service=new VinRecognition(db(),(vin,year,consent)->{calls++;assertEquals(VIN,vin);assertTrue(consent);return ANSWER;},true);
    }
    int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    JsonNode run(){return service.recognize(owner,VinRecognition.input(RAW));}
    @Test void localSourceExactCatalogCandidateRechecksAvailabilityAndConsent()throws Exception {
        Path fixture=Path.of("../docs/testing/fixtures/vin-local-synthetic.json");
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(fixture)));
        service=new VinRecognition(db(),new com.autocare.platform.vehicle.vin.LocalVinDecoder(fixture,sha,true),true);
        assertEquals(400,status(this::run));
        var input=VinRecognition.input(RAW.replace("VIN_VPIC_V1","VIN_LOCAL_V1"));
        jdbc.update("INSERT INTO brand(id,name) VALUES(901,'合成测试品牌')");
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES(901,901,'合成测试车系')");
        jdbc.update("INSERT INTO model(id,series_id,year,config_name) VALUES(901,901,'2026','合成配置A')");
        try {
            var result=service.recognize(owner,input);assertEquals("LOCAL_SYNTHETIC",result.path("source").asText());assertTrue(result.path("synthetic").asBoolean());
            assertEquals(901,result.path("catalog_candidates").get(0).path("model_id").asInt());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));
            jdbc.update("UPDATE model SET status=0 WHERE id=901");assertTrue(service.recognize(owner,input).path("catalog_candidates").isEmpty());
            jdbc.update("UPDATE model SET status=1,config_name='合成配置a' WHERE id=901");assertTrue(service.recognize(owner,input).path("catalog_candidates").isEmpty());
            jdbc.update("UPDATE model SET config_name='合成配置A' WHERE id=901");jdbc.update("INSERT INTO model(id,series_id,year,config_name) VALUES(902,901,'2026','合成配置A')");
            assertTrue(service.recognize(owner,input).path("catalog_candidates").isEmpty());
        } finally {jdbc.update("DELETE FROM model WHERE series_id=901");jdbc.update("DELETE FROM series WHERE id=901");jdbc.update("DELETE FROM brand WHERE id=901");}
    }
    @Test void canonical_input_and_optional_integer_year(){
        assertEquals(VIN,VinRecognition.input(RAW.replace(VIN," "+VIN.toLowerCase(Locale.ROOT)+" ")).vin());
        assertNull(VinRecognition.input(RAW).modelYear());
        assertEquals(2026,VinRecognition.input(RAW.replace("}",",\"model_year\":2026}")).modelYear());
    }
    @Test void local_source_revoked_after_decode_returns_safe_503_and_recovers(){
        var input=VinRecognition.input(RAW.replace("VIN_VPIC_V1","VIN_LOCAL_V1"));
        var before=jdbc.queryForList("SELECT * FROM vehicle");
        for(RuntimeException failure:List.of(new VinDecoder.Unavailable(),new IllegalStateException("private-license-path "+VIN))){
            var decoder=org.mockito.Mockito.mock(com.autocare.platform.vehicle.vin.LocalVinDecoder.class);
            var answer=new VinDecoder.Result(VinDecoder.Status.CANDIDATE,"LOCAL_SYNTHETIC",
                Map.of("make","Synthetic","model","Test","model_year","2026","trim","Test trim"),true);
            org.mockito.Mockito.when(decoder.decode(VIN,null,true)).thenReturn(answer);
            // Simulate revocation precisely between decode and the second source
            // read, without sleeps or a filesystem timing race.
            org.mockito.Mockito.when(decoder.evidence()).thenThrow(failure)
                .thenReturn(new com.autocare.platform.vehicle.vin.LocalVinDecoder.Evidence("test-revision",true));
            service=new VinRecognition(db(),decoder,true);
            var error=assertThrows(ResponseStatusException.class,()->service.recognize(owner,input));
            assertEquals(503,error.getStatusCode().value());assertNull(error.getCause());
            assertEquals("VIN解码未配置或暂不可用，请手动确认车型",error.getReason());
            assertFalse(error.toString().contains(VIN));assertFalse(error.toString().contains("private-license-path"));
            assertEquals(before,jdbc.queryForList("SELECT * FROM vehicle"));
            for(String table:List.of("vehicle_archive","audit_log","idempotency_record"))
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            // The failed metadata read must also release the admission permit.
            var recovered=service.recognize(owner,input);assertEquals("test-revision",recovered.path("source_revision").asText());
            org.mockito.Mockito.verify(decoder,org.mockito.Mockito.times(2)).decode(VIN,null,true);
            org.mockito.Mockito.verify(decoder,org.mockito.Mockito.times(2)).evidence();
        }
    }
    @Test void malformed_duplicate_extra_consent_and_year_refused_before_io(){
        for(String raw:List.of("{}",RAW+"{}",RAW.replace("true","false"),RAW.replace("VIN_VPIC_V1","OTHER"),RAW.replace(VIN,"I".repeat(17)),RAW.replace(VIN,"A".repeat(15)+"ß"),RAW.replace("}",",\"vin\":\""+VIN+"\"}"),RAW.replace("}",",\"url\":\"x\"}"),RAW.replace("}",",\"model_year\":null}"),RAW.replace("}",",\"model_year\":2026.0}"),RAW.replace("}",",\"model_year\":1979}"),RAW.replace("}",",\"model_year\":2101}"),RAW.replace("}",",\"model_year\":4294967297}")))assertEquals(400,status(()->VinRecognition.input(raw)));
        assertEquals(0,calls);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit",Integer.class));
    }
    @Test void candidate_does_not_write_vehicle_archive_or_business_audit(){
        var before=jdbc.queryForList("SELECT * FROM vehicle");var result=run();
        assertEquals(1,calls);assertEquals(VIN,result.path("vin").asText());assertTrue(result.path("model_year_hint").isNull());
        assertTrue(result.path("requires_confirmation").asBoolean());assertEquals("US_MARKET_REGISTRATION_ONLY",result.path("coverage").asText());assertFalse(result.has("model_id"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM vehicle"));
        for(String table:List.of("vehicle_archive","audit_log","idempotency_record"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertFalse(jdbc.queryForList("SELECT * FROM auth_rate_limit").toString().contains(VIN));
    }
    @Test void disabled_has_no_provider_or_admission_write(){
        service=new VinRecognition(db(),(v,y,c)->{fail();return null;},false);assertEquals(503,status(this::run));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit",Integer.class));
        assertThrows(IllegalArgumentException.class,()->new VinRecognitionConfiguration().vinDecoder(true,"unconfigured"));
    }
    @Test void revoked_disabled_deleted_expired_or_foreign_session_has_zero_io(){
        for(String mutation:List.of("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()","UPDATE user SET status=0 WHERE id=1","UPDATE user SET is_deleted=1 WHERE id=1","UPDATE auth_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)")){
            jdbc.update("UPDATE user SET status=1,is_deleted=0");jdbc.update("UPDATE auth_session SET revoked_at=NULL,expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)");jdbc.update(mutation);assertEquals(401,status(this::run));
        }
        owner=new VehicleOwner(2,owner.session(),Instant.now().plusSeconds(600));assertEquals(401,status(this::run));assertEquals(0,calls);
    }
    @Test void five_per_minute_is_shared_across_service_instances_and_never_contains_vin(){
        // Preload the current and next window to make the boundary deterministic.
        var now=Instant.now();var start=Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(),60)*60);
        String hash=com.autocare.platform.gateway.identity.AuthTokens.sha256("owner:1");
        for(int offset:List.of(0,60))jdbc.update("INSERT INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('vin_decode',?,?,5)",hash,ReservationStore.time(start.plusSeconds(offset)));
        assertEquals(429,status(this::run));service=new VinRecognition(db(),(v,y,c)->{fail();return null;},true);assertEquals(429,status(this::run));assertEquals(0,calls);
        assertTrue(jdbc.queryForList("SELECT attempts FROM auth_rate_limit").stream().allMatch(r->((Number)r.get("attempts")).intValue()==5));
    }
    @Test void expired_own_admission_rows_are_removed_without_touching_other_scopes(){
        String hash=com.autocare.platform.gateway.identity.AuthTokens.sha256("owner:1");
        jdbc.update("INSERT INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('vin_decode',?,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 DAY),1),('other',?,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 DAY),1)",hash,hash);
        run();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit WHERE scope='vin_decode'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit WHERE scope='other'",Integer.class));
    }
    @Test void revoke_during_success_or_failure_and_disable_during_io_withhold_result(){
        for(boolean failure:List.of(false,true)){
            jdbc.update("UPDATE auth_session SET revoked_at=NULL");
            service=new VinRecognition(db(),(v,y,c)->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");if(failure)throw new IllegalStateException("secret "+VIN);return ANSWER;},true);
            assertEquals(401,status(this::run));
        }
        jdbc.update("UPDATE auth_session SET revoked_at=NULL");service=new VinRecognition(db(),(v,y,c)->{jdbc.update("UPDATE user SET status=0 WHERE id=1");return ANSWER;},true);assertEquals(401,status(this::run));
    }
    @Test void provider_errors_null_wrong_source_and_year_conflicts_are_safe(){
        for(VinDecoder decoder:List.<VinDecoder>of((v,y,c)->{throw new IllegalStateException("private "+VIN);},(v,y,c)->null,(v,y,c)->new VinDecoder.Result(VinDecoder.Status.NO_MATCH,"OTHER",Map.of(),true),(v,y,c)->new VinDecoder.Result(VinDecoder.Status.PARTIAL,"NHTSA_VPIC",Map.of("model_year","2025"),true))){
            service=new VinRecognition(db(),decoder,true);var e=assertThrows(ResponseStatusException.class,()->service.recognize(owner,new VinRecognition.Input(VIN,2026)));assertEquals(503,e.getStatusCode().value());assertFalse(e.toString().contains(VIN));assertNull(e.getCause());
        }
    }
    @Test void partial_and_no_match_remain_distinct(){
        for(var answer:List.of(new VinDecoder.Result(VinDecoder.Status.PARTIAL,"NHTSA_VPIC",Map.of("make","Synthetic"),true),new VinDecoder.Result(VinDecoder.Status.NO_MATCH,"NHTSA_VPIC",Map.of(),true))){service=new VinRecognition(db(),(v,y,c)->answer,true);assertEquals(answer.status().name(),run().path("status").asText());}
    }
    @Test void two_requests_at_once_then_capacity_recovers()throws Exception{
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        service=new VinRecognition(db(),(v,y,c)->{entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return ANSWER;},true);
        var one=CompletableFuture.supplyAsync(this::run);var two=CompletableFuture.supplyAsync(this::run);
        try{assertTrue(entered.await(8,TimeUnit.SECONDS));assertEquals(429,status(this::run));}finally{release.countDown();}
        one.get(8,TimeUnit.SECONDS);two.get(8,TimeUnit.SECONDS);assertEquals("CANDIDATE",run().path("status").asText());
    }
}
