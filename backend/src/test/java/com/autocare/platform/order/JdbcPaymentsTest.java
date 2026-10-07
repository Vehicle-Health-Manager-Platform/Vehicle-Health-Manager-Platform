package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
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
class JdbcPaymentsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static class Time extends Clock {Instant value=Instant.now().truncatedTo(ChronoUnit.SECONDS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
    PaymentService payments;Time clock;ReservationStore db;ReservationSlots slots;ReservationOrders orders;ReservationExpiry expiry;MerchantActor shop;VehicleOwner a,b;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()){for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V006__merchant_project_versions.sql","V007__reservation_orders.sql","V007__reservation_orders.sql","V008__payment_foundation.sql","V008__payment_foundation.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_reservation_audit");
        for(String t:List.of("payment_event","payment_exception","payment","audit_log","idempotency_record","order","appointment_slot","merchant_project_version","merchant_project","standard_project","auth_session","staff_account","merchant","vehicle","user"))jdbc.update("DELETE FROM `"+t+"`");
        clock=new Time();var mapper=new ObjectMapper();var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),clock,manager);expiry=new ReservationExpiry(db);slots=new ReservationSlots(db);orders=new ReservationOrders(db,expiry);payments=new PaymentService(db,expiry,new PaymentChannels(new LocalTestPaymentChannel(LocalTestPaymentChannelTest.KEY,clock)));
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'测试店','合成地址',1)");jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account) VALUES(1,1,'MERCHANT','test')");
        shop=new MerchantActor(1,1,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));session(shop.session(),"staff_account",1,"MERCHANT","merchant-account",1);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'owner-a',1),(2,'owner-b',1)");a=owner(1);b=owner(2);
        jdbc.update("INSERT INTO vehicle(id,user_id,model_id,vin,current_mileage) VALUES(1,1,1,'TEST-A',0),(2,2,1,'TEST-B',0)");
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'测试项目',1,'测试',1,100)");
        jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(1,1,1,12.34,1)");jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(1,1,1,12.34,1,1)");
    }
    void session(String id,String type,long subject,String role,String app,long merchant){jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",id,type,subject,role,app,merchant,UUID.randomUUID().toString());}
    VehicleOwner owner(long id){var o=new VehicleOwner(id,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));session(o.session(),"user",id,"OWNER","test",0);return o;}
    String key(){return UUID.randomUUID().toString();}
    long slot(int capacity){Instant start=clock.value.atZone(ReservationInput.ZONE).toLocalDate().plusDays(1).atTime(10,0).atZone(ReservationInput.ZONE).toInstant();return slots.publish(shop,key(),new ReservationInput.Slot(1,start,start.plusSeconds(3600),capacity)).path("data").path("slot_id").asLong();}
    ReservationInput.Booking booking(VehicleOwner o,long slot){return new ReservationInput.Booking(1,1,o.id(),slot);}
    long order(VehicleOwner o,long slot){return orders.create(o,key(),booking(o,slot)).path("data").path("order_id").asLong();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    long pay(long order){return payments.create(a,key(),order,"LOCAL_TEST").path("data").path("payment_id").asLong();}
    PaymentNotice notice(long id,String status,String event,String transaction){var row=jdbc.queryForMap("SELECT p.amount,o.order_no FROM payment p JOIN `order` o ON o.id=p.order_id WHERE p.id=?",id);return new PaymentNotice(event,id,transaction,status,(java.math.BigDecimal)row.get("amount"),"CNY",row.get("order_no").toString(),clock.value,event+status+transaction);}
    void send(long id,String status){payments.notify(notice(id,status,key(),"test-"+id));}
    String state(long id){return payments.detail(a,id).get("status").toString();}
    @Test void creationReplayAndOwnership(){long slot=slot(1),o=order(a,slot);String k=key();var first=payments.create(a,k,o,"LOCAL_TEST");long p=first.path("data").path("payment_id").asLong();assertEquals(first,payments.create(a,k,o,"LOCAL_TEST"));assertEquals(p,pay(o));assertEquals(1,count("payment"));assertEquals(404,assertThrows(ResponseStatusException.class,()->payments.detail(b,p)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->payments.create(b,key(),o,"LOCAL_TEST")).getStatusCode().value());orders.cancel(a,key(),o);assertEquals(first,payments.create(a,k,o,"LOCAL_TEST"));assertEquals("CLOSED",state(p));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",a.session());assertEquals(401,assertThrows(ResponseStatusException.class,()->payments.create(a,k,o,"LOCAL_TEST")).getStatusCode().value());}
    @Test void concurrentCreationHasOneRecord()throws Exception{long o=order(a,slot(1));var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(()->pay(o));var y=pool.submit(()->pay(o));assertEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));assertEquals(1,count("payment"));}finally{pool.shutdownNow();}}
    @Test void successDuplicatesAndPaidCapacity(){long slot=slot(1),o=order(a,slot),p=pay(o);var n=notice(p,"SUCCEEDED",key(),"receipt");payments.notify(n);payments.notify(n);payments.notify(notice(p,"SUCCEEDED",key(),"receipt"));payments.notify(notice(p,"FAILED",key(),"receipt"));assertEquals("PAID",orders.detail(a,o).get("status"));assertEquals("SUCCEEDED",state(p));assertEquals(3,count("payment_event"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_PAID'",Integer.class));clock.value=clock.value.plusSeconds(901);expiry.sweep();assertEquals("PAID",orders.detail(a,o).get("status"));assertThrows(ResponseStatusException.class,()->orders.cancel(a,key(),o));assertThrows(ResponseStatusException.class,()->order(b,slot));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));}
    @Test void failureRetryAndOldFailureSuccessRequiresReview(){long o=order(a,slot(1)),old=pay(o);send(old,"FAILED");long current=pay(o);assertNotEquals(old,current);send(old,"SUCCEEDED");assertEquals("PENDING_PAYMENT",orders.detail(a,o).get("status"));assertEquals(true,payments.detail(a,old).get("requires_review"));send(current,"SUCCEEDED");assertEquals("PAID",orders.detail(a,o).get("status"));assertEquals(1,count("payment_exception"));}
    @Test void cancellationThenSuccessDoesNotRestoreCapacity(){long slot=slot(1),o=order(a,slot),p=pay(o);orders.cancel(a,key(),o);order(b,slot);send(p,"SUCCEEDED");send(p,"SUCCEEDED");assertEquals("CLOSED",orders.detail(a,o).get("status"));assertEquals("ORDER_CANCELLED",jdbc.queryForObject("SELECT reason FROM payment_exception",String.class));assertEquals(1,count("payment_exception"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));}
    @Test void expiredBeforeWorkerThenSuccessClosesAndReviews(){long o=order(a,slot(1)),p=pay(o);clock.value=clock.value.plusSeconds(900);send(p,"SUCCEEDED");assertEquals("CLOSED",orders.detail(a,o).get("status"));assertEquals("ORDER_EXPIRED",jdbc.queryForObject("SELECT reason FROM payment_exception",String.class));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_EXPIRE'",Integer.class));}
    @Test void eventConflictAndReceiptCannotCrossOrders(){long s=slot(2),o=order(a,s),p=pay(o);var n=notice(p,"SUCCEEDED",key(),"shared-receipt");payments.notify(n);assertEquals(409,assertThrows(ResponseStatusException.class,()->payments.notify(notice(p,"FAILED",n.eventId(),n.transaction()))).getStatusCode().value());long second=order(a,s),p2=pay(second);assertEquals(409,assertThrows(ResponseStatusException.class,()->payments.notify(notice(p2,"SUCCEEDED",key(),"shared-receipt"))).getStatusCode().value());assertEquals("PENDING",state(p2));assertEquals("PENDING_PAYMENT",orders.detail(a,second).get("status"));assertEquals(1,count("payment_event"));}
    @Test void amountAndOrderMismatchNeverPersist(){long o=order(a,slot(1)),p=pay(o);var n=notice(p,"SUCCEEDED",key(),"receipt");assertThrows(ResponseStatusException.class,()->payments.notify(new PaymentNotice(n.eventId(),p,n.transaction(),n.status(),new java.math.BigDecimal("0.01"),n.currency(),n.orderNo(),n.occurred(),n.hash())));assertThrows(ResponseStatusException.class,()->payments.notify(new PaymentNotice(n.eventId(),p,n.transaction(),n.status(),n.amount(),"USD",n.orderNo(),n.occurred(),n.hash())));assertEquals(0,count("payment_event"));assertEquals("PENDING",state(p));}
    @Test void successAuditFailureRollsBackEverything(){long o=order(a,slot(1)),p=pay(o);jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");assertEquals(503,assertThrows(ResponseStatusException.class,()->send(p,"SUCCEEDED")).getStatusCode().value());assertEquals("PENDING",state(p));assertEquals("PENDING_PAYMENT",orders.detail(a,o).get("status"));assertEquals(0,count("payment_event"));jdbc.execute("DROP TRIGGER reject_reservation_audit");send(p,"SUCCEEDED");assertEquals("PAID",orders.detail(a,o).get("status"));}
    @Test void cancellationRacesWithSuccess()throws Exception{long o=order(a,slot(1)),p=pay(o);var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(()->send(p,"SUCCEEDED"));var y=pool.submit(()->{try{orders.cancel(a,key(),o);}catch(ResponseStatusException e){assertEquals(400,e.getStatusCode().value());}});x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);String state=orders.detail(a,o).get("status").toString();assertTrue(Set.of("PAID","CLOSED").contains(state));assertEquals(state.equals("PAID")?1:0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));assertEquals(state.equals("CLOSED")?1:0,count("payment_exception"));}finally{pool.shutdownNow();}}
    @Test void concurrentNotificationsApplyOneTransition()throws Exception{long o=order(a,slot(1)),p=pay(o);var n=notice(p,"SUCCEEDED",key(),"receipt");var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(()->payments.notify(n));var y=pool.submit(()->new PaymentService(db,expiry,payments.channels).notify(n));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);assertEquals(1,count("payment_event"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_PAID'",Integer.class));}finally{pool.shutdownNow();}}
    @Test void oldAndNewPaymentCannotBothFulfillOrder(){long o=order(a,slot(1)),old=pay(o);send(old,"FAILED");long current=pay(o);send(current,"SUCCEEDED");send(old,"SUCCEEDED");assertEquals("DUPLICATE_PAYMENT",jdbc.queryForObject("SELECT reason FROM payment_exception",String.class));assertEquals("PAID",orders.detail(a,o).get("status"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_PAID'",Integer.class));}
    @Test void expiryWorkerRacesWithSuccessAtDeadline()throws Exception{long o=order(a,slot(1)),p=pay(o);clock.value=clock.value.plusSeconds(900);var pool=Executors.newFixedThreadPool(2);try{var x=pool.submit(expiry::sweep);var y=pool.submit(()->send(p,"SUCCEEDED"));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);assertEquals("CLOSED",orders.detail(a,o).get("status"));assertEquals("SUCCEEDED",state(p));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));assertEquals(1,count("payment_exception"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_EXPIRE'",Integer.class));}finally{pool.shutdownNow();}}
    @Test void creationAuditFailureAndExpiredOrderCannotOpenPayment(){long o=order(a,slot(1));jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");assertEquals(503,assertThrows(ResponseStatusException.class,()->pay(o)).getStatusCode().value());assertEquals(0,count("payment"));jdbc.execute("DROP TRIGGER reject_reservation_audit");clock.value=clock.value.plusSeconds(900);assertEquals(409,assertThrows(ResponseStatusException.class,()->pay(o)).getStatusCode().value());assertEquals(0,count("payment"));}
    @Test void incompleteHistoricalPaymentCannotBeReprepared(){long o=order(a,slot(1));jdbc.update("INSERT INTO payment(order_id,channel,amount,status) VALUES(?,'LOCAL_TEST',12.34,'CREATED')",o);assertEquals(409,assertThrows(ResponseStatusException.class,()->pay(o)).getStatusCode().value());assertEquals(1,count("payment"));}
    @Test void merchantOrdersAreScopedReadOnlyAndKeepHistoricalSnapshots(){
        long first=order(a,slot(2)),second=order(b,jdbc.queryForObject("SELECT id FROM appointment_slot",Long.class));
        var reader=new MerchantOrders(db);
        jdbc.update("UPDATE `order` SET project_snapshot=JSON_SET(project_snapshot,'$.user_id',999,'$.vin','SECRET'), merchant_snapshot=JSON_SET(merchant_snapshot,'$.phone','SECRET'), price_snapshot=JSON_SET(price_snapshot,'$.customer','SECRET') WHERE id=?",first);
        int audits=count("audit_log");
        var list=reader.list(shop,null,null,1,20);
        assertEquals(2L,list.get("total"));assertEquals(2,((List<?>)list.get("items")).size());
        var detail=reader.detail(shop,first);
        assertFalse(detail.toString().contains("SECRET"));assertFalse(detail.containsKey("vehicle_id"));assertFalse(detail.containsKey("user_id"));
        assertEquals(audits,count("audit_log"));
        jdbc.update("UPDATE merchant_project SET is_deleted=1 WHERE id=1");
        assertEquals(first,reader.detail(shop,first).get("order_id"));
        assertEquals(2L,reader.list(shop,null,null,1,20).get("total"));
    }
    @Test void merchantOrderExceptionCoversOldPaymentsAndFilters(){
        long first=order(a,slot(1)),old=pay(first);send(old,"FAILED");long current=pay(first);send(old,"SUCCEEDED");send(current,"SUCCEEDED");
        var reader=new MerchantOrders(db);
        var detail=reader.detail(shop,first);
        assertEquals(true,detail.get("has_payment_exception"));
        assertEquals(false,((Map<?,?>)detail.get("payment_summary")).get("requires_review"));
        assertEquals(1L,reader.list(shop,"PAID",null,1,20).get("total"));
        assertEquals(0L,reader.list(shop,"CLOSED",null,1,20).get("total"));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->reader.list(shop,"INVALID",null,1,20)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->reader.list(shop,null,"2026-02-30",1,20)).getStatusCode().value());
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",shop.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->reader.list(shop,null,null,1,20)).getStatusCode().value());
    }
    @Test void merchantOrdersExcludeOtherShopEvenWithSharedProject(){
        long first=order(a,slot(1));
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(2,2,'另一店','合成地址二',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account) VALUES(2,2,'MERCHANT','test-two')");
        var other=new MerchantActor(2,2,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));
        session(other.session(),"staff_account",2,"MERCHANT","merchant-account",2);
        var reader=new MerchantOrders(db);
        assertEquals(0L,reader.list(other,null,null,1,20).get("total"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->reader.detail(other,first)).getStatusCode().value());
    }
    @Test void merchantOrderDateUsesBeijingDayAndPaginates(){
        long first=order(a,slot(2)),second=order(b,jdbc.queryForObject("SELECT id FROM appointment_slot",Long.class));
        String day=clock.value.atZone(ReservationInput.ZONE).toLocalDate().plusDays(1).toString();
        var reader=new MerchantOrders(db);
        assertEquals(2L,reader.list(shop,null,day,1,1).get("total"));
        assertEquals(1,((List<?>)reader.list(shop,null,day,2,1).get("items")).size());
        assertEquals(0L,reader.list(shop,null,clock.value.atZone(ReservationInput.ZONE).toLocalDate().toString(),1,20).get("total"));
        jdbc.update("UPDATE `order` SET appointment_at=? WHERE id=?",java.sql.Timestamp.from(Instant.parse("2026-10-07T15:59:59Z")),first);
        jdbc.update("UPDATE `order` SET appointment_at=? WHERE id=?",java.sql.Timestamp.from(Instant.parse("2026-10-07T16:00:00Z")),second);
        assertEquals(1L,reader.list(shop,null,"2026-10-07",1,20).get("total"));
        assertEquals(1L,reader.list(shop,null,"2026-10-08",1,20).get("total"));
    }
}
