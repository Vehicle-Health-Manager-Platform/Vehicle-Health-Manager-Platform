package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.Timestamp;
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
 * A5.4 safety and contention evidence: staff-code reissue/revoke, account disable, session
 * revocation and concurrent first assignment/acceptance on a real MySQL, plus persisted payload
 * and legacy-anomaly freeze checks. Complements {@link JdbcTechnicianAssignmentsTest}.
 */
@Testcontainers
class JdbcTechnicianAssignmentsVerificationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;static JdbcTemplate admin;ReservationStore db;TechnicianAssignments service;MerchantActor shop,other;TechnicianActor tech,peer,foreign;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        // InnoDB 死锁报告与全局状态只有 PROCESS 权限可读，业务连接（test 用户）不够。
        admin=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword()));
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V012__technician_dispatch.sql")));}
    }
    @BeforeEach void setup(){
        for(String table:List.of("technician_assignment","pickup_check","order_status_transition","audit_log","idempotency_record","auth_session","staff_wechat_identity","order","appointment_slot","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,new ObjectMapper(),new WriteIntegrityService(jdbc,new ObjectMapper(),manager),Clock.systemUTC(),manager);service=new TechnicianAssignments(db,"test-app");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-A','test',1),(2,2,'synthetic-B','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','shop-A','ACTIVE'),(2,2,'MERCHANT','shop-B','ACTIVE'),(12,1,'TECHNICIAN','tech-A','ACTIVE'),(13,1,'TECHNICIAN','tech-peer','ACTIVE'),(14,2,'TECHNICIAN','tech-B','ACTIVE')");
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
    JsonNode dispatch(long technicianId){return service.assign(shop,key(),1,technicianId);}
    void inTransaction(Runnable operation){db.transactions.execute(tx->{operation.run();return null;});}
    List<Integer> concurrent(Callable<Integer> first,Callable<Integer> second)throws Exception{
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();return first.call();});var b=pool.submit(()->{gate.await();return second.call();});gate.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
    }
    int outcome(Runnable run){try{run.run();return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}
    String deadlockSection(){
        String report=admin.queryForObject("SHOW ENGINE INNODB STATUS",(rs,row)->rs.getString("Status"));
        int start=report.indexOf("LATEST DETECTED DEADLOCK");
        return start<0?"":report.substring(start,Math.min(report.length(),start+240));
    }

    @Test void reissuingStaffCodeWhileDispatchingLeavesNoStaleEligibility()throws Exception{
        for(int round=0;round<5;round++){
            setup();
            var results=concurrent(()->outcome(()->dispatch(12)),()->{inTransaction(()->new StaffCodeOperations(jdbc).issue(12));return 200;});
            assertEquals(200,results.get(1),"重发员工码必须成功");
            assertTrue(results.get(0)==200||results.get(0)==404,"派工结果只能是成功或资格失效："+results);
            assertEquals(results.get(0)==200?1:0,count("technician_assignment"));
            assertEquals(results.get(0)==200?1:0,count("audit_log"));
            assertEquals(results.get(0)==200?1:0,count("idempotency_record"),"只有成功的派工才留下幂等记录");
            assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM staff_wechat_identity WHERE id=112",String.class));
            assertNotNull(jdbc.queryForObject("SELECT unbound_at FROM staff_wechat_identity WHERE id=112",Timestamp.class));
            assertEquals(401,status(()->service.detail(tech,1)),"绑定被撤销后不能再读工单");
            assertEquals(0,count("order_status_transition"));
        }
    }
    @Test void revokingStaffCodeDuringOwnAcceptanceNeverLeavesStaleAcceptance()throws Exception{
        for(int round=0;round<5;round++){
            setup();dispatch(12);String acceptanceKey=key();
            var results=concurrent(()->outcome(()->service.accept(tech,acceptanceKey,1)),
                ()->{inTransaction(()->new StaffCodeOperations(jdbc).revoke(12));return 200;});
            assertEquals(200,results.get(1));
            assertTrue(results.get(0)==200||results.get(0)==401,"接单结果只能是成功或身份失效："+results);
            assertEquals(401,status(()->service.accept(tech,acceptanceKey,1)),"撤销后原成功键重放仍须拒绝");
            assertEquals(401,status(()->service.detail(tech,1)));
            assertEquals(1,count("technician_assignment"),"已有派工不因人员资格撤销而消失");
            assertEquals(results.get(0)==200?1:0,count("order_status_transition"));
            assertEquals(results.get(0)==200?2:1,count("audit_log"));
        }
    }
    @Test void disablingTechnicianAccountDuringAcceptanceFailsClosed()throws Exception{
        for(int round=0;round<3;round++){
            setup();dispatch(12);
            var results=concurrent(()->outcome(()->service.accept(tech,key(),1)),
                ()->{inTransaction(()->jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=12"));return 200;});
            assertTrue(results.get(0)==200||results.get(0)==401,"接单结果只能是成功或身份失效："+results);
            assertEquals(401,status(()->service.detail(tech,1)),"停用后不能再读工单");
            assertEquals(1,count("technician_assignment"));
            assertEquals(results.get(0)==200?1:0,count("order_status_transition"));
            assertEquals(results.get(0)==200?"IN_SERVICE":"RECEIVED",state());
        }
    }
    @Test void revokingMerchantSessionDuringDispatchFailsClosed()throws Exception{
        for(int round=0;round<3;round++){
            setup();String dispatchKey=key();
            var results=concurrent(()->outcome(()->service.assign(shop,dispatchKey,1,12)),
                ()->{inTransaction(()->jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session()));return 200;});
            assertEquals(200,results.get(1));
            assertTrue(results.get(0)==200||results.get(0)==401,"派工结果只能是成功或会话失效："+results);
            assertEquals(401,status(()->service.assign(shop,dispatchKey,1,12)),"会话撤销后原成功键重放仍须拒绝");
            assertEquals(results.get(0)==200?1:0,count("technician_assignment"));
            assertEquals(results.get(0)==200?1:0,count("audit_log"));
        }
    }
    @Test void twoDifferentKeysForOwnAcceptanceMigrateExactlyOnce()throws Exception{
        setup();dispatch(12);String first=key(),second=key();
        var results=concurrent(()->outcome(()->service.accept(tech,first,1)),()->outcome(()->service.accept(tech,second,1)));
        assertEquals(List.of(200,200),results,"同一位技师的两个不同键都必须拿到成功快照");
        assertEquals(1,count("order_status_transition"));
        assertEquals(2,count("audit_log"),"派工一次、接单一次");
        assertEquals(3,count("idempotency_record"),"派工一次加本人两次接单各一条幂等记录");
        assertEquals("IN_SERVICE",state());
        assertNotNull(jdbc.queryForObject("SELECT accepted_at FROM technician_assignment",Timestamp.class));
        assertEquals(false,service.detail(tech,1).get("can_accept"));
    }
    @Test void peerAndForeignTechniciansCannotJoinTheAcceptance()throws Exception{
        setup();dispatch(12);
        var results=concurrent(()->outcome(()->service.accept(tech,key(),1)),()->outcome(()->service.accept(peer,key(),1)));
        assertEquals(200,results.get(0));assertEquals(404,results.get(1));
        assertEquals(0L,service.list(peer,null,1,20).get("total"));
        assertEquals(404,status(()->service.accept(foreign,key(),1)));
        assertEquals(1,count("order_status_transition"));
        assertEquals(2,count("audit_log"));
    }
    @Test void persistedIdempotencyAndAuditPayloadsCarryNoPrivateCustomerData(){
        setup();dispatch(12);service.accept(tech,key(),1);
        var stored=jdbc.queryForList("SELECT request_path,response_body FROM idempotency_record");
        assertEquals(2,stored.size(),"派工与接单各一条幂等记录");
        var audits=jdbc.queryForList("SELECT action,before_state,after_state FROM audit_log");
        assertEquals(2,audits.size());
        List<String> sensitive=List.of("user_id","vehicle_id","phone","vin","plate_no","verify_code","pickup_code",
            "payment_summary","payment_id","owner_id","123456","synthetic-99","private","proof");
        for(var row:stored)for(String field:sensitive)assertFalse(String.valueOf(row.get("response_body")).contains(field),row.get("request_path")+" 的幂等响应含 "+field);
        for(var row:audits)for(Object column:row.values())for(String field:sensitive)assertFalse(String.valueOf(column).contains(field),row.get("action")+" 的审计载荷含 "+field);
    }
    @Test void legacyInconsistentAssignmentsStayFrozenAndRejected(){
        String[] mutations={
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,12,'UNKNOWN',1)",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,12,'ACCEPTED',1)",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by,accepted_at) VALUES(1,1,12,'ASSIGNED',1,UTC_TIMESTAMP())",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status) VALUES(1,1,12,'ASSIGNED')",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,12,'ASSIGNED',1)"};
        for(int index=0;index<mutations.length;index++){
            setup();jdbc.execute(mutations[index]);
            if(index!=mutations.length-1)jdbc.update("UPDATE `order` SET assigned_at=UTC_TIMESTAMP() WHERE id=1");
            var before=jdbc.queryForMap("SELECT * FROM technician_assignment");
            assertEquals(40905,code(()->service.merchantDetail(shop,1)),mutations[index]);
            assertEquals(40905,code(()->service.detail(tech,1)),mutations[index]);
            assertEquals(40905,code(()->dispatch(12)),mutations[index]);
            assertEquals(before,jdbc.queryForMap("SELECT * FROM technician_assignment"),"历史异常记录不得被改写");
            assertEquals(0,count("audit_log"));assertEquals(0,count("order_status_transition"));
        }
    }
    @Test void legacyCrossShopOwnershipIsRejectedAndFrozen(){
        String[] mutations={
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,2,12,'ASSIGNED',1)",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,12,'ASSIGNED',2)",
            "INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,14,'ASSIGNED',1)"};
        // 派工商家与订单不符、技师属他店时，本人视角连资源都看不到，只能 404。
        int[] technicianCodes={404,409,404};
        for(int index=0;index<mutations.length;index++){
            setup();jdbc.execute(mutations[index]);jdbc.update("UPDATE `order` SET assigned_at=UTC_TIMESTAMP() WHERE id=1");
            var before=jdbc.queryForMap("SELECT * FROM technician_assignment");
            assertEquals(40905,code(()->service.merchantDetail(shop,1)),mutations[index]);
            assertEquals(40905,code(()->dispatch(12)),mutations[index]);
            assertEquals(technicianCodes[index],status(()->service.detail(tech,1)),mutations[index]);
            assertEquals(1,count("technician_assignment"),"拒绝时不得新增派工");
            assertEquals(before,jdbc.queryForMap("SELECT * FROM technician_assignment"),"跨店派工历史不得被改写");
            assertEquals(0,count("audit_log"));
        }
    }
    @Test void deletedOrderAndAssignmentHistoryAreNeverRepaired(){
        setup();jdbc.execute("INSERT INTO technician_assignment(order_id,merchant_id,technician_id,status,assigned_by) VALUES(1,1,12,'ASSIGNED',1)");
        jdbc.update("UPDATE `order` SET assigned_at=UTC_TIMESTAMP(),is_deleted=1 WHERE id=1");
        var before=jdbc.queryForMap("SELECT * FROM technician_assignment");
        assertEquals(404,status(()->service.merchantDetail(shop,1)));
        assertEquals(404,status(()->service.detail(tech,1)));
        assertEquals(404,status(()->dispatch(12)));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM technician_assignment"));
        assertEquals(1,count("technician_assignment"),"删除订单不会自动清除历史派工行");
        assertEquals(0,count("audit_log"));
    }
    @Test void mixedConcurrentRoundsCompleteWithoutDeadlockOrTimeout()throws Exception{
        String baseline=deadlockSection();
        for(int round=0;round<6;round++){
            setup();
            var dispatched=concurrent(()->outcome(()->dispatch(12)),()->outcome(()->dispatch(13)));
            assertTrue(dispatched.contains(200),dispatched.toString());
            assertEquals(1,count("technician_assignment"),"两人竞争只能产生一条派工");
            assertEquals(1,count("audit_log"));
            boolean firstWins=jdbc.queryForObject("SELECT technician_id FROM technician_assignment",Long.class)==12L;
            var owner=firstWins?tech:peer;long winnerId=firstWins?12L:13L;
            var accepted=concurrent(()->outcome(()->service.accept(owner,key(),1)),
                ()->{inTransaction(()->new StaffCodeOperations(jdbc).issue(winnerId));return 200;});
            assertEquals(200,accepted.get(1));
            assertTrue(accepted.get(0)==200||accepted.get(0)==401,"接单结果只能是成功或身份失效："+accepted);
            assertEquals(1,count("technician_assignment"));
            assertEquals(accepted.get(0)==200?1:0,count("order_status_transition"));
        }
        assertEquals(baseline,deadlockSection(),"并发轮次新增了 InnoDB 死锁报告");
    }
}
