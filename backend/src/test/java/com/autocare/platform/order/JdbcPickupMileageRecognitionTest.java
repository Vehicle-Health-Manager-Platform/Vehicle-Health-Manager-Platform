package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.SignedObjectStore;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
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
import static org.mockito.Mockito.*;

/** Real MySQL authorization/state checks, explicit synthetic storage/OCR provider. */
@Testcontainers
class JdbcPickupMileageRecognitionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper=new ObjectMapper();
    MileageRecognition service;SignedObjectStore store;ReservationStore db;MerchantActor merchant,other,employee;int calls;
    static final String RAW="{\"order_id\":1,\"file_id\":1,\"consent\":true,\"consent_version\":\"PICKUP_MILEAGE_OCR_V1\"}";
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs/sql/migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));}
    }
    MerchantActor actor(long staff,long shop,String role){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,?,'merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,staff,role,shop,UUID.randomUUID().toString());return new MerchantActor(staff,shop,role,session,Instant.now().plusSeconds(600));}
    @BeforeEach void setup()throws Exception{
        for(String table:List.of("pickup_check_file","pickup_check","order","file_object","vehicle","user","auth_session","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var tx=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,tx),Clock.systemUTC(),tx);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-one','synthetic',1),(2,2,'synthetic-two','synthetic',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','pickup-ocr-one','ACTIVE'),(2,2,'MERCHANT','pickup-ocr-two','ACTIVE'),(3,1,'STAFF','pickup-ocr-staff','ACTIVE')");
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'pickup-ocr-owner',1),(2,'pickup-ocr-foreign',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(1,1,100)");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(1,'synthetic-pickup-ocr',1,1,1,10,10,'PAID')");
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(1,'staff_account',1,'synthetic-private-key','image/png',3,'CLEAN')");
        merchant=actor(1,1,"MERCHANT");other=actor(2,2,"MERCHANT");employee=actor(3,1,"STAFF");
        store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-private-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",3));when(store.read("synthetic-private-key",4194304)).thenReturn(new byte[]{1,2,3});
        calls=0;var result=answer("CANDIDATE",.95);service=new MileageRecognition(db,store,bytes->{calls++;return result;},true);
    }
    JsonNode answer(String status,double confidence)throws Exception{return mapper.readTree("{\"version\":1,\"scene\":\"mileage\",\"status\":\""+status+"\",\"candidates\":[{\"mileage_km\":12345,\"confidence\":"+confidence+"}],\"requires_confirmation\":true,\"image_recognition_performed\":true}");}
    Map<String,Object> run(){return service.recognizePickup(merchant,MileageRecognition.pickupInput(RAW));}
    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    @Test void candidate_has_only_order_scope_and_does_not_write_business_facts(){
        var order=jdbc.queryForList("SELECT * FROM `order`");var vehicle=jdbc.queryForList("SELECT * FROM vehicle");
        var out=run();assertEquals(1L,out.get("order_id"));assertFalse(out.containsKey("vehicle_id"));assertEquals(true,out.get("requires_confirmation"));assertTrue(out.toString().contains("12345"));assertFalse(out.toString().contains("synthetic-private-key"));
        assertEquals(order,jdbc.queryForList("SELECT * FROM `order`"));assertEquals(vehicle,jdbc.queryForList("SELECT * FROM vehicle"));
        for(String table:List.of("pickup_check","pickup_check_file","vehicle_archive","audit_log","idempotency_record"))assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void other_shop_and_other_staff_image_cannot_reach_storage(){
        assertEquals(404,status(()->service.recognizePickup(other,MileageRecognition.pickupInput(RAW))));
        assertEquals(404,status(()->service.recognizePickup(employee,MileageRecognition.pickupInput(RAW))));verifyNoInteractions(store);assertEquals(0,calls);
    }
    @Test void current_employee_can_recognize_only_own_image(){jdbc.update("UPDATE file_object SET owner_id=3");assertEquals(1L,service.recognizePickup(employee,MileageRecognition.pickupInput(RAW)).get("order_id"));}
    @Test void blocked_file_type_size_and_scan_status_rejected_before_provider(){
        for(String update:List.of("owner_type='user'","is_deleted=1","scan_status='PENDING'","size_bytes=4194305","content_type='application/pdf'")){
            jdbc.update("UPDATE file_object SET owner_type='staff_account',is_deleted=0,scan_status='CLEAN',size_bytes=3,content_type='image/png'");jdbc.update("UPDATE file_object SET "+update);assertEquals(404,status(this::run));
        }verifyNoInteractions(store);assertEquals(0,calls);
    }
    @Test void transferred_vehicle_and_finished_order_rejected(){jdbc.update("UPDATE vehicle SET user_id=2");assertEquals(404,status(this::run));jdbc.update("UPDATE vehicle SET user_id=1");for(String state:List.of("RECEIVED","CANCELLED","COMPLETED","UNKNOWN")){jdbc.update("UPDATE `order` SET status=?",state);assertEquals(409,status(this::run));}verifyNoInteractions(store);}
    @Test void already_consumed_pickup_image_is_rejected(){consume();assertEquals(404,status(this::run));verifyNoInteractions(store);}
    void consume(){jdbc.update("INSERT INTO pickup_check(id,order_id) VALUES(1,1)");jdbc.update("INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES(1,1,'DASHBOARD')");}
    @Test void revoked_disabled_or_changed_shop_session_rejected(){jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",merchant.session());assertEquals(401,status(this::run));jdbc.update("UPDATE auth_session SET revoked_at=NULL");jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=1");assertEquals(401,status(this::run));verifyNoInteractions(store);}
    @Test void default_disabled_is_zero_provider_io(){service=new MileageRecognition(db,store,bytes->{fail();return null;},false);assertEquals(503,status(this::run));verifyNoInteractions(store);}
    @Test void metadata_mismatch_is_not_provider_success(){when(store.stat(anyString())).thenReturn(new SignedObjectStore.StoredObject("image/png",4));assertEquals(503,status(this::run));assertEquals(0,calls);}
    @Test void in_flight_order_change_or_revocation_withholds_result()throws Exception{
        var answer=answer("CANDIDATE",.95);service=new MileageRecognition(db,store,bytes->{jdbc.update("UPDATE `order` SET status='RECEIVED'");return answer;},true);assertEquals(409,status(this::run));
        jdbc.update("UPDATE `order` SET status='PAID'");service=new MileageRecognition(db,store,bytes->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",merchant.session());return answer;},true);assertEquals(401,status(this::run));
    }
    @Test void in_flight_file_use_transfer_or_change_withholds_result()throws Exception{
        var answer=answer("CANDIDATE",.95);service=new MileageRecognition(db,store,bytes->{consume();return answer;},true);assertEquals(404,status(this::run));
        jdbc.update("DELETE FROM pickup_check_file");jdbc.update("DELETE FROM pickup_check");service=new MileageRecognition(db,store,bytes->{jdbc.update("UPDATE file_object SET object_key='changed'");return answer;},true);assertEquals(409,status(this::run));
    }
    @Test void owner_and_pickup_share_same_two_inference_permits()throws Exception{
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','synthetic-owner',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(2,'user',1,'synthetic-owner-key','image/png',3,'CLEAN')");
        when(store.stat("synthetic-owner-key")).thenReturn(new SignedObjectStore.StoredObject("image/png",3));when(store.read("synthetic-owner-key",4194304)).thenReturn(new byte[]{1,2,3});
        var owner=new com.autocare.platform.vehicle.VehicleOwner(1,session,Instant.now().plusSeconds(600));
        var ownerInput=MileageRecognition.input("{\"vehicle_id\":1,\"file_id\":2,\"consent\":true,\"consent_version\":\"MILEAGE_OCR_V1\"}");
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var answer=answer("CANDIDATE",.95);
        service=new MileageRecognition(db,store,bytes->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return answer;},true);
        var one=CompletableFuture.supplyAsync(this::run);var two=CompletableFuture.supplyAsync(()->service.recognize(owner,ownerInput));
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(429,status(this::run));}finally{release.countDown();}
        one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);
    }
    @Test void low_confidence_and_ambiguous_results_still_require_manual_review()throws Exception{
        var low=answer("LOW_CONFIDENCE",.2);service=new MileageRecognition(db,store,bytes->low,true);assertEquals("LOW_CONFIDENCE",run().get("review_status"));
        var ambiguous=answer("AMBIGUOUS",.2);service=new MileageRecognition(db,store,bytes->ambiguous,true);assertEquals("EMPTY",((Map<?,?>)run().get("recognition")).get("status"));
    }
}
