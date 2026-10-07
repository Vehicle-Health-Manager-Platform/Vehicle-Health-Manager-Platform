package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** 履约状态迁移的真库验证：矩阵、前置、幂等、越权、并发与同事务审计。 */
@Testcontainers
class JdbcOrderFulfillmentTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static class Time extends Clock {Instant value=Instant.now().truncatedTo(ChronoUnit.SECONDS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
    Time clock;ReservationStore db;ReservationSlots slots;ReservationOrders orders;ReservationExpiry expiry;MerchantOrders merchantOrders;OrderFulfillment fulfillment;MerchantActor shop,other;VehicleOwner a;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()){for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V006__merchant_project_versions.sql","V007__reservation_orders.sql","V007__reservation_orders.sql","V008__payment_foundation.sql","V008__payment_foundation.sql","V009__order_fulfillment_states.sql","V009__order_fulfillment_states.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_fulfillment_transition");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_fulfillment_audit");
        for(String t:List.of("order_status_transition","payment_event","payment_exception","payment","audit_log","idempotency_record","order","appointment_slot","merchant_project_version","merchant_project","standard_project","auth_session","staff_account","merchant","vehicle","user"))jdbc.update("DELETE FROM `"+t+"`");
        clock=new Time();var mapper=new ObjectMapper();var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),clock,manager);
        expiry=new ReservationExpiry(db);slots=new ReservationSlots(db);orders=new ReservationOrders(db,expiry);merchantOrders=new MerchantOrders(db);fulfillment=new OrderFulfillment(db,merchantOrders);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'测试店','合成地址',1),(2,2,'另一家店','合成地址',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account) VALUES(1,1,'MERCHANT','test'),(2,2,'MERCHANT','other')");
        shop=new MerchantActor(1,1,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));session(shop.session(),"staff_account",1,"MERCHANT","merchant-account",1);
        other=new MerchantActor(2,2,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));session(other.session(),"staff_account",2,"MERCHANT","merchant-account",2);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'owner-a',1)");a=owner(1);
        jdbc.update("INSERT INTO vehicle(id,user_id,model_id,vin,current_mileage) VALUES(1,1,1,'TEST-A',0)");
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'测试项目',1,'测试',1,100)");
        jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(1,1,1,12.34,1)");jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(1,1,1,12.34,1,1)");
    }
    void session(String id,String type,long subject,String role,String app,long merchant){jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",id,type,subject,role,app,merchant,UUID.randomUUID().toString());}
    VehicleOwner owner(long id){var o=new VehicleOwner(id,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));session(o.session(),"user",id,"OWNER","test",0);return o;}
    String key(){return UUID.randomUUID().toString();}
    long slot(int capacity){Instant start=clock.value.atZone(ReservationInput.ZONE).toLocalDate().plusDays(1).atTime(10,0).atZone(ReservationInput.ZONE).toInstant();return slots.publish(shop,key(),new ReservationInput.Slot(1,start,start.plusSeconds(3600),capacity)).path("data").path("slot_id").asLong();}
    long order(VehicleOwner o,long slot){return orders.create(o,key(),new ReservationInput.Booking(1,1,o.id(),slot)).path("data").path("order_id").asLong();}
    long paid(long slot){long id=order(a,slot);jdbc.update("UPDATE `order` SET status='PAID' WHERE id=?",id);return id;}
    void evidence(long id,String column){jdbc.update("UPDATE `order` SET `"+column+"`=UTC_TIMESTAMP() WHERE id=?",id);}
    String status(long id){return jdbc.queryForObject("SELECT status FROM `order` WHERE id=?",String.class,id);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    static String data(JsonNode response,String field){return response.path("data").path(field).asText();}
    static int codeOf(Executable action){return assertThrows(FulfillmentConflict.class,action).code;}
    static int httpStatus(Executable action){return assertThrows(ResponseStatusException.class,action).getStatusCode().value();}
    static List<Map<String,Object>> actions(String action,String target){return List.of(Map.of("action",action,"to_status",target));}

    @Test void checkInNeedsEvidenceAndThenRecordsBothAudits(){
        long id=paid(slot(1));
        assertEquals(43001,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null)));
        assertEquals("PAID",status(id));assertEquals(0,count("order_status_transition"));assertEquals(0,count("audit_log"));
        evidence(id,"check_in_completed_at");
        var response=fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,"  实到十分钟  ");
        assertEquals("RECEIVED",data(response,"status"));assertEquals("PAID",data(response,"from_status"));assertTrue(response.path("data").path("changed").asBoolean());
        assertEquals(1,count("order_status_transition"));assertEquals(1,count("audit_log"));assertEquals(1,count("idempotency_record"));
        var row=jdbc.queryForMap("SELECT * FROM order_status_transition WHERE order_id=?",id);
        assertEquals("PAID",row.get("from_status"));assertEquals("RECEIVED",row.get("to_status"));assertEquals("ORDER_CHECK_IN",row.get("action"));
        assertEquals("staff_account",row.get("actor_type"));assertEquals(1L,((Number)row.get("actor_id")).longValue());assertEquals("实到十分钟",row.get("note"));
        assertEquals(1L,((Number)row.get("merchant_id")).longValue());assertNotNull(row.get("occurred_at"));
    }

    @Test void matrixRejectsSkippingAndReopening(){
        long place=slot(2);
        long pending=order(a,place);
        assertEquals(40905,codeOf(()->fulfillment.apply(shop,key(),pending,OrderStatus.RECEIVE,null)));
        assertEquals(40905,codeOf(()->fulfillment.apply(shop,key(),pending,OrderStatus.START_SERVICE,null)));
        assertEquals(0,count("order_status_transition"));
        long id=paid(place);
        evidence(id,"check_in_completed_at");evidence(id,"owner_confirmed_at");evidence(id,"assigned_at");
        assertEquals("RECEIVED",data(fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null),"status"));
        assertEquals("IN_SERVICE",data(fulfillment.apply(shop,key(),id,OrderStatus.START_SERVICE,null),"status"));
        assertEquals(40905,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.COMPLETE,null)));
        evidence(id,"service_report_ready_at");
        assertEquals("PENDING_VERIFY",data(fulfillment.apply(shop,key(),id,OrderStatus.FINISH_SERVICE,null),"status"));
        assertEquals(40905,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null)));
        assertEquals("PENDING_VERIFY",status(id));assertEquals(3,count("order_status_transition"));
    }

    @Test void startServiceWaitsForOwnerConfirmationAndAssignment(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);
        assertEquals(43003,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.START_SERVICE,null)));
        evidence(id,"owner_confirmed_at");
        assertEquals(43004,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.START_SERVICE,null)));
        assertEquals("RECEIVED",status(id));
        evidence(id,"assigned_at");
        assertEquals("IN_SERVICE",data(fulfillment.apply(shop,key(),id,OrderStatus.START_SERVICE,null),"status"));
    }

    @Test void finishWaitsForReportsAndCompletionWaitsForVerificationChecks(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);
        evidence(id,"owner_confirmed_at");evidence(id,"assigned_at");fulfillment.apply(shop,key(),id,OrderStatus.START_SERVICE,null);
        assertEquals(43005,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.FINISH_SERVICE,null)));
        evidence(id,"service_report_ready_at");
        assertEquals("PENDING_VERIFY",data(fulfillment.apply(shop,key(),id,OrderStatus.FINISH_SERVICE,null),"status"));
        // D3 的核销校验尚未接入，因此 COMPLETE 必须 fail-closed 而不是放行。
        assertEquals(43006,codeOf(()->fulfillment.apply(shop,key(),id,OrderStatus.COMPLETE,null)));
        assertEquals("PENDING_VERIFY",status(id));assertEquals(3,count("order_status_transition"));
    }

    @Test void repeatedActionReportsNoChangeWithoutSecondAudit(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);
        var again=fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);
        assertFalse(again.path("data").path("changed").asBoolean());assertEquals("RECEIVED",data(again,"status"));
        assertEquals(1,count("order_status_transition"));assertEquals(1,count("audit_log"));
    }

    @Test void sameKeyReplaysOneResponseAndOneTransition(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        String k=key();
        var first=fulfillment.apply(shop,k,id,OrderStatus.RECEIVE,null);
        assertEquals(first,fulfillment.apply(shop,k,id,OrderStatus.RECEIVE,null));
        assertEquals(1,count("order_status_transition"));assertEquals(1,count("audit_log"));
        assertEquals(400,httpStatus(()->fulfillment.apply(shop,k,id,OrderStatus.START_SERVICE,null)));
    }

    @Test void notesAndActionsAreStrictlyShaped(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        for(String action:new String[]{"","receive","RECEIVED","ORDER_CHECK_IN",null})
            assertEquals(400,httpStatus(()->fulfillment.apply(shop,key(),id,action,null)),"action="+action);
        assertEquals(400,httpStatus(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE," ")));
        assertEquals(400,httpStatus(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,"x".repeat(201))));
        assertEquals(400,httpStatus(()->fulfillment.apply(shop,key(),0,OrderStatus.RECEIVE,null)));
        assertEquals(0,count("order_status_transition"));
    }

    @Test void anotherShopCannotSeeOrTransition(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        assertEquals(404,httpStatus(()->fulfillment.apply(other,key(),id,OrderStatus.RECEIVE,null)));
        assertEquals(404,httpStatus(()->merchantOrders.detail(other,id)));
        assertEquals("PAID",status(id));assertEquals(0,count("order_status_transition"));
    }

    @Test void revokingTheSessionStopsTheTransition(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());
        assertEquals(401,httpStatus(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null)));
        assertEquals("PAID",status(id));
    }

    @Test void transitionAndItsAuditShareOneTransaction(){
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        jdbc.execute("CREATE TRIGGER reject_fulfillment_transition BEFORE INSERT ON order_status_transition FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try{assertEquals(503,httpStatus(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null)));}
        finally{jdbc.execute("DROP TRIGGER reject_fulfillment_transition");}
        assertEquals("PAID",status(id));assertEquals(0,count("order_status_transition"));assertEquals(0,count("audit_log"));assertEquals(0,count("idempotency_record"));
        jdbc.execute("CREATE TRIGGER reject_fulfillment_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try{assertEquals(503,httpStatus(()->fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null)));}
        finally{jdbc.execute("DROP TRIGGER reject_fulfillment_audit");}
        assertEquals("PAID",status(id));assertEquals(0,count("order_status_transition"));assertEquals(0,count("idempotency_record"));
        assertEquals("RECEIVED",data(fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null),"status"));
    }

    @Test void projectedActionsTrackTheStoredStatus(){
        long id=paid(slot(1));
        assertEquals(actions(OrderStatus.RECEIVE,OrderStatus.RECEIVED),merchantOrders.detail(shop,id).get("allowed_actions"));
        evidence(id,"check_in_completed_at");fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);
        assertEquals(actions(OrderStatus.START_SERVICE,OrderStatus.IN_SERVICE),merchantOrders.detail(shop,id).get("allowed_actions"));
        var items=(List<?>)merchantOrders.list(shop,OrderStatus.RECEIVED,null,1,20).get("items");
        assertEquals(1,items.size());
        assertEquals(actions(OrderStatus.START_SERVICE,OrderStatus.IN_SERVICE),((Map<?,?>)items.get(0)).get("allowed_actions"));
    }

    @Test void filtersAcceptEveryDeclaredStateAndRejectUnknownOnes(){
        for(String status:OrderStatus.ALL){merchantOrders.list(shop,status,null,1,20);orders.list(a,status,1,20);}
        assertEquals(400,httpStatus(()->merchantOrders.list(shop,"CANCELLED",null,1,20)));
        assertEquals(400,httpStatus(()->orders.list(a,"CANCELLED",1,20)));
    }

    @Test void concurrentActionsSerializeOnTheOrderRow()throws Exception{
        long id=paid(slot(1));evidence(id,"check_in_completed_at");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{
            var first=pool.submit(()->attempt(id,gate));var second=pool.submit(()->attempt(id,gate));gate.countDown();
            assertEquals(List.of(200,200),List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)).stream().sorted().toList());
        }finally{pool.shutdownNow();}
        assertEquals("RECEIVED",status(id));assertEquals(1,count("order_status_transition"));assertEquals(1,count("audit_log"));
    }
    int attempt(long id,CountDownLatch gate)throws Exception{gate.await();try{fulfillment.apply(shop,key(),id,OrderStatus.RECEIVE,null);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}
}
