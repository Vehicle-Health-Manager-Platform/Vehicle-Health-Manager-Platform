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
class JdbcTechnicianAssignmentsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;ReservationStore db;TechnicianAssignments service;MerchantActor shop,other;TechnicianActor tech,peer,foreign;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V012__technician_dispatch.sql")));}
    }
    @BeforeEach void setup(){
        for(String trigger:List.of("reject_dispatch_audit","reject_dispatch_transition","reject_dispatch_cache"))jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("technician_assignment","pickup_check","order_status_transition","audit_log","idempotency_record","auth_session","staff_wechat_identity","order","appointment_slot","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,new ObjectMapper(),new WriteIntegrityService(jdbc,new ObjectMapper(),manager),Clock.systemUTC(),manager);service=new TechnicianAssignments(db,"test-app");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-A','test',1),(2,2,'synthetic-B','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','shop-A','ACTIVE'),(2,2,'MERCHANT','shop-B','ACTIVE'),(12,1,'TECHNICIAN','tech-A','ACTIVE'),(13,1,'TECHNICIAN','tech-peer','ACTIVE'),(14,2,'TECHNICIAN','tech-B','ACTIVE'),(15,1,'TECHNICIAN','unbound','ACTIVE')");
        for(int i=12;i<=14;i++)jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(?,'test-app',?,?)",i+100,"synthetic-"+i,i);
        shop=merchant(1,1);other=merchant(2,2);tech=technician(12,1);peer=technician(13,1);foreign=technician(14,2);
        jdbc.update("INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(1,1,1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),10)");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot) VALUES(1,'synthetic-job',99,99,1,10,10,'RECEIVED',1,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'123456',?,?)",
            db.json(Map.of("project_name","inspection","service_content","synthetic","phone","private","vin","private")),db.json(Map.of("slot_id",1,"starts_at",Instant.now().toString(),"verify_code","private")));
        jdbc.update("INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,confirm_at) VALUES(1,1,1,1,UTC_TIMESTAMP())");
    }
    String key(){return UUID.randomUUID().toString();}
    MerchantActor merchant(long id,long shop){String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'MERCHANT','merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,shop,key());return new MerchantActor(id,shop,s,Instant.now().plusSeconds(3600));}
    TechnicianActor technician(long id,long shop){String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'TECHNICIAN','test-app',?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,shop,id+100,key());return new TechnicianActor(id,shop,id+100,"test-app",s,Instant.now().plusSeconds(3600));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    int code(Runnable action){return assertThrows(FulfillmentConflict.class,action::run).code;}
    String state(){return jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class);}
    @Test void firstDispatchAndAcceptanceCommitWithIdempotencyAndMinimalProjection(){
        assertNull(service.merchantDetail(shop,1).get("assignment"));String k=key();var assigned=service.assign(shop,k,1,12);assertEquals(assigned,service.assign(shop,k,1,12));
        assertEquals("RECEIVED",state());assertEquals(1,count("technician_assignment"));assertEquals(1,count("audit_log"));assertEquals(0,count("order_status_transition"));
        assertEquals(1,jdbc.queryForObject("SELECT assigned_by FROM technician_assignment",Integer.class));assertNotNull(jdbc.queryForObject("SELECT assigned_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        assertFalse(service.assign(shop,key(),1,12).path("data").path("changed").asBoolean());assertEquals(1,count("audit_log"));
        var detail=service.detail(tech,1);assertEquals(true,detail.get("can_accept"));String json=db.json(detail);
        for(String field:List.of("user_id","vehicle_id","phone","vin","plate_no","verify_code","private","payment"))assertFalse(json.contains(field),field);
        assertEquals(0,count("repair_protection")); // Protection does not gate A5.
        String acceptedKey=key();var accepted=service.accept(tech,acceptedKey,1);assertEquals(accepted,service.accept(tech,acceptedKey,1));
        assertEquals("IN_SERVICE",state());assertEquals("ACCEPTED",accepted.path("data").path("assignment_status").asText());
        assertNotNull(jdbc.queryForObject("SELECT accepted_at FROM technician_assignment",java.sql.Timestamp.class));
        assertEquals(1,count("order_status_transition"));assertEquals(2,count("audit_log"));assertFalse(service.accept(tech,key(),1).path("data").path("changed").asBoolean());assertEquals(2,count("audit_log"));
        assertEquals(false,service.detail(tech,1).get("can_accept"));assertEquals(1L,service.list(tech,"ACCEPTED",1,20).get("total"));
        assertEquals(40905,code(()->service.assign(shop,key(),1,12)));
    }
    @Test void candidatesAreBoundActiveCurrentAppStaffFromOwnShop(){
        assertEquals(2L,service.candidates(shop,1,20).get("total"));assertEquals(1L,service.candidates(other,1,20).get("total"));
        assertEquals(1,((List<?>)service.candidates(shop,2,1).get("items")).size());
        for(long id:List.of(1L,14L,15L,999L))assertEquals(404,status(()->service.assign(shop,key(),1,id)));
        for(String update:List.of("UPDATE staff_account SET status='DISABLED' WHERE id=12","UPDATE staff_account SET status='ACTIVE',is_deleted=1 WHERE id=12","UPDATE staff_account SET is_deleted=0,role='MERCHANT' WHERE id=12","UPDATE staff_account SET role='TECHNICIAN' WHERE id=12;")){
            jdbc.update(update);if(!update.endsWith(";"))assertEquals(404,status(()->service.assign(shop,key(),1,12)));}
        jdbc.update("UPDATE staff_wechat_identity SET app_id='other' WHERE id=112");assertEquals(404,status(()->service.assign(shop,key(),1,12)));
        jdbc.update("UPDATE staff_wechat_identity SET app_id='test-app',status='REVOKED' WHERE id=112");assertEquals(404,status(()->service.assign(shop,key(),1,12)));assertEquals(1L,service.candidates(shop,1,20).get("total"));
    }
    @Test void stateAndEvidenceCannotBeBypassedWithTimestamps(){
        for(String state:List.of("PAID","DISPUTED","IN_SERVICE","CLOSED")){jdbc.update("UPDATE `order` SET status=? WHERE id=1",state);assertEquals(40905,code(()->service.assign(shop,key(),1,12)));}
        jdbc.update("UPDATE `order` SET status='RECEIVED',check_in_completed_at=NULL WHERE id=1");assertEquals(43001,code(()->service.assign(shop,key(),1,12)));
        jdbc.update("UPDATE `order` SET check_in_completed_at=UTC_TIMESTAMP(),owner_confirmed_at=NULL WHERE id=1");assertEquals(43003,code(()->service.assign(shop,key(),1,12)));
        jdbc.update("UPDATE `order` SET owner_confirmed_at=UTC_TIMESTAMP() WHERE id=1");
        for(Object decision:Arrays.asList(0,2,null)){jdbc.update("UPDATE pickup_check SET owner_confirm=? WHERE order_id=1",decision);assertEquals(43003,code(()->service.assign(shop,key(),1,12)));}
        jdbc.update("UPDATE pickup_check SET owner_confirm=1,confirm_at=NULL WHERE order_id=1");assertEquals(43003,code(()->service.assign(shop,key(),1,12)));
        jdbc.update("DELETE FROM pickup_check");assertEquals(43001,code(()->service.assign(shop,key(),1,12)));assertEquals(0,count("technician_assignment"));assertEquals(0,count("audit_log"));
    }
    @Test void isolatesMerchantAndTechnicianAndRechecksAcceptanceEvidence(){
        assertEquals(404,status(()->service.assign(other,key(),1,14)));assertEquals(404,status(()->service.merchantDetail(other,1)));
        service.assign(shop,key(),1,12);for(var actor:List.of(peer,foreign)){assertEquals(0L,service.list(actor,null,1,20).get("total"));assertEquals(404,status(()->service.detail(actor,1)));assertEquals(404,status(()->service.accept(actor,key(),1)));}
        jdbc.update("UPDATE pickup_check SET owner_confirm=2 WHERE order_id=1");assertEquals(false,service.detail(tech,1).get("can_accept"));assertEquals(43003,code(()->service.accept(tech,key(),1)));
        jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");assertEquals(40905,code(()->service.accept(tech,key(),1)));assertEquals(0,count("order_status_transition"));
    }
    @Test void differentKeyCannotReassignAndSameKeyCannotChangeBody(){String k=key();service.assign(shop,k,1,12);assertEquals(400,status(()->service.assign(shop,k,1,13)));assertEquals(40905,code(()->service.assign(shop,key(),1,13)));assertEquals(12,jdbc.queryForObject("SELECT technician_id FROM technician_assignment",Integer.class));assertEquals(1,count("audit_log"));}
    @Test void cachedSuccessStillRequiresActiveSessionAndBinding(){
        String k=key();service.assign(shop,k,1,12);String a=key();service.accept(tech,a,1);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",tech.session());assertEquals(401,status(()->service.accept(tech,a,1)));
        jdbc.update("UPDATE auth_session SET revoked_at=NULL WHERE id=?",tech.session());jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED',unbound_at=UTC_TIMESTAMP() WHERE id=112");assertEquals(401,status(()->service.accept(tech,a,1)));assertEquals(404,status(()->service.assign(shop,k,1,12)));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());assertEquals(401,status(()->service.assign(shop,k,1,12)));
    }
    @Test void everyTechnicianIdentityDimensionIsRevalidated(){
        service.assign(shop,key(),1,12);
        for(String sql:List.of("UPDATE auth_session SET merchant_id=2 WHERE id=?","UPDATE auth_session SET binding_id=113 WHERE id=?","UPDATE auth_session SET app_id='other' WHERE id=?","UPDATE auth_session SET role='MERCHANT' WHERE id=?","UPDATE auth_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=?")){
            jdbc.update(sql,tech.session());assertEquals(401,status(()->service.detail(tech,1)));jdbc.update("UPDATE auth_session SET merchant_id=1,binding_id=112,app_id='test-app',role='TECHNICIAN',expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE id=?",tech.session());}
        jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=12");assertEquals(401,status(()->service.accept(tech,key(),1)));jdbc.update("UPDATE staff_account SET status='ACTIVE' WHERE id=12");jdbc.update("UPDATE merchant SET status=0 WHERE id=1");assertEquals(401,status(()->service.detail(tech,1)));
    }
    @Test void legacyAssignmentsFailClosedWithoutInventingEvidence(){
        jdbc.update("INSERT INTO technician_assignment(order_id,merchant_id,technician_id) VALUES(1,1,12)");assertEquals(40905,code(()->service.assign(shop,key(),1,12)));assertEquals(40905,code(()->service.merchantDetail(shop,1)));assertEquals(40905,code(()->service.detail(tech,1)));
        jdbc.update("UPDATE technician_assignment SET assigned_by=1,technician_id=14");jdbc.update("UPDATE `order` SET assigned_at=UTC_TIMESTAMP() WHERE id=1");assertEquals(40905,code(()->service.merchantDetail(shop,1)));
        jdbc.update("UPDATE technician_assignment SET technician_id=12,is_deleted=1");assertEquals(40905,code(()->service.assign(shop,key(),1,12)));assertEquals(404,status(()->service.accept(tech,key(),1)));
        jdbc.update("DELETE FROM technician_assignment");assertEquals(40905,code(()->service.merchantDetail(shop,1)));assertEquals(40905,code(()->service.assign(shop,key(),1,12)));
    }
    @Test void dispatchAuditAndCacheFailuresRollBackAndOriginalKeyCanRetry(){
        for(String trigger:List.of("CREATE TRIGGER reject_dispatch_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'","CREATE TRIGGER reject_dispatch_cache BEFORE UPDATE ON idempotency_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'")){
            setup();String k=key();jdbc.execute(trigger);assertEquals(503,status(()->service.assign(shop,k,1,12)));assertEquals(0,count("technician_assignment"));assertEquals(0,count("audit_log"));assertEquals(0,count("idempotency_record"));assertNull(jdbc.queryForObject("SELECT assigned_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
            jdbc.execute("DROP TRIGGER IF EXISTS reject_dispatch_audit");jdbc.execute("DROP TRIGGER IF EXISTS reject_dispatch_cache");service.assign(shop,k,1,12);assertEquals(1,count("technician_assignment"));}
    }
    @Test void acceptanceTransitionAuditAndCacheFailuresRollBackTogether(){
        for(String table:List.of("order_status_transition","audit_log","idempotency_record")){
            setup();service.assign(shop,key(),1,12);String k=key(),name=table.equals("order_status_transition")?"reject_dispatch_transition":table.equals("audit_log")?"reject_dispatch_audit":"reject_dispatch_cache";
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE "+(table.equals("idempotency_record")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
            assertEquals(503,status(()->service.accept(tech,k,1)));assertEquals("RECEIVED",state());assertEquals("ASSIGNED",jdbc.queryForObject("SELECT status FROM technician_assignment",String.class));assertNull(jdbc.queryForObject("SELECT accepted_at FROM technician_assignment",java.sql.Timestamp.class));assertEquals(0,count("order_status_transition"));assertEquals(1,count("audit_log"));assertEquals(1,count("idempotency_record"));
            jdbc.execute("DROP TRIGGER "+name);service.accept(tech,k,1);assertEquals("IN_SERVICE",state());}
    }
    List<Integer> concurrent(Callable<Integer> first,Callable<Integer> second)throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return first.call();});var b=pool.submit(()->{gate.await();return second.call();});gate.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    int outcome(Runnable run){try{run.run();return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}
    @Test void twoTechniciansRaceForOneAssignmentAndAcceptanceOccursOnce()throws Exception{
        var results=concurrent(()->outcome(()->service.assign(shop,key(),1,12)),()->outcome(()->service.assign(shop,key(),1,13)));assertEquals(Set.of(200,409),new HashSet<>(results));assertEquals(1,count("technician_assignment"));assertEquals(1,count("audit_log"));
        var winner=jdbc.queryForObject("SELECT technician_id FROM technician_assignment",Long.class)==12?tech:peer;
        assertEquals(List.of(200,200),concurrent(()->outcome(()->service.accept(winner,key(),1)),()->outcome(()->service.accept(winner,key(),1))));assertEquals(1,count("order_status_transition"));assertEquals(2,count("audit_log"));
    }
    @Test void revocationAndAcceptanceUseCompatibleLocksAndReplaysFailAfterwards()throws Exception{
        service.assign(shop,key(),1,12);String k=key();var results=concurrent(()->outcome(()->service.accept(tech,k,1)),()->{db.transactions.execute(tx->{new StaffCodeOperations(jdbc).revoke(12);return null;});return 200;});
        assertTrue(results.get(0)==200 || results.get(0)==401);assertEquals(200,results.get(1));assertEquals(401,status(()->service.accept(tech,k,1)));assertEquals(1,count("technician_assignment"));assertEquals(results.get(0)==200?1:0,count("order_status_transition"));
    }
    @Test void migrationRepeatPreservesExistingAcceptedAssignment()throws Exception{
        service.assign(shop,key(),1,12);service.accept(tech,key(),1);var prior=jdbc.queryForMap("SELECT * FROM technician_assignment");
        try(var c=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V012__technician_dispatch.sql")));}
        assertEquals(prior,jdbc.queryForMap("SELECT * FROM technician_assignment"));assertEquals(1,count("technician_assignment"));
    }
}
