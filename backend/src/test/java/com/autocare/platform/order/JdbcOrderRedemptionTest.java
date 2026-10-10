package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
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

@Testcontainers
class JdbcOrderRedemptionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;ReservationStore db;TechnicianAssignments service;ServiceWork work;OrderRedemption redemption;MerchantActor shop,other;TechnicianActor tech,peer,foreign;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V015__order_redemption.sql")));}
    }
    @BeforeEach void setup(){
        for(String trigger:List.of("reject_dispatch_audit","reject_dispatch_transition","reject_dispatch_cache","reject_work_audit","reject_work_transition","reject_work_cache","reject_redeem"))jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("order_redemption","auth_rate_limit","payment_event","payment_exception","payment","user","order_dispute_record","order_dispute","service_evidence_file","service_report_submission","repair_protection","technician_report","file_object","pickup_check_file","technician_assignment","pickup_check","order_status_transition","audit_log","idempotency_record","auth_session","staff_wechat_identity","order","appointment_slot","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,new ObjectMapper(),new WriteIntegrityService(jdbc,new ObjectMapper(),manager),Clock.systemUTC(),manager);service=new TechnicianAssignments(db,"test-app");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-A','test',1),(2,2,'synthetic-B','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','shop-A','ACTIVE'),(2,2,'MERCHANT','shop-B','ACTIVE'),(12,1,'TECHNICIAN','tech-A','ACTIVE'),(13,1,'TECHNICIAN','tech-peer','ACTIVE'),(14,2,'TECHNICIAN','tech-B','ACTIVE'),(15,1,'TECHNICIAN','unbound','ACTIVE')");
        for(int i=12;i<=14;i++)jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(?,'test-app',?,?)",i+100,"synthetic-"+i,i);
        shop=merchant(1,1);other=merchant(2,2);tech=technician(12,1);peer=technician(13,1);foreign=technician(14,2);
        jdbc.update("INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(1,1,1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),10)");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot) VALUES(1,'synthetic-job',99,99,1,10,10,'RECEIVED',1,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'123456',?,?)",
            db.json(Map.of("project_name","inspection","service_content","synthetic","phone","private","vin","private")),db.json(Map.of("slot_id",1,"starts_at",Instant.now().toString(),"verify_code","private")));
        jdbc.update("INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,confirm_at) VALUES(1,1,1,1,UTC_TIMESTAMP())");
        work=new ServiceWork(db,service,"test-app");service.assign(shop,key(),1,12);service.accept(tech,key(),1);
        for(int i=101;i<=106;i++)jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'staff_account',?,?, 'image/png',100,'CLEAN')",i,i==101?1:12,"synthetic-work-"+i);
    }
    void ready(){
        long checkId=jdbc.queryForObject("SELECT id FROM pickup_check WHERE order_id=1",Long.class);
        for(int i=0;i<PickupInput.SLOTS.size();i++){int file=201+i;jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'staff_account',1,?,'image/png',100,'CLEAN')",file,"synthetic-pickup-"+file);jdbc.update("INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES(?,?,?)",checkId,file,PickupInput.SLOTS.get(i));}
        submitted();work.sign(tech,key(),signature());
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("PAYMENT_LOCAL_TEST_ENABLED","true").withProperty("PAYMENT_LOCAL_TEST_SECRET","synthetic-secret-at-least-32-characters");env.setActiveProfiles("local-payment-test");
        redemption=new OrderRedemption(db,work,new PaymentChannels(env,Clock.systemUTC()));
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(1,1,'LOCAL_TEST','synthetic-payment','CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),'synthetic-receipt')");
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST',?,1,?,'PAID')",key(),"a".repeat(64));
    }
    JsonNode redeemBody(){return db.mapper.valueToTree(Map.of("code","123456"));}
    JsonNode redeem(String k){return redemption.redeem(shop,k,1,redeemBody());}
    String key(){return UUID.randomUUID().toString();}
    MerchantActor merchant(long id,long shop){String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'MERCHANT','merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,shop,key());return new MerchantActor(id,shop,s,Instant.now().plusSeconds(3600));}
    TechnicianActor technician(long id,long shop){String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'TECHNICIAN','test-app',?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,shop,id+100,key());return new TechnicianActor(id,shop,id+100,"test-app",s,Instant.now().plusSeconds(3600));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    int code(Runnable action){return assertThrows(FulfillmentConflict.class,action::run).code;}
    String state(){return jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class);}

    JsonNode protection(){return db.mapper.valueToTree(Map.of("order_id",1,"items",List.of("SEAT_COVER","STEERING_COVER"),"photo_file_id",101));}
    JsonNode report(){return db.mapper.valueToTree(Map.of("order_id",1,"process_photos",List.of(102),"fault_part_photos",List.of(103),"finish_photos",List.of(104),"no_fault_parts",false,"repair_plan","synthetic work","fault_analysis","synthetic diagnosis","parts_used",List.of(Map.of("name","test","model","test","brand","test","quantity",1)),"no_parts",false,"work_hours",45));}
    JsonNode signature(){return db.mapper.valueToTree(Map.of("order_id",1,"signature_file_id",105));}
    void protectedOrder(){work.protect(shop,key(),protection());}
    void submitted(){protectedOrder();work.submit(tech,key(),report());}
    @Test void successSameKeyAndNewKeyCommitExactlyOnceAndNeverLeakCode(){
        ready();int audits=count("audit_log"),transitions=count("order_status_transition");String k=key();var first=redeem(k);assertEquals(first,redeem(k));assertFalse(redeem(key()).path("data").path("changed").asBoolean());
        assertEquals("COMPLETED",state());assertEquals(audits+1,count("audit_log"));assertEquals(transitions+1,count("order_status_transition"));assertEquals(1,count("order_redemption"));assertTrue(first.path("data").path("test_mode").asBoolean());
        String payload=jdbc.queryForObject("SELECT after_state FROM audit_log WHERE action='ORDER_COMPLETE'",String.class)+jdbc.queryForObject("SELECT response_body FROM idempotency_record WHERE request_path LIKE '%/redeem' LIMIT 1",String.class);
        for(String secret:List.of("123456","verify_code","code_digest","channel_payment_no","user_id","staff_id"))assertFalse(payload.contains(secret),secret);
        assertNotNull(redemption.detail(shop,1).get("redemption"));
    }
    @Test void sharedFiveWrongGuessesPersistAcrossKeysEmployeesAndSessions(){
        ready();jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(3,1,'MERCHANT','shop-peer','ACTIVE')");var peerShop=merchant(3,1);var bad=db.mapper.valueToTree(Map.of("code","000000"));
        for(int i=0;i<5;i++)assertEquals(422,status(()->redemption.redeem(peerShop,key(),1,bad)));
        var limited=assertThrows(OrderRedemption.RateLimited.class,()->redeem(key()));assertTrue(limited.retry>=1&&limited.retry<=600);assertEquals(5,jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='redeem_code'",Integer.class));assertEquals(0,count("order_redemption"));
        jdbc.update("UPDATE auth_rate_limit SET window_start=DATE_SUB(window_start,INTERVAL 10 MINUTE)");redeem(key());assertEquals("COMPLETED",state());
    }
    @Test void missingPaymentAndExceptionsIncludingDeletedBlockCompletion(){
        ready();jdbc.update("DELETE FROM payment");assertEquals(43009,code(()->redeem(key())));assertEquals("PENDING_VERIFY",state());assertEquals(0,count("auth_rate_limit"));
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(1,1,'LOCAL_TEST','synthetic-payment','CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),'synthetic-receipt')");
        jdbc.update("INSERT INTO payment_exception(payment_id,order_id,channel,amount,reason,is_deleted) VALUES(1,1,'LOCAL_TEST',10,'TEST',1)");assertEquals(43009,code(()->redeem(key())));
    }
    @Test void rejectsIncompleteMismatchedAndUnsupportedPaymentEvidence(){
        ready();for(String update:List.of("amount=11","currency='USD'","paid_at=NULL","channel_payment_no=NULL","payment_no=NULL","is_deleted=1","status='FAILED'")){
            jdbc.update("UPDATE payment SET "+update+" WHERE id=1");assertEquals(43009,code(()->redeem(key())));jdbc.update("UPDATE payment SET amount=10,currency='CNY',paid_at=UTC_TIMESTAMP(),channel_payment_no='synthetic-receipt',payment_no='synthetic-payment',is_deleted=0,status='SUCCEEDED' WHERE id=1");
        }
        jdbc.update("DELETE FROM payment_event");assertEquals(43009,code(()->redeem(key())));jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('WECHAT',?,1,?,'PAID')",key(),"a".repeat(64));jdbc.update("UPDATE payment SET channel='WECHAT' WHERE id=1");assertEquals(503,status(()->redeem(key())));assertEquals(0,count("order_redemption"));
    }
    @Test void testReceiptsAreRejectedOutsideIsolatedPaymentProfile(){
        ready();redemption=new OrderRedemption(db,work,new PaymentChannels(new org.springframework.mock.env.MockEnvironment(),Clock.systemUTC()));assertEquals(404,status(()->redeem(key())));assertEquals("PENDING_VERIFY",state());
    }
    @Test void deletedOrUnsafeConstructionAndSignatureEvidenceCannotComplete(){
        ready();for(int file:List.of(101,102,103,104,105)){jdbc.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=?",file);assertTrue(Set.of(43002,43005).contains(code(()->redeem(key()))));jdbc.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=?",file);}jdbc.update("UPDATE technician_report SET signed_at=NULL");assertEquals(43005,code(()->redeem(key())));assertEquals(0,count("order_redemption"));
    }
    @Test void missingConfirmationDisputeAndAssignmentBlockEvenAfterSignature(){
        ready();jdbc.update("UPDATE pickup_check SET owner_confirm=2");assertEquals(43003,code(()->redeem(key())));jdbc.update("UPDATE pickup_check SET owner_confirm=3");jdbc.update("INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,reason,from_status,opened_by,opened_at) VALUES(1,1,1,'test','PENDING_VERIFY',99,UTC_TIMESTAMP())");assertEquals(43007,code(()->redeem(key())));jdbc.update("DELETE FROM order_dispute");jdbc.update("UPDATE technician_assignment SET status='ASSIGNED'");assertEquals(43004,code(()->redeem(key())));
    }
    com.autocare.platform.vehicle.VehicleOwner owner(long id){
        jdbc.update("INSERT INTO user(id) VALUES(?)",id);String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,key());return new com.autocare.platform.vehicle.VehicleOwner(id,s,Instant.now().plusSeconds(3600));
    }
    @Test void ownerReadsOnlyOwnReceiptAndCodeDisappearsAfterCompletion(){
        ready();var owner=owner(99);var foreignOwner=owner(100);var orders=new ReservationOrders(db,new ReservationExpiry(db));assertEquals("123456",orders.detail(owner,1).get("appointment_code"));assertNull(redemption.detail(owner,1).get("redemption"));assertEquals(404,status(()->redemption.detail(foreignOwner,1)));redeem(key());assertFalse(orders.detail(owner,1).containsKey("appointment_code"));assertNotNull(redemption.detail(owner,1).get("redemption"));
    }
    @Test void replaysRevalidatePaymentAndEvidenceAndLegacyCodesAreNotIssued(){
        ready();String k=key();redeem(k);jdbc.update("UPDATE payment SET amount=11 WHERE id=1");assertEquals(43009,code(()->redeem(k)));jdbc.update("UPDATE payment SET amount=10 WHERE id=1");jdbc.update("UPDATE file_object SET is_deleted=1 WHERE id=105");assertEquals(43005,code(()->redeem(k)));assertEquals(1,count("order_redemption"));
        jdbc.update("UPDATE `order` SET status='PENDING_VERIFY',verify_code=NULL WHERE id=1");var owner=owner(99);assertFalse(new ReservationOrders(db,new ReservationExpiry(db)).detail(owner,1).containsKey("appointment_code"));assertNull(jdbc.queryForObject("SELECT verify_code FROM `order` WHERE id=1",String.class));
    }
    @Test void allSevenPickupPhotosMustRemainAvailableIncludingOnReplay(){
        ready();for(String change:List.of("is_deleted=1","scan_status='INFECTED'","owner_id=12","content_type='text/plain'","size_bytes=0")){
            jdbc.update("UPDATE file_object SET "+change+" WHERE id=201");assertEquals(43001,code(()->redeem(key())));jdbc.update("UPDATE file_object SET is_deleted=0,scan_status='CLEAN',owner_id=1,content_type='image/png',size_bytes=100 WHERE id=201");
        }String k=key();redeem(k);jdbc.update("DELETE FROM pickup_check_file WHERE file_id=207");assertEquals(43001,code(()->redeem(k)));assertEquals(1,count("order_redemption"));
    }
    @Test void wrongShopAndRevokedSessionCannotWriteOrReplay(){
        ready();assertEquals(404,status(()->redemption.redeem(other,key(),1,redeemBody())));assertEquals(404,status(()->redemption.detail(other,1)));String k=key();redeem(k);jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());assertEquals(401,status(()->redeem(k)));assertEquals(1,count("order_redemption"));
    }
    @Test void cannotBypassWithGenericCompleteBeforeOrAfterRedemption(){
        ready();var generic=new OrderFulfillment(db,new MerchantOrders(db));assertEquals(43006,code(()->generic.apply(shop,key(),1,"COMPLETE",null)));redeem(key());assertEquals(43006,code(()->generic.apply(shop,key(),1,"COMPLETE",null)));
    }
    @Test void completedLegacyAndOtherStatusesAreRejected(){
        ready();for(String state:List.of("PAID","IN_SERVICE","COMPLETED","CLOSED","DISPUTED")){jdbc.update("UPDATE `order` SET status=? WHERE id=1",state);assertEquals(40905,code(()->redeem(key())));}assertEquals(0,count("order_redemption"));
    }
    @Test void completionRecordStateAndBothAuditsRollbackIfAnyWriteFails(){
        ready();int audits=count("audit_log"),cache=count("idempotency_record"),transitions=count("order_status_transition");String k=key();
        for(String table:List.of("order_redemption","audit_log","order_status_transition","idempotency_record")){
            jdbc.execute("CREATE TRIGGER reject_redeem BEFORE "+(table.equals("idempotency_record")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->redeem(k)));assertEquals("PENDING_VERIFY",state());assertEquals(0,count("order_redemption"));assertEquals(audits,count("audit_log"));assertEquals(cache,count("idempotency_record"));assertEquals(transitions,count("order_status_transition"));jdbc.execute("DROP TRIGGER reject_redeem");
        }redeem(k);assertEquals("COMPLETED",state());
    }
    @Test void concurrentRedemptionWithTwoEmployeesCommitsOnlyOneTransition()throws Exception{
        ready();jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(3,1,'MERCHANT','shop-peer','ACTIVE')");var peerShop=merchant(3,1);int audits=count("audit_log"),transitions=count("order_status_transition");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var jobs=new ArrayList<Future<Boolean>>();for(var actor:List.of(shop,peerShop))jobs.add(pool.submit(()->{start.await();return redemption.redeem(actor,key(),1,redeemBody()).path("data").path("changed").asBoolean();}));start.countDown();int changed=0;for(var job:jobs)if(job.get(30,TimeUnit.SECONDS))changed++;assertEquals(1,changed);assertEquals(1,count("order_redemption"));assertEquals(audits+1,count("audit_log"));assertEquals(transitions+1,count("order_status_transition"));}finally{pool.shutdownNow();}
    }
}
