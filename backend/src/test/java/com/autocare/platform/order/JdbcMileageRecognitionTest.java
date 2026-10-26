package com.autocare.platform.order;
import com.autocare.platform.file.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class JdbcMileageRecognitionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    ObjectMapper mapper=new ObjectMapper();
    SignedObjectStore store;MileageRecognition service;VehicleOwner owner;int calls;
    static final String RAW="{\"vehicle_id\":101,\"file_id\":1,\"consent\":true,\"consent_version\":\"MILEAGE_OCR_V1\"}";
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()){for(String name:List.of("V001__baseline.sql","V003__auth_lifecycle.sql"))ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations",name)));}
    }
    JsonNode result(String status,double confidence)throws Exception{return mapper.readTree("{\"version\":1,\"scene\":\"mileage\",\"status\":\""+status+"\",\"candidates\":[{\"mileage_km\":12345,\"confidence\":"+confidence+"}],\"requires_confirmation\":true,\"image_recognition_performed\":true}");}
    ReservationStore db(){var tx=new DataSourceTransactionManager(jdbc.getDataSource());return new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,tx),Clock.systemUTC(),tx);}
    @BeforeEach void setup()throws Exception{
        for(String table:List.of("file_object","vehicle","auth_session","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'ocr-one',1),(2,'ocr-two',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','ocr-test','synthetic',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(101,1,100),(201,2,200)");
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(1,'user',1,'synthetic-key','image/png',3,'CLEAN')");
        store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",3));when(store.read("synthetic-key",4194304)).thenReturn(new byte[]{1,2,3});
        calls=0;JsonNode answer=result("CANDIDATE",.95);service=new MileageRecognition(db(),store,b->{calls++;return answer;},true);
    }
    int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    Map<String,Object> run(){return service.recognize(owner,MileageRecognition.input(RAW));}
    @Test void clean_owned_image_returns_only_candidate_and_never_writes(){
        var before=jdbc.queryForList("SELECT * FROM vehicle");var files=jdbc.queryForList("SELECT * FROM file_object");
        var out=run();assertEquals(1,calls);assertEquals(true,out.get("requires_confirmation"));assertFalse(out.toString().contains("synthetic-key"));assertTrue(out.toString().contains("12345"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM vehicle"));assertEquals(files,jdbc.queryForList("SELECT * FROM file_object"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
    }
    @Test void foreign_vehicle_or_file_refused_before_storage(){
        assertEquals(404,status(()->service.recognize(owner,MileageRecognition.input(RAW.replace("101","201")))));
        jdbc.update("UPDATE file_object SET owner_id=2 WHERE id=1");assertEquals(404,status(this::run));assertEquals(0,calls);verifyNoInteractions(store);
    }
    @Test void revoked_session_refused_before_storage(){jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");assertEquals(401,status(this::run));verifyNoInteractions(store);}
    @Test void disabled_unknown_type_deleted_oversize_and_scan_state_rejected(){
        for(String mutation:List.of("content_type='application/pdf'","is_deleted=1","size_bytes=4194305","scan_status='PENDING'")){
            jdbc.update("UPDATE file_object SET content_type='image/png',is_deleted=0,size_bytes=3,scan_status='CLEAN'");jdbc.update("UPDATE file_object SET "+mutation);assertEquals(404,status(this::run));}
        assertEquals(0,calls);verifyNoInteractions(store);
    }
    @Test void disabled_service_has_zero_external_io(){service=new MileageRecognition(db(),store,b->{fail();return null;},false);assertEquals(503,status(this::run));verifyNoInteractions(store);}
    @Test void storage_metadata_mismatch_cannot_reach_provider(){when(store.stat("synthetic-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",4));assertEquals(503,status(this::run));assertEquals(0,calls);verify(store,never()).read(anyString(),anyInt());}
    @Test void revoke_or_transfer_during_inference_withholds_candidate()throws Exception{
        var answer=result("CANDIDATE",.95);service=new MileageRecognition(db(),store,b->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");return answer;},true);assertEquals(401,status(this::run));
        jdbc.update("UPDATE auth_session SET revoked_at=NULL");service=new MileageRecognition(db(),store,b->{jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");return answer;},true);assertEquals(404,status(this::run));
    }
    @Test void changed_file_and_malformed_upstream_withhold_candidate()throws Exception{
        var answer=result("CANDIDATE",.95);service=new MileageRecognition(db(),store,b->{jdbc.update("UPDATE file_object SET object_key='changed-key'");return answer;},true);assertEquals(409,status(this::run));
    }
    @Test void low_confidence_is_not_promoted_and_ambiguity_has_no_automatic_choice()throws Exception{
        var low=result("LOW_CONFIDENCE",.5);service=new MileageRecognition(db(),store,b->low,true);assertEquals("LOW_CONFIDENCE",run().get("review_status"));
        var ambiguous=result("AMBIGUOUS",.5);service=new MileageRecognition(db(),store,b->ambiguous,true);assertEquals("EMPTY",((Map<?,?>)run().get("recognition")).get("status"));
    }
    @Test void missing_consent_extra_duplicate_and_negative_ids_rejected(){for(String raw:List.of("{}",RAW.replace("true","false"),RAW.replace("101","-1"),RAW.replace("\"file_id\":1","\"file_id\":1,\"file_id\":2"),RAW.replace("}",",\"url\":\"remote\"}")))assertEquals(400,status(()->MileageRecognition.input(raw)));}
    @Test void only_two_inferences_enter_at_once()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);var answer=result("CANDIDATE",.95);
        service=new MileageRecognition(db(),store,b->{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}return answer;},true);
        var one=java.util.concurrent.CompletableFuture.supplyAsync(this::run);var two=java.util.concurrent.CompletableFuture.supplyAsync(this::run);
        try{assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(429,status(this::run));}finally{release.countDown();}
        one.get(5,java.util.concurrent.TimeUnit.SECONDS);two.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test void invalid_provider_shape_confidence_version_and_count_rejected()throws Exception{
        for(String raw:List.of(result("CANDIDATE",.95).toString().replace("\"version\":1","\"version\":4294967297"),result("CANDIDATE",.5).toString(),result("LOW_CONFIDENCE",.95).toString(),"{}")){
            JsonNode answer=mapper.readTree(raw);service=new MileageRecognition(db(),store,b->answer,true);assertEquals(503,status(this::run));}
    }
}
