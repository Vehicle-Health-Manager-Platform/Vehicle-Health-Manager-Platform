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
class JdbcReservationsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static class Time extends Clock {Instant value=Instant.now().truncatedTo(ChronoUnit.SECONDS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
    Time clock;ReservationStore db;ReservationSlots slots;ReservationOrders orders;ReservationExpiry expiry;MerchantActor shop;VehicleOwner a,b;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=java.nio.file.Files.list(Path.of("..","docs","sql","migrations"))){for(var file:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(file));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V007__reservation_orders.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.update("DELETE FROM notification");jdbc.update("DELETE FROM service_notification_outbox");
        jdbc.update("DELETE FROM merchant_commission_entry");jdbc.update("DELETE FROM order_commission_snapshot");jdbc.update("DELETE FROM merchant_commission_policy");

        jdbc.execute("DROP TRIGGER IF EXISTS reject_reservation_audit");
        for(String t:List.of("project_model_applicability","model","series","brand","vehicle_health_observation","payment_event","payment_exception","payment","audit_log","idempotency_record","order","appointment_slot","merchant_project_version","merchant_project","standard_project","auth_session","staff_account","merchant","vehicle","user"))jdbc.update("DELETE FROM `"+t+"`");
        clock=new Time();var mapper=new ObjectMapper();var manager=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),clock,manager);expiry=new ReservationExpiry(db);slots=new ReservationSlots(db);orders=new ReservationOrders(db,expiry);
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
    @Test void cancelAndScheduledExpiryPublishOneClosedFactEach(){
        long slot=slot(2),one=order(a,slot),two=order(b,slot);String k=key();orders.cancel(a,k,one);orders.cancel(a,k,one);orders.cancel(a,key(),one);clock.value=clock.value.plusSeconds(901);expiry.sweep();expiry.sweep();assertEquals("CLOSED",orders.detail(b,two).get("status"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM service_notification_outbox WHERE kind='ORDER_CLOSED'",Integer.class));var notices=new ServiceNotifications(db,"test-app");notices.recover();assertEquals(1,db.mapper.valueToTree(notices.list(a,1)).path("unread_count").asInt());assertEquals(1,db.mapper.valueToTree(notices.list(b,1)).path("unread_count").asInt());
    }
    @Test void failedCloseNotificationKeepsCapacityAndOrderUntilOriginalKeyRetry(){
        long slot=slot(1),id=order(a,slot);String k=key();jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON service_notification_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");try{assertThrows(ResponseStatusException.class,()->orders.cancel(a,k,id));assertEquals("PENDING_PAYMENT",orders.detail(a,id).get("status"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));}finally{jdbc.execute("DROP TRIGGER reject_reservation_audit");}orders.cancel(a,k,id);assertEquals("CLOSED",orders.detail(a,id).get("status"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM service_notification_outbox WHERE kind='ORDER_CLOSED'",Integer.class));
    }
    long slot(int capacity){Instant start=clock.value.atZone(ReservationInput.ZONE).toLocalDate().plusDays(1).atTime(10,0).atZone(ReservationInput.ZONE).toInstant();return slots.publish(shop,key(),new ReservationInput.Slot(1,start,start.plusSeconds(3600),capacity)).path("data").path("slot_id").asLong();}
    // A4c-2: capacity is decided from the slot counter under the slot row lock, so racing
    // creations must resolve to exactly `capacity` winners and business conflicts, never a
    // lock timeout or a transient write failure.
    @Test void capacityIsDecidedAtomicallyForRacingCreations()throws Exception{
        long slot=slot(2);List<VehicleOwner> owners=new ArrayList<>();
        for(int n=3;n<=10;n++){jdbc.update("INSERT INTO user(id,openid,status) VALUES(?,?,1)",n,"capacity-"+n);jdbc.update("INSERT INTO vehicle(id,user_id,model_id,vin,current_mileage) VALUES(?,?,1,?,0)",n,n,"CAP-"+n);owners.add(owner(n));}
        var pool=Executors.newFixedThreadPool(owners.size());var start=new CountDownLatch(1);var outcomes=new java.util.concurrent.CopyOnWriteArrayList<String>();var tasks=new ArrayList<Future<?>>();
        for(VehicleOwner o:owners)tasks.add(pool.submit(()->{try{start.await();orders.create(o,key(),booking(o,slot));outcomes.add("CREATED");}catch(ReservationConflict error){outcomes.add("CONFLICT:"+error.code);}return null;}));
        start.countDown();try{for(var task:tasks)task.get(60,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(2,java.util.Collections.frequency(outcomes,"CREATED"));assertEquals(6,java.util.Collections.frequency(outcomes,"CONFLICT:40903"));
        assertEquals(2,reserved(slot));assertEquals(2,count("order"));
        long released=jdbc.queryForObject("SELECT MIN(id) FROM `order` WHERE slot_id=?",Long.class,slot);long holder=jdbc.queryForObject("SELECT user_id FROM `order` WHERE id=?",Long.class,released);
        orders.cancel(owners.get((int)holder-3),key(),released);assertEquals(1,reserved(slot));order(b,slot);assertEquals(2,reserved(slot));
    }
    @Test void expiredHoldReleasesTheSeatBeforeTheNextBooking(){
        long slot=slot(1);long first=order(a,slot);assertEquals(1,reserved(slot));
        assertEquals(40903,assertThrows(ReservationConflict.class,()->orders.create(b,key(),booking(b,slot))).code);
        clock.value=clock.value.plusSeconds(901);long second=order(b,slot);
        assertEquals("CLOSED",orders.detail(a,first).get("status"));assertEquals(1,reserved(slot));assertEquals(2,count("order"));assertTrue(second>first);
    }
    int reserved(long slot){return jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot);}
    ReservationInput.Booking booking(VehicleOwner o,long slot){return new ReservationInput.Booking(1,1,o.id(),slot);}
    long order(VehicleOwner o,long slot){return orders.create(o,key(),booking(o,slot)).path("data").path("order_id").asLong();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    PlanBookingContext planContext(){jdbc.update("INSERT INTO brand(id,name,status) VALUES(1,'合成品牌',1)");jdbc.update("INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'合成车系',1)");jdbc.update("INSERT INTO model(id,series_id,year,status) VALUES(1,1,'2026',1)");var manager=new DataSourceTransactionManager(jdbc.getDataSource());var ops=new com.autocare.platform.gateway.identity.OperatorIdentity(jdbc,null,manager);var health=new com.autocare.platform.vehicle.HealthObservationService(jdbc,db.writes,db.mapper,manager,Clock.systemUTC());return new PlanBookingContext(db,orders,slots,new VehiclePlanEvidence(db,ops,health,new ExperienceModeration(db,ops,false)));}
    PlanBookingContext.Input contextInput(Long selected){return new PlanBookingContext.Input(1,1,1,clock.value.atZone(ReservationInput.ZONE).toLocalDate().plusDays(1).toString(),1,selected);}
    @Test void planContextUsesCurrentQuoteOwnEvidenceAndReadOnlyAvailability(){var service=planContext();long s=slot(2);int audits=count("audit_log"),keys=count("idempotency_record");var out=service.read(a,contextInput(s));assertEquals(12.34,Double.parseDouble((String)((Map<?,?>)out.get("quote")).get("price")));assertEquals(s,((Number)((Map<?,?>)out.get("selected_slot")).get("slot_id")).longValue());assertEquals(1,((List<?>)((Map<?,?>)out.get("slots")).get("items")).size());assertEquals(audits,count("audit_log"));assertEquals(keys,count("idempotency_record"));jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(2,1,2,22.00,1,1)");assertEquals("22.00",((Map<?,?>)service.read(a,contextInput(null)).get("quote")).get("price"));}
    @Test void planContextRejectsWrongProjectVehicleAndRevokedOwner(){var service=planContext();var in=contextInput(null);assertEquals(404,assertThrows(ResponseStatusException.class,()->service.read(b,in)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->service.read(a,new PlanBookingContext.Input(1,2,1,in.date(),1,null))).getStatusCode().value());jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",a.session());assertEquals(401,assertThrows(ResponseStatusException.class,()->service.read(a,in)).getStatusCode().value());}
    @Test void planContextSelectedSlotCannotCrossMerchantProjectDateOrAvailability(){var service=planContext();long s=slot(1);assertNotNull(service.read(a,contextInput(s)).get("selected_slot"));for(String column:List.of("merchant_id=99","project_id=99","is_open=0","is_deleted=1")){jdbc.update("UPDATE appointment_slot SET "+column+" WHERE id=?",s);assertNull(service.read(a,contextInput(s)).get("selected_slot"));jdbc.update("UPDATE appointment_slot SET merchant_id=1,project_id=1,is_open=1,is_deleted=0 WHERE id=?",s);}var in=contextInput(s);assertNull(service.read(a,new PlanBookingContext.Input(1,1,1,clock.value.atZone(ReservationInput.ZONE).toLocalDate().toString(),1,s)).get("selected_slot"));order(b,s);assertNull(service.read(a,in).get("selected_slot"));assertEquals(0,((List<?>)((Map<?,?>)service.read(a,in).get("slots")).get("items")).size());}
    @Test void planContextExactRuleChangesAndStoppedCatalogAreVisible(){var service=planContext();jdbc.update("INSERT INTO project_model_applicability(project_id,model_id,verdict,source_reference,version,operator_id) VALUES(1,1,'NOT_APPLICABLE','合成来源',1,1)");assertEquals("NOT_APPLICABLE",db.mapper.valueToTree(service.read(a,contextInput(null))).path("evidence").path("applicability").get(0).path("verdict").asText());jdbc.update("UPDATE project_model_applicability SET verdict=NULL,source_reference=NULL,version=2");assertEquals("UNKNOWN",db.mapper.valueToTree(service.read(a,contextInput(null))).path("evidence").path("applicability").get(0).path("verdict").asText());jdbc.update("UPDATE merchant_project SET on_shelf=0 WHERE id=1");assertEquals(404,assertThrows(ResponseStatusException.class,()->service.read(a,contextInput(null))).getStatusCode().value());}
    @Test void planContextSelectedSlotIsRecheckedAcrossPaginationAndExpiredHolds(){var service=planContext();long s=slot(1);var in=contextInput(s);assertNotNull(service.read(a,new PlanBookingContext.Input(1,1,1,in.date(),2,s)).get("selected_slot"));order(b,s);clock.value=clock.value.plusSeconds(901);assertNotNull(service.read(a,in).get("selected_slot"));jdbc.update("UPDATE appointment_slot SET starts_at=?,ends_at=? WHERE id=?",ReservationStore.time(clock.value.minusSeconds(1)),ReservationStore.time(clock.value.plusSeconds(100)),s);assertNull(service.read(a,in).get("selected_slot"));}
    @Test void snapshotIdempotencyAndHistory(){long slot=slot(2);String k=key();var first=orders.create(a,k,booking(a,slot));long id=first.path("data").path("order_id").asLong();
        jdbc.update("UPDATE merchant_project SET price=99,on_shelf=0 WHERE id=1");slots.close(shop,key(),slot);
        jdbc.update("UPDATE standard_project SET status=0,project_name='新目录名称' WHERE id=1");
        assertEquals(first,orders.create(a,k,booking(a,slot)));assertEquals(1,count("order"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));
        assertEquals("12.34",((com.fasterxml.jackson.databind.JsonNode)orders.detail(a,id).get("price_snapshot")).path("price").asText());
        assertNotEquals("新目录名称",((com.fasterxml.jackson.databind.JsonNode)orders.detail(a,id).get("project_snapshot")).path("project_name").asText());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->orders.create(a,k,new ReservationInput.Booking(1,2,1,slot))).getStatusCode().value());
    }
    @Test void oneLastPlaceHasOneWinner()throws Exception{long slot=slot(1);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var x=pool.submit(()->attempt(a,slot,gate));var y=pool.submit(()->attempt(b,slot,gate));gate.countDown();var results=List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));assertTrue(results.contains(200));assertTrue(results.contains(409));assertEquals(1,count("order"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));}finally{pool.shutdownNow();}
    }
    int attempt(VehicleOwner owner,long slot,CountDownLatch gate)throws Exception{gate.await();try{order(owner,slot);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}
    @Test void cancelAndExpiryReleaseOnlyOnce(){long slot=slot(1),id=order(a,slot);clock.value=clock.value.plusSeconds(900);expiry.sweep();expiry.sweep();orders.cancel(a,key(),id);orders.cancel(a,key(),id);
        assertEquals("CLOSED",orders.detail(a,id).get("status"));assertEquals("PAYMENT_EXPIRED",orders.detail(a,id).get("close_reason"));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_EXPIRE'",Integer.class));order(b,slot);
    }
    @Test void explicitCancelRetainsReasonAndReopensPlace(){long slot=slot(1),id=order(a,slot);orders.cancel(a,key(),id);clock.value=clock.value.plusSeconds(1000);expiry.sweep();assertEquals("OWNER_CANCELLED",orders.detail(a,id).get("close_reason"));order(b,slot);}
    @Test void expiredPlaceIsBookableBeforeWorker(){long slot=slot(1);order(a,slot);clock.value=clock.value.plusSeconds(901);order(b,slot);assertEquals(2,count("order"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_EXPIRE'",Integer.class));}
    @Test void ownershipVersionAndClosedSlotBoundaries(){long slot=slot(1);assertEquals(404,assertThrows(ResponseStatusException.class,()->orders.create(a,key(),new ReservationInput.Booking(1,1,2,slot))).getStatusCode().value());
        assertEquals(40901,assertThrows(ReservationConflict.class,()->orders.create(a,key(),new ReservationInput.Booking(1,2,1,slot))).code);long id=order(a,slot);assertEquals(404,assertThrows(ResponseStatusException.class,()->orders.detail(b,id)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->orders.cancel(b,key(),id)).getStatusCode().value());slots.close(shop,key(),slot);assertEquals(40902,assertThrows(ReservationConflict.class,()->orders.create(b,key(),booking(b,slot))).code);
    }
    @Test void overlappingSlotRejectedAndAdjacentAllowed(){long id=slot(1);var row=jdbc.queryForMap("SELECT * FROM appointment_slot WHERE id=?",id);Instant start=ReservationStore.instant(row.get("starts_at"));
        assertEquals(40904,assertThrows(ReservationConflict.class,()->slots.publish(shop,key(),new ReservationInput.Slot(1,start.plusSeconds(60),start.plusSeconds(3600),1))).code);
        slots.close(shop,key(),id);assertThrows(ReservationConflict.class,()->slots.publish(shop,key(),new ReservationInput.Slot(1,start,start.plusSeconds(3600),1)));slots.publish(shop,key(),new ReservationInput.Slot(1,start.plusSeconds(3600),start.plusSeconds(7200),1));
    }
    @Test void auditFailureRollsBackReservationAndRelease(){long slot=slot(1);jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->order(a,slot)).getStatusCode().value());assertEquals(0,count("order"));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));
        jdbc.execute("DROP TRIGGER reject_reservation_audit");long id=order(a,slot);clock.value=clock.value.plusSeconds(901);jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");expiry.sweep();assertEquals("PENDING_PAYMENT",orders.detail(a,id).get("status"));assertEquals(1,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));
    }
    @Test void expiryAtSlotStartAndRevokedReplay(){Instant start=clock.value.plusSeconds(300).truncatedTo(ChronoUnit.MINUTES);long slot=slots.publish(shop,key(),new ReservationInput.Slot(1,start,start.plusSeconds(300),1)).path("data").path("slot_id").asLong();String k=key();var created=orders.create(a,k,booking(a,slot));assertEquals(start.toString(),created.path("data").path("expires_at").asText());jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",a.session());assertEquals(401,assertThrows(ResponseStatusException.class,()->orders.create(a,k,booking(a,slot))).getStatusCode().value());clock.value=start;expiry.sweep();assertEquals("CLOSED",jdbc.queryForObject("SELECT status FROM `order`",String.class));}
    @Test void concurrentWorkersReleaseExactlyOnce()throws Exception{long slot=slot(1);order(a,slot);clock.value=clock.value.plusSeconds(901);var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(expiry::sweep);var second=pool.submit(new ReservationExpiry(db)::sweep);first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_EXPIRE'",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));}finally{pool.shutdownNow();}}
    @Test void invalidWindowAndMerchantOwnership(){assertEquals(400,assertThrows(ResponseStatusException.class,()->slots.publish(shop,key(),new ReservationInput.Slot(1,clock.value.minusSeconds(10),clock.value.plusSeconds(10),1))).getStatusCode().value());long slot=slot(1);var foreign=new MerchantActor(1,2,shop.session(),shop.expires());assertEquals(401,assertThrows(ResponseStatusException.class,()->slots.close(foreign,key(),slot)).getStatusCode().value());assertEquals(400,assertThrows(ResponseStatusException.class,()->slots.available(a,1,1,"2000-01-01",1,20)).getStatusCode().value());}

    @Test void actualBookingCreatesOneFrozenSnapshotAndReplaysOnce(){long slot=slot(2);String k=key();var result=orders.create(a,k,booking(a,slot));assertEquals(result,orders.create(a,k,booking(a,slot)));assertEquals(1,count("order_commission_snapshot"));assertEquals("WAIT_POLICY",jdbc.queryForObject("SELECT state FROM order_commission_snapshot",String.class));}
    @Test void snapshotWriteFailureRollsBackBookingAndCapacity(){long slot=slot(2);jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON order_commission_snapshot FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='snapshot failure'");assertEquals(503,assertThrows(ResponseStatusException.class,()->orders.create(a,key(),booking(a,slot))).getStatusCode().value());assertEquals(0,count("order"));assertEquals(0,count("order_commission_snapshot"));assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot",Integer.class));}
}
