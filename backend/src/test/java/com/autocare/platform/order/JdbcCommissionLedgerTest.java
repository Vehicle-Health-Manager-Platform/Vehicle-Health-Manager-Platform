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
class JdbcCommissionLedgerTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;ReservationStore db;TechnicianAssignments service;ServiceWork work;OrderRedemption redemption;MerchantActor shop,other;TechnicianActor tech,peer,foreign;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V015__order_redemption.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_commission");jdbc.update("DELETE FROM merchant_application_review");jdbc.update("DELETE FROM merchant_application");jdbc.update("DELETE FROM merchant_commission_entry");jdbc.update("DELETE FROM order_commission_snapshot");jdbc.update("DELETE FROM merchant_commission_policy");jdbc.update("DELETE FROM refund_event");jdbc.update("DELETE FROM refund");

        jdbc.update("DELETE FROM service_archive_job");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_archive_queue");
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







    com.autocare.platform.vehicle.VehicleOwner owner(long id){
        jdbc.update("INSERT INTO user(id) VALUES(?)",id);String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,key());return new com.autocare.platform.vehicle.VehicleOwner(id,s,Instant.now().plusSeconds(3600));
    }









    void policy(Instant start){jdbc.update("INSERT INTO merchant_application(id,applicant_user_id,merchant_name,category,region_code,status,merchant_id,reviewed_by,reviewed_at) VALUES(1,99,'synthetic','REPAIR','440106','APPROVED',1,1,?)",ReservationStore.time(start));jdbc.update("INSERT INTO merchant_application_review(application_id,revision,decision,merchant_id,operator_id,decided_at) VALUES(1,1,'APPROVED',1,1,?)",ReservationStore.time(start));db.transactions.executeWithoutResult(tx->CommissionLedger.initialize(db,1,1,start));}
    Instant old(){return db.now().atZone(ReservationInput.ZONE).minusMonths(4).toInstant();}
    void snapshot(){db.transactions.executeWithoutResult(tx->CommissionLedger.booking(db,1));}
    String kind(){return jdbc.queryForObject("SELECT kind FROM merchant_commission_entry ORDER BY id LIMIT 1",String.class);}
    java.math.BigDecimal amount(){return jdbc.queryForObject("SELECT amount FROM merchant_commission_entry ORDER BY id LIMIT 1",java.math.BigDecimal.class);}
    void futureRefund(){jdbc.update("UPDATE `order` SET status='CLOSED',close_reason='REFUND_CANCELLED'");jdbc.update("INSERT INTO refund(id,order_id,payment_id,amount,status,owner_consented_at,merchant_consented_at,refunded_at) VALUES(1,1,1,10,'SUCCEEDED',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())");db.transactions.executeWithoutResult(tx->CommissionLedger.refunded(db,db.one("SELECT * FROM `order` WHERE id=1 FOR UPDATE"),db.one("SELECT * FROM payment WHERE id=1 FOR UPDATE"),db.one("SELECT * FROM refund WHERE id=1 FOR UPDATE")));}
    @Test void completionAccruesOnceAndNeverChargesTestsAsReal(){policy(old());snapshot();ready();String k=key();redeem(k);redeem(k);redeem(key());assertEquals("ACCRUAL",kind());assertEquals(new java.math.BigDecimal("0.30"),amount());assertEquals(1,count("merchant_commission_entry"));var detail=new CommissionLedger(db).list(shop,1,20);var summary=(Map<?,?>)detail.get("summary");assertEquals("0.30",((Map<?,?>)summary.get("test")).get("accrued"));assertEquals("0.00",((Map<?,?>)summary.get("real")).get("net"));assertEquals(1,count("order_redemption"));}
    @Test void waiverFrozenAtBookingSurvivesPolicyMutation(){policy(db.now().atZone(ReservationInput.ZONE).minusMonths(1).toInstant());snapshot();jdbc.update("UPDATE merchant_commission_policy SET rate_percent=88,waived_until=UTC_TIMESTAMP()");ready();redeem(key());assertEquals(new java.math.BigDecimal("0.00"),amount());assertEquals("NEW_MERCHANT",jdbc.queryForObject("SELECT exemption FROM merchant_commission_entry",String.class));}
    @Test void chargedSnapshotDoesNotFollowNewPolicyOrMerchantRate(){policy(old());snapshot();jdbc.update("UPDATE merchant SET commission_rate=90");jdbc.update("UPDATE merchant_commission_policy SET rate_percent=1");ready();redeem(key());assertEquals(new java.math.BigDecimal("0.30"),amount());}
    @Test void newUnknownPolicyAndLegacyArePendingNotZero(){snapshot();ready();redeem(key());assertEquals("WAIT_REVIEW",kind());assertNull(amount());assertEquals("WAIT_POLICY",jdbc.queryForObject("SELECT exemption FROM merchant_commission_entry",String.class));assertEquals(1L,((Map<?,?>)((Map<?,?>)new CommissionLedger(db).list(shop,1,20).get("summary")).get("test")).get("pending_count"));}
    @Test void historicalOrderNeverGetsCurrentPolicyRetroactively(){policy(old());ready();redeem(key());assertEquals("WAIT_REVIEW",kind());assertNull(amount());assertEquals("WAIT_LEGACY",jdbc.queryForObject("SELECT exemption FROM merchant_commission_entry",String.class));}
    @Test void couponFactsAreNeverChargedUntilCouponAllocationExists(){policy(old());jdbc.update("UPDATE `order` SET coupon_id=42");snapshot();ready();redeem(key());assertEquals("WAIT_REVIEW",kind());assertNull(amount());assertEquals("WAIT_COUPON",jdbc.queryForObject("SELECT exemption FROM merchant_commission_entry",String.class));}
    @Test void commissionWriteFailureRollsBackRedemptionAndArchive(){policy(old());snapshot();ready();jdbc.execute("CREATE TRIGGER reject_commission BEFORE INSERT ON merchant_commission_entry FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");String k=key();assertEquals(503,status(()->redeem(k)));assertEquals("PENDING_VERIFY",state());assertEquals(0,count("order_redemption"));assertEquals(0,count("merchant_commission_entry"));assertEquals(0,count("service_archive_job"));jdbc.execute("DROP TRIGGER reject_commission");redeem(k);assertEquals("COMPLETED",state());assertEquals(1,count("merchant_commission_entry"));}
    @Test void concurrentRedemptionAccruesOneFee()throws Exception{policy(old());snapshot();ready();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->redeem(key()));var b=pool.submit(()->redeem(key()));a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);assertEquals(1,count("merchant_commission_entry"));assertEquals(new java.math.BigDecimal("0.30"),amount());}finally{pool.shutdownNow();}}
    @Test void refundPrimitiveReversesOriginalAmountOnce(){policy(old());snapshot();ready();redeem(key());futureRefund();db.transactions.executeWithoutResult(tx->CommissionLedger.refunded(db,db.one("SELECT * FROM `order` WHERE id=1"),db.one("SELECT * FROM payment WHERE id=1"),db.one("SELECT * FROM refund WHERE id=1")));assertEquals(2,count("merchant_commission_entry"));assertEquals(new java.math.BigDecimal("-0.30"),jdbc.queryForObject("SELECT amount FROM merchant_commission_entry WHERE event_key='REFUND'",java.math.BigDecimal.class));assertNotNull(jdbc.queryForObject("SELECT reference_entry_id FROM merchant_commission_entry WHERE event_key='REFUND'",Long.class));assertEquals("0.00",((Map<?,?>)((Map<?,?>)new CommissionLedger(db).list(shop,1,20).get("summary")).get("test")).get("net"));}
    @Test void refundWithoutAccrualRecordsZeroNotInventedReversal(){ready();futureRefund();assertEquals("REFUND_NO_CHARGE",kind());assertEquals(new java.math.BigDecimal("0.00"),amount());assertNull(jdbc.queryForObject("SELECT reference_entry_id FROM merchant_commission_entry",Long.class));}
    @Test void refundUnknownAccrualStaysUnknown(){ready();redeem(key());futureRefund();assertEquals(2,count("merchant_commission_entry"));assertNull(jdbc.queryForObject("SELECT amount FROM merchant_commission_entry WHERE event_key='REFUND'",Object.class));assertEquals(1L,((Map<?,?>)((Map<?,?>)new CommissionLedger(db).list(shop,1,20).get("summary")).get("test")).get("pending_count"));}
    @Test void merchantScopeRoleSessionAndWhitelist(){ready();redeem(key());assertEquals(0L,new CommissionLedger(db).list(other,1,20).get("total"));var staff=new MerchantActor(shop.staffId(),1,"STAFF",shop.session(),shop.expires());assertEquals(403,status(()->new CommissionLedger(db).list(staff,1,20)));var data=new CommissionLedger(db).list(shop,1,1);assertEquals(1,((List<?>)data.get("items")).size());assertEquals(16,((Map<?,?>)((List<?>)data.get("items")).get(0)).size());for(String secret:List.of("staff_id","user_id","verify_code","channel_payment_no","signature_file"))assertFalse(db.json(data).contains(secret));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());assertEquals(401,status(()->new CommissionLedger(db).list(shop,1,20)));}
    @Test void trustedApprovalBackfillRepeatsWithoutInventingLegacyPolicies()throws Exception{policy(old());jdbc.update("DELETE FROM merchant_commission_policy");try(var c=jdbc.getDataSource().getConnection()){for(int i=0;i<2;i++)ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V026__commission_ledger.sql")));}assertEquals(1,count("merchant_commission_policy"));assertNotNull(CommissionLedger.policy(db,1,db.now()));assertNull(CommissionLedger.policy(db,2,db.now()));}

    @Test void exactNaturalMonthBoundaryIsChargeable(){Instant start=Instant.parse("2026-01-31T15:59:59Z");policy(start);Instant until=CommissionLedger.waivedUntil(start);jdbc.update("UPDATE `order` SET created_at=?",ReservationStore.time(until.minusSeconds(1)));snapshot();assertEquals(new java.math.BigDecimal("0.00"),jdbc.queryForObject("SELECT commission_amount FROM order_commission_snapshot",java.math.BigDecimal.class));jdbc.update("DELETE FROM order_commission_snapshot");jdbc.update("UPDATE `order` SET created_at=?",ReservationStore.time(until));snapshot();assertEquals(new java.math.BigDecimal("0.30"),jdbc.queryForObject("SELECT commission_amount FROM order_commission_snapshot",java.math.BigDecimal.class));}
    @Test void partialRefundAndChangedOriginalFactsCannotReverseFee(){policy(old());snapshot();ready();redeem(key());jdbc.update("UPDATE `order` SET status='CLOSED',close_reason='REFUND_CANCELLED'");jdbc.update("INSERT INTO refund(id,order_id,payment_id,amount,status,owner_consented_at,merchant_consented_at,refunded_at) VALUES(1,1,1,0.01,'SUCCEEDED',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())");assertEquals(409,status(()->db.transactions.executeWithoutResult(tx->CommissionLedger.refunded(db,db.one("SELECT * FROM `order` WHERE id=1"),db.one("SELECT * FROM payment WHERE id=1"),db.one("SELECT * FROM refund WHERE id=1")))));jdbc.update("UPDATE refund SET amount=10");jdbc.update("UPDATE merchant_commission_entry SET currency='USD'");assertEquals(409,status(()->db.transactions.executeWithoutResult(tx->CommissionLedger.refunded(db,db.one("SELECT * FROM `order` WHERE id=1"),db.one("SELECT * FROM payment WHERE id=1"),db.one("SELECT * FROM refund WHERE id=1")))));assertEquals(1,count("merchant_commission_entry"));}
    @Test void refundLedgerFailureRollsBackSupplierSuccessAndEvent(){ready();var owner=owner(99);var payments=new PaymentService(db,new ReservationExpiry(db),new PaymentChannels(new LocalTestPaymentChannel(LocalTestPaymentChannelTest.KEY,Clock.systemUTC())));var refunds=new OrderRefunds(db,payments,new RefundChannels(new LocalTestRefundChannel(LocalTestPaymentChannelTest.KEY,Clock.systemUTC(),"")));refunds.request(owner,key(),1,"取消");refunds.decide(shop,key(),1,1,"ACCEPT",null);var row=db.one("SELECT * FROM refund");long refund=ReservationStore.number(row,"id");var notice=new RefundChannel.Notice(key(),refund,row.get("refund_no").toString(),1,"supplier-refund","SUCCEEDED",new java.math.BigDecimal("10.00"),"CNY",db.now(),"a".repeat(64));var worker=new RefundRecovery(refunds,false);jdbc.execute("CREATE TRIGGER reject_commission BEFORE INSERT ON merchant_commission_entry FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");assertEquals(503,status(()->worker.notify(notice)));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM refund",String.class));assertEquals(0,count("refund_event"));assertEquals(0,count("merchant_commission_entry"));jdbc.execute("DROP TRIGGER reject_commission");worker.notify(notice);worker.notify(notice);assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM refund",String.class));assertEquals(1,count("refund_event"));assertEquals(1,count("merchant_commission_entry"));assertEquals("REFUND_NO_CHARGE",kind());}
}
