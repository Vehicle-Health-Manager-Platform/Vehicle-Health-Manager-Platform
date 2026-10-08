package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
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

/**
 * 争议处理、车主复核与恢复条件的真实 MySQL 验证。
 *
 * <p>夹具直接构造"已经提出异议"的订单，A4 建立争议单的链路由
 * {@code JdbcPickupInspectionTest} 覆盖，避免两处重复造七图接车单。
 */
@Testcontainers
class JdbcOrderDisputesTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;static JdbcTemplate admin;ReservationStore db;OrderDisputes service;TechnicianAssignments dispatch;
    MerchantActor shop,other;VehicleOwner owner,foreign;TechnicianActor tech;
    static final String REASON="车门划痕记录与实车不符";
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        // 死锁报告与全局状态只有 PROCESS 权限可读，业务连接（test 用户）不够。
        admin=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword()));
        try(var c=source.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V013__dispute_resolution.sql")));}
    }
    @BeforeEach void setup(){
        for(String trigger:List.of("reject_dispute_audit","reject_dispute_cache"))jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(String table:List.of("order_dispute_record","order_dispute","technician_assignment","pickup_check","order_status_transition","audit_log","idempotency_record","auth_session","staff_wechat_identity","order","appointment_slot","staff_account","merchant","user"))
            jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        db=new ReservationStore(jdbc,new ObjectMapper(),new WriteIntegrityService(jdbc,new ObjectMapper(),manager),Clock.systemUTC(),manager);
        service=new OrderDisputes(db);dispatch=new TechnicianAssignments(db,"test-app");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-A','test',1),(2,2,'synthetic-B','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','shop-A','ACTIVE'),(2,2,'MERCHANT','shop-B','ACTIVE'),(12,1,'TECHNICIAN','tech-A','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(112,'test-app','synthetic-12',12)");
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'owner',1),(2,'foreign',1)");
        shop=merchant(1,1);other=merchant(2,2);owner=owner(1);foreign=owner(2);tech=technician(12);
        jdbc.update("INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(1,1,1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),10)");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,verify_code,appointment_snapshot) VALUES(1,'synthetic-job',1,1,1,10,10,'DISPUTED',1,UTC_TIMESTAMP(),'123456',?)",
            db.json(Map.of("slot_id",1,"starts_at",Instant.now().toString(),"verify_code","private")));
        jdbc.update("INSERT INTO pickup_check(id,order_id,merchant_id,staff_id,mileage,owner_confirm,confirm_at,dispute_reason) VALUES(1,1,1,1,110,2,UTC_TIMESTAMP(),?)",REASON);
        jdbc.update("INSERT INTO order_dispute(id,order_id,merchant_id,pickup_check_id,reason,from_status,status,opened_by,opened_at) VALUES(1,1,1,1,?, 'RECEIVED','OPEN',1,UTC_TIMESTAMP())",REASON);
        // 历史异议：订单在争议里但没有争议单，本阶段之前的形态。
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at) VALUES(2,'legacy-job',1,1,1,10,10,'DISPUTED',NULL,UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO pickup_check(id,order_id,merchant_id,staff_id,mileage,owner_confirm,confirm_at,dispute_reason) VALUES(2,2,1,1,110,2,UTC_TIMESTAMP(),'历史异议')");
    }
    MerchantActor merchant(long staff,long store){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'MERCHANT','merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,staff,store,UUID.randomUUID().toString());return new MerchantActor(staff,store,session,Instant.now().plusSeconds(3600));}
    VehicleOwner owner(long id){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,id,UUID.randomUUID().toString());return new VehicleOwner(id,session,Instant.now().plusSeconds(3600));}
    TechnicianActor technician(long id){String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'TECHNICIAN','test-app',1,112,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,id,UUID.randomUUID().toString());return new TechnicianActor(id,1,112,"test-app",session,Instant.now().plusSeconds(3600));}
    String key(){return UUID.randomUUID().toString();}
    static int number(Map<String,Object> row,String name){return ((Number)row.get(name)).intValue();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    int countWhere(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args);}
    String state(long id){return jdbc.queryForObject("SELECT status FROM `order` WHERE id=?",String.class,id);}
    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    int code(Runnable action){return assertThrows(FulfillmentConflict.class,action::run).code;}
    Map<String,Object> data(com.fasterxml.jackson.databind.JsonNode node){return db.mapper.convertValue(node.path("data"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}
    @SuppressWarnings("unchecked") Map<String,Object> disputeView(Object actor,long order){return (Map<String,Object>)new PickupInspection(db).detail(actor,order).get("dispute");}
    String deadlockSection(){
        String report=admin.queryForObject("SHOW ENGINE INNODB STATUS",(rs,row)->rs.getString("Status"));
        int start=report.indexOf("LATEST DETECTED DEADLOCK");
        return start<0?"":report.substring(start,Math.min(report.length(),start+240));
    }

    @Test void merchantHandlingAppendsTimelineAndReplaysTheSameKey(){
        String first=key();var handled=data(service.handle(shop,first,1,"已核对接车照片，同意补拍左后门"));
        assertEquals(OrderDisputes.OPEN,handled.get("dispute_status"));assertEquals("DISPUTED",handled.get("order_status"));
        assertEquals(2,number(handled,"owner_confirm"));assertEquals(1,number(handled,"record_count"));assertEquals(OrderDisputes.HANDLE,handled.get("last_action"));
        assertEquals(Boolean.TRUE,handled.get("changed"));
        assertEquals("DISPUTED",state(1));assertEquals(2,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE id=1",Integer.class));
        assertEquals(1,count("order_dispute_record"));assertEquals(1,count("audit_log"));assertEquals(1,count("idempotency_record"));
        assertEquals(1,countWhere("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_DISPUTE_HANDLE' AND resource_type='order_dispute_record'"));
        var replay=data(service.handle(shop,first,1,"已核对接车照片，同意补拍左后门"));
        assertEquals(handled,replay);assertEquals(1,count("order_dispute_record"));assertEquals(1,count("audit_log"));
        var second=data(service.handle(shop,key(),1,"补拍已完成，请车主核对"));
        assertEquals(2,number(second,"record_count"));assertEquals(OrderDisputes.HANDLE,second.get("last_action"));
        assertEquals(List.of("HANDLE","HANDLE"),jdbc.queryForList("SELECT action FROM order_dispute_record ORDER BY id",String.class));
        assertEquals(2,count("audit_log"));
    }
    @Test void ownerAcceptResolvesRestoresAndReopensDispatchAndService(){
        service.handle(shop,key(),1,"已核对接车照片，同意补拍左后门");
        String acceptKey=key();var resolved=data(service.review(owner,acceptKey,1,OrderDisputes.ACCEPT,"接受处理，请继续施工"));
        assertEquals(OrderDisputes.RESOLVED,resolved.get("dispute_status"));assertEquals("RECEIVED",resolved.get("order_status"));
        assertEquals(3,number(resolved,"owner_confirm"));assertEquals(OrderDisputes.ACCEPT,resolved.get("last_action"));assertEquals(Boolean.TRUE,resolved.get("changed"));
        assertEquals(resolved,data(service.review(owner,acceptKey,1,OrderDisputes.ACCEPT,"接受处理，请继续施工")));
        assertEquals("RECEIVED",state(1));assertEquals(3,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE id=1",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT owner_confirmed_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        assertEquals(OrderDisputes.RESOLVED,jdbc.queryForObject("SELECT status FROM order_dispute WHERE id=1",String.class));
        assertNotNull(jdbc.queryForObject("SELECT resolved_at FROM order_dispute WHERE id=1",java.sql.Timestamp.class));
        assertEquals(1,jdbc.queryForObject("SELECT resolved_by FROM order_dispute WHERE id=1",Integer.class));
        assertEquals(1,countWhere("SELECT COUNT(*) FROM order_status_transition WHERE action='ORDER_DISPUTE_RESOLVE' AND from_status='DISPUTED' AND to_status='RECEIVED' AND actor_type='user'"));
        assertEquals(1,countWhere("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_DISPUTE_RESOLVE' AND resource_type='order' AND resource_id=1"));
        assertEquals(2,count("audit_log"));assertEquals(2,count("order_dispute_record"));
        // 恢复条件 R1/R4 已不成立：不能重复恢复，也不能再追加处理记录。
        assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        assertEquals(40905,code(()->service.handle(shop,key(),1,"再来一条")));
        // 争议解决后派工与本人接单恢复可用。
        var assigned=dispatch.assign(shop,key(),1,12);assertEquals("ASSIGNED",assigned.path("data").path("assignment_status").asText());
        assertEquals("RECEIVED",state(1));assertEquals(true,dispatch.detail(tech,1).get("can_accept"));
        assertEquals("IN_SERVICE",dispatch.accept(tech,key(),1).path("data").path("order_status").asText());
        assertEquals("IN_SERVICE",state(1));
    }
    @Test void ownerRejectKeepsTheDisputeOpenAndStillBlocksDispatch(){
        service.handle(shop,key(),1,"已核对接车照片，同意补拍左后门");
        var rejected=data(service.review(owner,key(),1,OrderDisputes.REJECT,"左后门仍未补拍"));
        assertEquals(OrderDisputes.OPEN,rejected.get("dispute_status"));assertEquals("DISPUTED",rejected.get("order_status"));
        assertEquals(2,number(rejected,"owner_confirm"));assertEquals(OrderDisputes.REJECT,rejected.get("last_action"));
        assertEquals(2,number(rejected,"record_count"));assertEquals("DISPUTED",state(1));
        assertNull(jdbc.queryForObject("SELECT owner_confirmed_at FROM `order` WHERE id=1",java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT resolved_at FROM order_dispute WHERE id=1",java.sql.Timestamp.class));
        assertEquals(0,count("order_status_transition"));
        assertEquals(OrderDisputes.DISPUTE_BLOCKS_ASSIGNMENT,code(()->dispatch.assign(shop,key(),1,12)));
        assertNull(disputeView(owner,1).get("resolved_at"));
        assertEquals(true,disputeView(owner,1).get("can_review"));
        // 商家继续处理后可再次复核并解决。
        service.handle(shop,key(),1,"左后门已补拍并重新生成接车单");
        assertEquals(OrderDisputes.RESOLVED,data(service.review(owner,key(),1,OrderDisputes.ACCEPT,null)).get("dispute_status"));
        assertEquals("RECEIVED",state(1));
    }
    @Test void resumeRequiresHandlingAndAWritablePreviousState(){
        assertEquals(OrderDisputes.HANDLING_REQUIRED,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        assertEquals(0,count("order_dispute_record"));assertEquals(0,count("audit_log"));assertEquals(0,count("idempotency_record"));
        service.handle(shop,key(),1,"已核对接车照片");
        for(String from:List.of("CLOSED","COMPLETED","PENDING_PAYMENT","NOPE")){
            jdbc.update("UPDATE order_dispute SET from_status=? WHERE id=1",from);
            assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        }
        jdbc.update("UPDATE order_dispute SET from_status='RECEIVED',status='RESOLVED' WHERE id=1");
        assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        jdbc.update("UPDATE order_dispute SET status='OPEN' WHERE id=1");
        jdbc.update("UPDATE `order` SET status='IN_SERVICE' WHERE id=1");
        assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");
        for(int confirm:List.of(0,1,3)){jdbc.update("UPDATE pickup_check SET owner_confirm=? WHERE id=1",confirm);assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));}
        jdbc.update("UPDATE pickup_check SET owner_confirm=2 WHERE id=1");jdbc.update("UPDATE pickup_check SET is_deleted=1 WHERE id=1");
        assertEquals(40905,code(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        jdbc.update("UPDATE pickup_check SET is_deleted=0 WHERE id=1");
        assertEquals("DISPUTED",state(1));assertEquals(1,count("order_dispute_record"));assertEquals(1,count("audit_log"));
    }
    @Test void rejectsInvalidDecisionTextBeforeTouchingTheDatabase(){
        for(String decision:Arrays.asList(null,"RESOLVE","accept",""))assertEquals(400,status(()->service.review(owner,key(),1,decision,null)));
        for(String note:Arrays.asList(null," ","x".repeat(501)))assertEquals(400,status(()->service.review(owner,key(),1,OrderDisputes.REJECT,note)));
        for(String note:Arrays.asList(null,"", " ", "x".repeat(501)))assertEquals(400,status(()->service.handle(shop,key(),1,note)));
        assertEquals(0,count("order_dispute_record"));assertEquals(0,count("idempotency_record"));
    }
    @Test void legacyDisputesWithoutARecordFailClosed(){
        for(Runnable action:List.<Runnable>of(()->service.handle(shop,key(),2,"补一条"),()->service.review(owner,key(),2,OrderDisputes.ACCEPT,null),()->service.review(owner,key(),2,OrderDisputes.REJECT,"仍不接受")))
            assertEquals(40905,code(action));
        assertEquals("DISPUTED",state(2));assertEquals(0,count("order_dispute_record"));assertEquals(0,count("audit_log"));
        assertEquals(1,count("order_dispute"));
    }
    @Test void isolatesOwnerMerchantAndRevokedSessions(){
        assertEquals(404,status(()->service.handle(other,key(),1,"跨店处理")));
        assertEquals(404,status(()->service.review(foreign,key(),1,OrderDisputes.ACCEPT,null)));
        assertEquals(404,status(()->service.handle(shop,key(),999,"不存在的订单")));
        service.handle(shop,key(),1,"已核对接车照片");
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());
        assertEquals(401,status(()->service.handle(shop,key(),1,"已撤销会话")));
        jdbc.update("UPDATE auth_session SET revoked_at=NULL WHERE id=?",shop.session());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());
        assertEquals(401,status(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        assertEquals("DISPUTED",state(1));assertEquals(1,count("order_dispute_record"));
    }
    @Test void auditAndCacheFailuresRollBackTheWholeDisputeWrite(){
        for(String table:List.of("audit_log","idempotency_record")){
            String trigger=table.equals("audit_log")?"reject_dispute_audit":"reject_dispute_cache";
            jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE "+(table.equals("audit_log")?"INSERT":"UPDATE")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
            assertEquals(503,status(()->service.handle(shop,key(),1,"已核对接车照片")));
            for(String checked:List.of("order_dispute_record","audit_log","idempotency_record"))assertEquals(0,count(checked));
            assertEquals("DISPUTED",state(1));jdbc.execute("DROP TRIGGER "+trigger);
        }
        service.handle(shop,key(),1,"已核对接车照片");jdbc.execute("CREATE TRIGGER reject_dispute_cache BEFORE UPDATE ON idempotency_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
        assertEquals(503,status(()->service.review(owner,key(),1,OrderDisputes.ACCEPT,null)));
        assertEquals("DISPUTED",state(1));assertEquals(OrderDisputes.OPEN,jdbc.queryForObject("SELECT status FROM order_dispute WHERE id=1",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE id=1",Integer.class));
        assertEquals(1,count("order_dispute_record"));assertEquals(0,count("order_status_transition"));assertEquals(1,count("audit_log"));
        jdbc.execute("DROP TRIGGER reject_dispute_cache");
    }
    @Test void projectionsAndStoredPayloadsCarryNoIdentityOrPrivateFields(){
        service.handle(shop,key(),1,"已核对接车照片，同意补拍左后门");
        service.review(owner,key(),1,OrderDisputes.REJECT,"左后门仍未补拍");
        for(Object actor:List.of(owner,shop)){
            String json=db.json(new PickupInspection(db).detail(actor,1));
            for(String field:List.of("opened_by","resolved_by","actor_id","actor_type","merchant_id","staff_id","verify_code","123456","private"))
                assertFalse(json.contains(field),field);
            assertTrue(json.contains(REASON));
        }
        String timeline=db.json(disputeView(owner,1).get("records"));
        for(String field:List.of("actor_id","opened_by","resolved_by"))assertFalse(timeline.contains(field),field);
        for(String[] target:List.of(new String[]{"idempotency_record","response_body,request_hash"},new String[]{"audit_log","before_state,after_state"})){
            String payload=jdbc.queryForObject("SELECT GROUP_CONCAT(IFNULL(CONCAT_WS('',"+target[1]+"),'')) FROM `"+target[0]+"`",String.class);
            for(String field:List.of("actor_id","opened_by","resolved_by","verify_code","123456"))assertFalse(payload!=null&&payload.contains(field),target[0]+":"+field);
        }
    }
    @Test void concurrentReviewsResolveTheOrderOnlyOnce()throws Exception{
        service.handle(shop,key(),1,"已核对接车照片");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var jobs=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)jobs.add(pool.submit(()->{gate.await();try{service.review(owner,key(),1,OrderDisputes.ACCEPT,"恢复施工");return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
            gate.countDown();var outcomes=new HashSet<Integer>();for(var job:jobs)outcomes.add(job.get(20,TimeUnit.SECONDS));
            assertEquals(Set.of(200,409),outcomes);
            assertEquals(1,countWhere("SELECT COUNT(*) FROM order_status_transition WHERE action='ORDER_DISPUTE_RESOLVE'"));
            assertEquals(2,count("order_dispute_record"));
            assertEquals(OrderDisputes.RESOLVED,jdbc.queryForObject("SELECT status FROM order_dispute WHERE id=1",String.class));
            assertEquals(3,jdbc.queryForObject("SELECT owner_confirm FROM pickup_check WHERE id=1",Integer.class));assertEquals("RECEIVED",state(1));
        }finally{pool.shutdownNow();}
    }
    @Test void mixedHandlingReviewAndDispatchNeverDeadlocks()throws Exception{
        String baseline=deadlockSection();var pool=Executors.newFixedThreadPool(3);var outcomes=new HashSet<Integer>();
        try{
            for(int round=0;round<6;round++){
                final String stage=String.valueOf(round);
                if("RESOLVED".equals(jdbc.queryForObject("SELECT status FROM order_dispute WHERE id=1",String.class)))
                    jdbc.update("UPDATE order_dispute SET status='OPEN',resolved_at=NULL,resolved_by=NULL WHERE id=1");
                if(!"DISPUTED".equals(state(1)))jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");
                jdbc.update("UPDATE pickup_check SET owner_confirm=2 WHERE id=1");
                var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<Integer>>();
                jobs.add(pool.submit(()->{gate.await();try{service.handle(shop,key(),1,"第 "+stage+" 轮处理");return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
                jobs.add(pool.submit(()->{gate.await();try{service.review(owner,key(),1,OrderDisputes.ACCEPT,"第 "+stage+" 轮复核");return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
                jobs.add(pool.submit(()->{gate.await();try{dispatch.assign(shop,key(),1,12);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
                gate.countDown();for(var job:jobs)outcomes.add(job.get(30,TimeUnit.SECONDS));
            }
            assertFalse(outcomes.contains(503),"混合并发出现不可重试的失败："+outcomes);
            assertEquals(baseline,deadlockSection(),"并发轮次新增了 InnoDB 死锁报告");
        }finally{pool.shutdownNow();}
    }
}
