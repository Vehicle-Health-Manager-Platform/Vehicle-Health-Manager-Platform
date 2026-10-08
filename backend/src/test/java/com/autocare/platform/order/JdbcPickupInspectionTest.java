package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.UploadHttpException;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
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
class JdbcPickupInspectionTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;final ObjectMapper mapper=new ObjectMapper();ReservationStore db;PickupInspection service;MerchantActor shop,other;VehicleOwner owner,foreign;
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        try(var c=source.getConnection()){for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V004__upload_http.sql","V007__reservation_orders.sql","V008__payment_foundation.sql","V009__order_fulfillment_states.sql","V010__pickup_inspection.sql","V010__pickup_inspection.sql","V011__pickup_owner_decision.sql","V011__pickup_owner_decision.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));}
    }
    @BeforeEach void setup(){
        for(String trigger:List.of("reject_pickup_audit","reject_pickup_cache"))jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("pickup_check_file","pickup_check","order_status_transition","audit_log","idempotency_record","auth_rate_limit","auth_session","file_object","vehicle_archive","order","appointment_slot","staff_account","merchant","vehicle","user"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);service=new PickupInspection(db);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'测试店A','合成地址',1),(2,2,'测试店B','合成地址',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','A','ACTIVE'),(2,2,'MERCHANT','B','ACTIVE')");
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'owner',1),(2,'foreign',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(1,1,100)");
        shop=merchant(1,1);other=merchant(2,2);owner=owner(1);foreign=owner(2);
        jdbc.update("INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity,reserved_count) VALUES(1,1,1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),1,1)");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,verify_code,slot_id,appointment_snapshot) VALUES(1,'synthetic',1,1,1,10,10,'PAID','012345',1,?)",db.json(Map.of("starts_at",Instant.now().toString(),"ends_at",Instant.now().plusSeconds(3600).toString())));
        for(int i=1;i<=7;i++)jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'staff_account',1,?,'image/png',100,'CLEAN')",i,"test/"+i);
    }
    MerchantActor merchant(long staff,long store){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'MERCHANT','merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,staff,store,UUID.randomUUID().toString());return new MerchantActor(staff,store,session,Instant.now().plusSeconds(3600));}
    VehicleOwner owner(long id){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,id,UUID.randomUUID().toString());return new VehicleOwner(id,session,Instant.now().plusSeconds(3600));}
    com.fasterxml.jackson.databind.node.ObjectNode body(){var photos=new LinkedHashMap<String,Long>();for(int i=0;i<7;i++)photos.put(PickupInput.SLOTS.get(i),(long)i+1);return mapper.valueToTree(Map.of("order_id",1,"appointment_code","012345","photos",photos,"mileage",110,"fuel_level","HALF","damage_status","NONE","damages",List.of()));}
    String key(){return UUID.randomUUID().toString();}
    JsonNode submit(String key,JsonNode body){return service.submit(shop,key,PickupInput.parse(body));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    String state(){return jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class);}
    @Test void realSubmissionCommitsEvidenceStateAndBothAuditsAndReplays(){
        String key=key();var result=submit(key,body());assertEquals(result,submit(key,body()));
        assertEquals("RECEIVED",state());assertEquals(1,count("pickup_check"));assertEquals(7,count("pickup_check_file"));assertEquals(1,count("order_status_transition"));assertEquals(1,count("audit_log"));assertEquals(1,count("idempotency_record"));
        assertEquals(10,result.path("data").path("mileage_delta").asInt());assertEquals(0,result.path("data").path("owner_confirm").asInt());
        assertFalse(result.toString().contains("012345"));assertFalse(jdbc.queryForObject("SELECT CONCAT(before_state,after_state) FROM audit_log",String.class).contains("012345"));
        assertEquals(service.detail(owner,1),service.detail(shop,1));assertEquals("staff_account",service.fileOwner(owner,1,1).type());
        assertEquals(1,db.occupied(1,db.now()));assertEquals(100,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=1",Integer.class));
        assertEquals(43003,assertThrows(FulfillmentConflict.class,()->new OrderFulfillment(db,new MerchantOrders(db)).apply(shop,key(),1,OrderStatus.START_SERVICE,null)).code);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->submit(key(),body())).getStatusCode().value());
        var changed=body().put("mileage",111);assertEquals(400,assertThrows(ResponseStatusException.class,()->submit(key,changed)).getStatusCode().value());
    }
    @Test void ownerConfirmIsPrivateIdempotentAndEnablesAssignmentGuard(){
        submit(key(),body());String decisionKey=key();
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.decide(foreign,key(),1,"CONFIRM",null)).getStatusCode().value());
        var accepted=service.decide(owner,decisionKey,1,"CONFIRM",null);
        assertEquals(accepted,service.decide(owner,decisionKey,1,"CONFIRM",null));
        assertEquals("RECEIVED",state());assertEquals(1,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE order_id=1",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT owner_confirmed_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        assertEquals(1,count("order_status_transition"));assertEquals(2,count("audit_log"));
        assertEquals(43004,assertThrows(FulfillmentConflict.class,()->new OrderFulfillment(db,new MerchantOrders(db)).apply(shop,key(),1,OrderStatus.START_SERVICE,null)).code);
        assertEquals(40905,assertThrows(FulfillmentConflict.class,()->service.decide(owner,key(),1,"DISPUTE","不同意")).code);
    }
    @Test void ownerDisputeBlocksWorkAndWritesBothAudits(){
        String prefix="车门已有划痕记录不准确";String reason=prefix+"异".repeat(500-prefix.length());
        submit(key(),body());service.decide(owner,key(),1,"DISPUTE",reason);
        assertEquals("DISPUTED",state());assertEquals(2,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE order_id=1",Integer.class));
        assertNull(jdbc.queryForObject("SELECT owner_confirmed_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        assertEquals(reason,service.detail(shop,1).get("dispute_reason"));
        assertEquals(reason,jdbc.queryForObject("SELECT note FROM order_status_transition WHERE action='ORDER_PICKUP_DISPUTE'",String.class));
        assertEquals(2,count("order_status_transition"));assertEquals(2,count("audit_log"));
        assertEquals(40905,assertThrows(FulfillmentConflict.class,()->new OrderFulfillment(db,new MerchantOrders(db)).apply(shop,key(),1,OrderStatus.START_SERVICE,null)).code);
        assertEquals(40905,assertThrows(FulfillmentConflict.class,()->service.decide(owner,key(),1,"CONFIRM",null)).code);
    }
    @Test void decisionAuditFailureRollsBackDecisionAndStatus(){
        submit(key(),body());jdbc.execute("CREATE TRIGGER reject_pickup_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.decide(owner,key(),1,"DISPUTE","照片不符")).getStatusCode().value());
        assertEquals("RECEIVED",state());assertEquals(0,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE order_id=1",Integer.class));
        assertEquals(1,count("order_status_transition"));jdbc.execute("DROP TRIGGER reject_pickup_audit");
    }
    @Test void rejectsInvalidReasonsAndRevokedReplay(){
        submit(key(),body());
        for(String note:Arrays.asList(null," ","x".repeat(501)))assertEquals(400,assertThrows(ResponseStatusException.class,()->service.decide(owner,key(),1,"DISPUTE",note)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.decide(owner,key(),1,"CONFIRM","理由")).getStatusCode().value());
        String decisionKey=key();service.decide(owner,decisionKey,1,"CONFIRM",null);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.decide(owner,decisionKey,1,"DISPUTE","同键换正文")).getStatusCode().value());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.decide(owner,decisionKey,1,"CONFIRM",null)).getStatusCode().value());
    }
    @Test void concurrentConfirmAndDisputeCommitOnlyOneDecision()throws Exception{
        submit(key(),body());var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var jobs=new ArrayList<Future<Integer>>();for(String decision:List.of("CONFIRM","DISPUTE"))jobs.add(pool.submit(()->{gate.await();try{service.decide(owner,key(),1,decision,decision.equals("DISPUTE")?"照片不符":null);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            gate.countDown();var outcomes=new HashSet<Integer>();for(var job:jobs)outcomes.add(job.get(20,TimeUnit.SECONDS));assertEquals(Set.of(200,409),outcomes);assertEquals(2,count("audit_log"));
            int decision=jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE order_id=1",Integer.class);assertEquals(decision==1?"RECEIVED":"DISPUTED",state());assertEquals(decision==1?1:2,count("order_status_transition"));
        }finally{pool.shutdownNow();}
    }
    @Test void isolatesShopOwnerAndRevokedSession(){
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.submit(other,key(),PickupInput.parse(body()))).getStatusCode().value());
        submit(key(),body());assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail(foreign,1)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->service.fileOwner(other,1,1)).getStatusCode().value());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());assertEquals(401,assertThrows(ResponseStatusException.class,()->service.detail(shop,1)).getStatusCode().value());
    }
    @Test void wrongCodeFailuresSurviveRollbackAndRateLimit(){
        var wrong=body().put("appointment_code","999999");for(int i=0;i<5;i++)assertEquals(422,assertThrows(ResponseStatusException.class,()->submit(key(),wrong)).getStatusCode().value());
        assertEquals(429,assertThrows(UploadHttpException.class,()->submit(key(),body())).status());assertEquals(0,count("pickup_check"));assertEquals(0,count("audit_log"));assertEquals("PAID",state());
    }
    @Test void requiresOwnedCleanUnusedFilesAndSevenUniqueSlots(){
        for(String update:List.of("UPDATE file_object SET owner_type='user' WHERE id=1","UPDATE file_object SET owner_type='staff_account',owner_id=2 WHERE id=1","UPDATE file_object SET owner_id=1,scan_status='PENDING' WHERE id=1","UPDATE file_object SET scan_status='CLEAN',is_deleted=1 WHERE id=1")){
            jdbc.update(update);assertEquals(422,assertThrows(ResponseStatusException.class,()->submit(key(),body())).getStatusCode().value());assertEquals(0,count("pickup_check"));
        }
        var incomplete=body();((com.fasterxml.jackson.databind.node.ObjectNode)incomplete.get("photos")).remove("ROOF");assertThrows(ResponseStatusException.class,()->PickupInput.parse(incomplete));
        var duplicate=body();((com.fasterxml.jackson.databind.node.ObjectNode)duplicate.get("photos")).put("ROOF",1);assertThrows(ResponseStatusException.class,()->PickupInput.parse(duplicate));
    }
    @Test void comparesLatestArchiveAndRequiresRollbackAndArrivalReasons(){
        jdbc.update("INSERT INTO vehicle_archive(vehicle_id,archive_type,input_type,content,recorded_at) VALUES(1,1,3,'{\"mileage\":120}',UTC_TIMESTAMP())");
        assertEquals(120,((Map<?,?>)service.context(shop,1).get("mileage_baseline")).get("mileage"));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->submit(key(),body())).getStatusCode().value());
        jdbc.update("UPDATE `order` SET appointment_snapshot=? WHERE id=1",db.json(Map.of("starts_at",Instant.now().minusSeconds(10000).toString())));
        var b=body().put("mileage_reason","仪表盘更换");assertEquals(400,assertThrows(ResponseStatusException.class,()->submit(key(),b)).getStatusCode().value());
        assertEquals(-10,submit(key(),b.put("arrival_reason","提前到店沟通")).path("data").path("mileage_delta").asInt());
    }
    @Test void auditAndCacheFailureRollBackAllEvidence(){
        for(String table:List.of("audit_log","idempotency_record")){
            String trigger=table.equals("audit_log")?"reject_pickup_audit":"reject_pickup_cache";
            jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE "+(table.equals("audit_log")?"INSERT":"UPDATE")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private failure'");
            assertEquals(503,assertThrows(ResponseStatusException.class,()->submit(key(),body())).getStatusCode().value());assertEquals("PAID",state());for(String t:List.of("pickup_check","pickup_check_file","order_status_transition","audit_log","idempotency_record"))assertEquals(0,count(t));jdbc.execute("DROP TRIGGER "+trigger);
        }
        submit(key(),body());assertEquals("RECEIVED",state());
    }
    @Test void concurrentDistinctKeysOnlyCommitOnePickup()throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var jobs=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{gate.await();try{submit(key(),body());return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));gate.countDown();var outcomes=new HashSet<Integer>();for(var job:jobs)outcomes.add(job.get(20,TimeUnit.SECONDS));assertEquals(Set.of(200,409),outcomes);assertEquals(1,count("pickup_check"));assertEquals(7,count("pickup_check_file"));assertEquals(1,count("audit_log"));}finally{pool.shutdownNow();}
    }
}
