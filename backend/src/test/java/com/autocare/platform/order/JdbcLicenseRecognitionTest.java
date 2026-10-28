package com.autocare.platform.order;

import com.autocare.platform.file.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
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
import static org.mockito.Mockito.*;

/** Real isolated MySQL; identity, file bytes, private storage and OCR are explicit test fixtures. */
@Testcontainers
class JdbcLicenseRecognitionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    ObjectMapper mapper=new ObjectMapper();
    SignedObjectStore store;LicenseRecognition service;VehicleOwner owner;int calls;Instant now;
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        try(var connection=source.getConnection()){for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql"))ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations",file)));}
    }
    ReservationStore db(){var tx=new DataSourceTransactionManager(jdbc.getDataSource());return new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,tx),Clock.fixed(now,ZoneOffset.UTC),tx);}
    @BeforeEach void setup(){
        now=Instant.now();for(String table:List.of("auth_rate_limit","file_object","vehicle","auth_session","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-license-one',1),(2,'synthetic-license-two',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','license-test','synthetic',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session());
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(1,'user',1,'synthetic-license-key','image/png',3,'CLEAN')");
        store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-license-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",3));when(store.read("synthetic-license-key",4194304)).thenReturn(new byte[]{1,2,3});
        calls=0;service=new LicenseRecognition(db(),store,bytes->{calls++;return LicenseTestData.answer(mapper);},true);
    }
    int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    JsonNode run(){return service.recognize(owner,LicenseRecognition.input(LicenseTestData.RAW));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void new_vehicle_candidate_needs_no_existing_vehicle_and_never_writes_business_records(){
        assertEquals(0,count("vehicle"));var files=jdbc.queryForList("SELECT * FROM file_object");var out=run();
        assertEquals(1,calls);assertEquals("NEW_VEHICLE",out.path("purpose").asText());assertEquals(1,out.path("file_id").asLong());assertTrue(out.path("requires_confirmation").booleanValue());assertEquals("CANDIDATE",out.path("review_status").asText());
        assertFalse(out.has("model_id"));assertFalse(out.toString().contains("synthetic-license-key"));assertEquals(files,jdbc.queryForList("SELECT * FROM file_object"));
        for(String table:List.of("vehicle","vehicle_archive","audit_log","idempotency_record"))assertEquals(0,count(table));
        assertFalse(jdbc.queryForList("SELECT * FROM auth_rate_limit").toString().contains(LicenseTestData.VIN));
    }
    @Test void strict_body_purpose_consent_ids_and_duplicates_rejected_without_io(){
        String raw=LicenseTestData.RAW;
        for(String value:List.of("{}",raw+"{}",raw.replace("true","false"),raw.replace("NEW_VEHICLE\"","ARCHIVE\""),raw.replace("NEW_VEHICLE_LICENSE_OCR_V1","MILEAGE_OCR_V1"),raw.replace("\"file_id\":1","\"file_id\":1.0"),raw.replace("\"file_id\":1","\"file_id\":0"),raw.replace("\"file_id\":1","\"file_id\":9007199254740992"),raw.replace("}",",\"url\":\"https://private.invalid\"}"),raw.replace("}",",\"vehicle_id\":1}"),raw.replace("}",",\"file_id\":2}")))assertEquals(400,status(()->LicenseRecognition.input(value)));
        assertEquals(400,status(()->service.recognize(owner,null)));assertEquals(0,calls);assertEquals(0,count("auth_rate_limit"));verifyNoInteractions(store);
    }
    @Test void disabled_or_unconfigured_service_has_zero_storage_provider_and_admission_io(){
        service=new LicenseRecognition(db(),store,b->{fail();return null;},false);assertEquals(503,status(this::run));
        service=new LicenseRecognition(db(),null,null,true);assertEquals(503,status(this::run));assertEquals(0,count("auth_rate_limit"));verifyNoInteractions(store);
    }
    @Test void revoked_deleted_disabled_expired_or_foreign_session_is_rejected_before_storage(){
        for(String mutation:List.of("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()","UPDATE user SET status=0 WHERE id=1","UPDATE user SET is_deleted=1 WHERE id=1","UPDATE auth_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)")){
            jdbc.update("UPDATE user SET status=1,is_deleted=0");jdbc.update("UPDATE auth_session SET revoked_at=NULL,expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)");jdbc.update(mutation);assertEquals(401,status(this::run));
        }
        owner=new VehicleOwner(2,owner.session(),Instant.now().plusSeconds(600));assertEquals(401,status(this::run));assertEquals(0,calls);verifyNoInteractions(store);
    }
    @Test void foreign_file_wrong_owner_type_scan_type_size_or_deletion_refused_before_storage(){
        for(String mutation:List.of("owner_id=2","owner_type='staff_account'","is_deleted=1","scan_status='PENDING'","scan_status='INFECTED'","content_type='image/webp'","content_type='application/pdf'","size_bytes=0","size_bytes=4194305")){
            jdbc.update("UPDATE file_object SET owner_type='user',owner_id=1,is_deleted=0,scan_status='CLEAN',content_type='image/png',size_bytes=3");jdbc.update("UPDATE file_object SET "+mutation);assertEquals(404,status(this::run));
        }
        assertEquals(0,calls);assertEquals(0,count("auth_rate_limit"));verifyNoInteractions(store);
    }
    @Test void nonprivate_storage_metadata_size_type_or_short_read_never_reaches_model(){
        when(store.isPrivate()).thenReturn(false);assertEquals(503,status(this::run));when(store.isPrivate()).thenReturn(true);
        when(store.stat("synthetic-license-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",4));assertEquals(503,status(this::run));
        when(store.stat("synthetic-license-key")).thenReturn(new SignedObjectStore.StoredObject("image/jpeg",3));assertEquals(503,status(this::run));
        when(store.stat("synthetic-license-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",3));when(store.read("synthetic-license-key",4194304)).thenReturn(new byte[]{1,2});assertEquals(503,status(this::run));assertEquals(0,calls);
    }
    @Test void storage_read_rechecks_session_and_file_before_model(){
        doAnswer(invocation->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");return new byte[]{1,2,3};}).when(store).read("synthetic-license-key",4194304);assertEquals(401,status(this::run));assertEquals(0,calls);
        jdbc.update("UPDATE auth_session SET revoked_at=NULL");doAnswer(invocation->{jdbc.update("UPDATE file_object SET object_key='changed-private-key'");return new byte[]{1,2,3};}).when(store).read("synthetic-license-key",4194304);assertEquals(409,status(this::run));assertEquals(0,calls);
    }
    @Test void revoke_during_success_or_provider_failure_withholds_candidate(){
        for(boolean fail:List.of(false,true)){
            jdbc.update("UPDATE auth_session SET revoked_at=NULL");service=new LicenseRecognition(db(),store,b->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");if(fail)throw new IllegalStateException("private certificate "+LicenseTestData.VIN);return LicenseTestData.answer(mapper);},true);
            assertEquals(401,status(this::run));
        }
    }
    @Test void transfer_delete_or_scan_change_during_model_withholds_candidate(){
        for(String mutation:List.of("owner_id=2","is_deleted=1","scan_status='PENDING'")){
            jdbc.update("UPDATE file_object SET owner_id=1,is_deleted=0,scan_status='CLEAN'");service=new LicenseRecognition(db(),store,b->{jdbc.update("UPDATE file_object SET "+mutation);return LicenseTestData.answer(mapper);},true);assertEquals(404,status(this::run));
        }
    }
    @Test void same_owner_file_fact_changes_are_conflicts_not_returned_candidates(){
        service=new LicenseRecognition(db(),store,b->{jdbc.update("UPDATE file_object SET object_key='changed-private-key'");return LicenseTestData.answer(mapper);},true);assertEquals(409,status(this::run));
    }
    @Test void supplier_failure_and_malformed_protocol_do_not_expose_private_diagnostics(){
        service=new LicenseRecognition(db(),store,b->{throw new IllegalStateException("private certificate "+LicenseTestData.VIN);},true);var failure=assertThrows(ResponseStatusException.class,this::run);assertEquals(503,failure.getStatusCode().value());assertNull(failure.getCause());assertFalse(failure.toString().contains(LicenseTestData.VIN));
        service=new LicenseRecognition(db(),store,b->mapper.createObjectNode().put("owner_name","private"),true);assertEquals(503,status(this::run));
        service=new LicenseRecognition(db(),store,b->null,true);assertEquals(503,status(this::run));
    }
    @Test void low_partial_empty_and_all_ambiguous_values_are_preserved(){
        for(String status:List.of("LOW_CONFIDENCE","PARTIAL","NO_CANDIDATE","AMBIGUOUS")){
            var answer=LicenseTestData.answer(mapper);var fields=(ArrayNode)answer.path("fields");
            if(status.equals("LOW_CONFIDENCE")){((ObjectNode)fields.get(0)).put("status",status);((ObjectNode)fields.get(0).path("candidates").get(0)).put("confidence",.4);}
            else if(status.equals("AMBIGUOUS")){((ObjectNode)fields.get(0)).put("status",status);((ArrayNode)fields.get(0).path("candidates")).addObject().put("value","粤L11111").put("confidence",.99);}
            else{((ObjectNode)fields.get(1)).put("status","NO_CANDIDATE").putArray("candidates");if(status.equals("NO_CANDIDATE"))((ObjectNode)fields.get(0)).put("status","NO_CANDIDATE").putArray("candidates");}
            answer.put("status",status);service=new LicenseRecognition(db(),store,b->answer,true);var result=run();assertEquals(status,result.path("review_status").asText());assertEquals(fields,result.path("fields"));
        }
    }
    @Test void five_attempts_shared_by_instances_and_new_session_cannot_reset_limit(){
        for(int i=0;i<5;i++){service=new LicenseRecognition(db(),store,b->{calls++;return LicenseTestData.answer(mapper);},true);run();}
        assertEquals(429,status(this::run));assertEquals(5,calls);
        var next=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','license-test','synthetic2',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",next.session());owner=next;
        assertEquals(429,status(this::run));assertEquals(5,jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='license_ocr'",Integer.class));
    }
    @Test void expired_own_rows_are_removed_other_scopes_are_preserved(){
        String hash=AuthTokens.sha256("owner:1");var old=ReservationStore.time(now.minusSeconds(172800));
        jdbc.update("INSERT INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('license_ocr',?,?,1),('other',?,?,1)",hash,old,hash,old);run();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit WHERE scope='license_ocr'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit WHERE scope='other'",Integer.class));
    }
    @Test void two_inferences_enter_no_queue_and_capacity_recovers()throws Exception{
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        service=new LicenseRecognition(db(),store,b->{entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return LicenseTestData.answer(mapper);},true);
        var one=CompletableFuture.supplyAsync(this::run);var two=CompletableFuture.supplyAsync(this::run);
        try{assertTrue(entered.await(8,TimeUnit.SECONDS));assertEquals(429,status(this::run));}finally{release.countDown();}
        one.get(8,TimeUnit.SECONDS);two.get(8,TimeUnit.SECONDS);assertEquals("CANDIDATE",run().path("review_status").asText());
    }
}
