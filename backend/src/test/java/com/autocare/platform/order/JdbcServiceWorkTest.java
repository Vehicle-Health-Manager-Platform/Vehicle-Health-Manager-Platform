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
class JdbcServiceWorkTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;ReservationStore db;TechnicianAssignments service;ServiceWork work;MerchantActor shop,other;TechnicianActor tech,peer,foreign;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V014__service_work.sql")));}
    }
    @BeforeEach void setup(){
        for(String trigger:List.of("reject_dispatch_audit","reject_dispatch_transition","reject_dispatch_cache","reject_work_audit","reject_work_transition","reject_work_cache"))jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("order_dispute_record","order_dispute","service_evidence_file","service_report_submission","repair_protection","technician_report","file_object","pickup_check_file","technician_assignment","pickup_check","order_status_transition","audit_log","idempotency_record","auth_session","staff_wechat_identity","order","appointment_slot","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
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
    @Test void completeWorkCommitsOnlyAfterSignatureAndIsIdempotent(){
        String p=key(),r=key(),s=key();var protectedView=work.protect(shop,p,protection());assertEquals(protectedView,work.protect(shop,p,protection()));
        assertEquals("IN_SERVICE",state());var reportView=work.submit(tech,r,report());assertEquals(reportView,work.submit(tech,r,report()));assertEquals("IN_SERVICE",state());assertNull(jdbc.queryForObject("SELECT service_report_ready_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        var signed=work.sign(tech,s,signature());assertEquals(signed,work.sign(tech,s,signature()));assertEquals("PENDING_VERIFY",state());assertEquals("SIGNED",signed.path("data").path("report").path("status").asText());
        assertEquals(1,count("repair_protection"));assertEquals(1,count("technician_report"));assertEquals(5,count("service_evidence_file"));assertEquals(2,count("order_status_transition"));assertEquals(5,count("audit_log"));assertEquals(5,count("idempotency_record"));
        assertNotNull(jdbc.queryForObject("SELECT signed_at FROM technician_report",java.sql.Timestamp.class));assertNotNull(jdbc.queryForObject("SELECT service_report_ready_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        for(Runnable change:List.<Runnable>of(()->work.protect(shop,key(),protection()),()->work.submit(tech,key(),report()),()->work.sign(tech,key(),signature())))assertEquals(409,status(change));
        assertEquals(5,count("audit_log"));
        String view=db.json(work.detail(tech,1));for(String secret:List.of("technician_id","user_id","vehicle_id","phone","vin","verify_code","object_key","owner_id"))assertFalse(view.contains(secret),secret);
        assertEquals(1,work.fileOwner(tech,1,101).id());assertEquals(12,work.fileOwner(shop,1,105).id());assertEquals(404,status(()->work.fileOwner(tech,1,106)));
    }
    @Test void missingProtectionBlocksReportAndSignature(){assertEquals(43002,code(()->work.submit(tech,key(),report())));assertEquals(43002,code(()->work.sign(tech,key(),signature())));protectedOrder();assertEquals(43005,code(()->work.sign(tech,key(),signature())));assertEquals(0,count("technician_report"));}
    @Test void onlyCurrentShopAndAssignedTechnicianCanWriteReadOrAccess(){
        for(var actor:List.of(peer,foreign)){assertEquals(404,status(()->work.submit(actor,key(),report())));assertEquals(404,status(()->work.sign(actor,key(),signature())));assertEquals(404,status(()->work.detail(actor,1)));assertEquals(404,status(()->work.fileOwner(actor,1,101)));}
        assertEquals(404,status(()->work.protect(other,key(),protection())));assertEquals(404,status(()->work.detail(other,1)));assertEquals(404,status(()->work.fileOwner(other,1,101)));
    }
    @Test void checksDisputeConfirmationAndAcceptedAssignmentBeforeReplay(){
        protectedOrder();String k=key();work.submit(tech,k,report());jdbc.update("UPDATE pickup_check SET owner_confirm=2 WHERE order_id=1");assertEquals(43003,code(()->work.submit(tech,k,report())));
        jdbc.update("UPDATE pickup_check SET owner_confirm=3 WHERE order_id=1");work.submit(tech,k,report());jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");assertEquals(43007,code(()->work.submit(tech,k,report())));
        jdbc.update("UPDATE `order` SET status='IN_SERVICE' WHERE id=1");jdbc.update("INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,reason,from_status,opened_by,opened_at) VALUES(1,1,1,'test','IN_SERVICE',99,UTC_TIMESTAMP())");assertEquals(43007,code(()->work.sign(tech,key(),signature())));jdbc.update("DELETE FROM order_dispute");
        jdbc.update("UPDATE technician_assignment SET status='ASSIGNED',accepted_at=NULL WHERE order_id=1");assertEquals(43004,code(()->work.submit(tech,k,report())));
    }
    @Test void revokedAndUnboundIdentitiesCannotReplay(){submitted();String k=key();work.sign(tech,k,signature());jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED',unbound_at=UTC_TIMESTAMP() WHERE id=112");assertEquals(401,status(()->work.sign(tech,k,signature())));jdbc.update("UPDATE staff_wechat_identity SET status='ACTIVE',unbound_at=NULL WHERE id=112");jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",tech.session());assertEquals(401,status(()->work.sign(tech,k,signature())));}
    @Test void rejectsForeignUnsafeDeletedAndReusedFiles(){
        for(String update:List.of("owner_id=13","scan_status='INFECTED'","is_deleted=1","content_type='text/plain'","size_bytes=0")){
            jdbc.update("UPDATE file_object SET "+update+" WHERE id=101");assertEquals(422,status(()->work.protect(shop,key(),protection())));jdbc.update("UPDATE file_object SET owner_id=1,scan_status='CLEAN',is_deleted=0,content_type='image/png',size_bytes=100 WHERE id=101");}
        jdbc.update("INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES(1,101,'FRONT')");assertEquals(422,status(()->work.protect(shop,key(),protection())));jdbc.update("DELETE FROM pickup_check_file");protectedOrder();
        jdbc.update("UPDATE file_object SET owner_id=13 WHERE id=102");assertEquals(422,status(()->work.submit(tech,key(),report())));assertEquals(0,count("technician_report"));jdbc.update("UPDATE file_object SET owner_id=12 WHERE id=102");work.submit(tech,key(),report());
        jdbc.update("UPDATE file_object SET is_deleted=1 WHERE id=104");assertEquals(43005,code(()->work.sign(tech,key(),signature())));assertEquals("IN_SERVICE",state());jdbc.update("UPDATE file_object SET is_deleted=0 WHERE id=104");jdbc.update("UPDATE file_object SET content_type='image/jpeg' WHERE id=105");assertEquals(422,status(()->work.sign(tech,key(),signature())));
    }
    @Test void noFaultAndNoPartsMustBeExplicit(){protectedOrder();var b=(com.fasterxml.jackson.databind.node.ObjectNode)report();b.put("no_fault_parts",true);b.set("fault_part_photos",db.mapper.createArrayNode());b.put("fault_analysis","无故障件");b.put("no_parts",true);b.set("parts_used",db.mapper.createArrayNode());work.submit(tech,key(),b);work.sign(tech,key(),signature());assertEquals("PENDING_VERIFY",state());assertEquals(4,count("service_evidence_file"));}
    @Test void legacyProtectionAndReportsAreNotPromoted(){jdbc.update("INSERT INTO repair_protection(order_id,items,uploaded_at,photo_url) VALUES(1,?,UTC_TIMESTAMP(),'https://legacy.invalid/image')",db.json(List.of("SEAT_COVER","STEERING_COVER")));assertEquals(43002,code(()->work.submit(tech,key(),report())));jdbc.update("DELETE FROM repair_protection");protectedOrder();jdbc.update("INSERT INTO technician_report(order_id,technician_id,status) VALUES(1,12,1)");assertEquals(409,status(()->work.submit(tech,key(),report())));assertEquals(43005,code(()->work.sign(tech,key(),signature())));}
    @Test void sameKeyCannotChangeReport(){protectedOrder();String k=key();work.submit(tech,k,report());var changed=(com.fasterxml.jackson.databind.node.ObjectNode)report();changed.put("work_hours",60);assertEquals(400,status(()->work.submit(tech,k,changed)));assertEquals(45,jdbc.queryForObject("SELECT work_hours FROM technician_report",Integer.class));}
    @Test void signatureTransactionRollsBackOnAuditTransitionAndCacheFailures(){
        submitted();int audit=count("audit_log"),cache=count("idempotency_record");
        for(String table:List.of("audit_log","order_status_transition","idempotency_record")){
            String trigger=table.equals("audit_log")?"reject_work_audit":table.equals("order_status_transition")?"reject_work_transition":"reject_work_cache";
            String sql="CREATE TRIGGER "+trigger+" BEFORE "+(table.equals("idempotency_record")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'";jdbc.execute(sql);
            assertEquals(503,status(()->work.sign(tech,key(),signature())));assertEquals("IN_SERVICE",state());assertEquals(0,jdbc.queryForObject("SELECT status FROM technician_report",Integer.class));assertNull(jdbc.queryForObject("SELECT signed_at FROM technician_report",java.sql.Timestamp.class));assertEquals(4,count("service_evidence_file"));assertEquals(audit,count("audit_log"));assertEquals(cache,count("idempotency_record"));assertEquals(1,count("order_status_transition"));jdbc.execute("DROP TRIGGER "+trigger);
        }
        work.sign(tech,key(),signature());assertEquals("PENDING_VERIFY",state());
    }
    @Test void protectionAndReportTransactionsDoNotLeaveEvidenceAfterAuditFailure(){
        jdbc.execute("CREATE TRIGGER reject_work_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");
        assertEquals(503,status(()->work.protect(shop,key(),protection())));assertEquals(0,count("repair_protection"));assertEquals(0,count("service_evidence_file"));assertEquals(2,count("idempotency_record"));jdbc.execute("DROP TRIGGER reject_work_audit");protectedOrder();
        jdbc.execute("CREATE TRIGGER reject_work_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->work.submit(tech,key(),report())));assertEquals(0,count("technician_report"));assertEquals(0,count("service_report_submission"));assertEquals(1,count("service_evidence_file"));assertEquals(3,count("idempotency_record"));jdbc.execute("DROP TRIGGER reject_work_audit");work.submit(tech,key(),report());assertEquals(1,count("technician_report"));
    }
    @Test void concurrentSignaturesCommitOnlyOnce()throws Exception{
        submitted();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{
            var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();try{work.sign(tech,key(),signature());return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));start.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(30,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,409),codes);assertEquals(2,count("order_status_transition"));assertEquals(5,count("audit_log"));assertEquals(5,count("service_evidence_file"));
        }finally{pool.shutdownNow();}
    }
}
