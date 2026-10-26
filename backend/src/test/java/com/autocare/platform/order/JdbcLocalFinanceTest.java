package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
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
class JdbcLocalFinanceTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();LocalFinance finance;OperatorActor actor;
    Instant now=Instant.parse("2026-10-24T04:00:00Z");
    Instant start=Instant.parse("2026-10-22T16:00:00Z");
    @BeforeAll static void schema() throws Exception {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        try(var c=source.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
            for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(path));
        }
    }
    @BeforeEach void setup() {
        for(String table:List.of("refund_event","refund","payment_event","payment_exception","payment","order","operator_account","auth_session","audit_log","idempotency_record"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var db=new ReservationStore(jdbc,json,new WriteIntegrityService(jdbc,json,manager),Clock.fixed(now,ZoneOffset.UTC),manager);
        finance=new LocalFinance(db,new OperatorIdentity(jdbc,null,manager));
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,can_analytics) VALUES(1,'finance-test','unused','13800000000',1)");
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,UUID.randomUUID().toString());
        actor=new OperatorActor(1,session,Instant.now().plusSeconds(3600));
    }
    void payment(long id,String channel,String amount,Instant at) {
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status,created_at) VALUES(?,?,1,1,1,1,?,?,'PAID',?)",id,"synthetic-"+id,amount,amount,ReservationStore.time(at.minusSeconds(60)));
        jdbc.update("INSERT INTO payment(id,order_id,channel,channel_payment_no,payment_no,amount,status,paid_at,created_at) VALUES(?,?,?,?,?,?,'SUCCEEDED',?,?)",id,id,channel,"synthetic-channel-"+id,"synthetic-payment-"+id,amount,ReservationStore.time(at),ReservationStore.time(at.minusSeconds(30)));
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES(?,?,?,?,'PAID')",channel,UUID.randomUUID().toString(),id,"a".repeat(64));
    }
    void refund(long id,Instant at) {
        jdbc.update("UPDATE `order` SET status='CLOSED',close_reason='REFUND_CANCELLED' WHERE id=?",id);
        jdbc.update("INSERT INTO refund(id,order_id,payment_id,amount,status,refunded_at,created_at,owner_consented_at,merchant_consented_at,refund_no,channel_refund_no) SELECT ?,order_id,id,amount,'SUCCEEDED',?,?,?,?,?,? FROM payment WHERE id=?",id,ReservationStore.time(at),ReservationStore.time(at.minusSeconds(10)),ReservationStore.time(at.minusSeconds(8)),ReservationStore.time(at.minusSeconds(4)),"R"+id,"synthetic-refund-"+id,id);
        jdbc.update("INSERT INTO refund_event(channel,event_id,refund_id,event_hash,outcome) SELECT channel,?,?,?,'SUCCEEDED' FROM payment WHERE id=?",UUID.randomUUID().toString(),id,"b".repeat(64),id);
    }
    JsonNode report(String date,String channel){return json.valueToTree(finance.report(actor,date,channel));}
    JsonNode report(){return report("2026-10-23","LOCAL_TEST");}
    @Test void exactDailyBoundariesAndChannelSeparation() {
        payment(1,"LOCAL_TEST","0.01",start);payment(2,"LOCAL_TEST","0.02",start.plusSeconds(1));
        payment(3,"WECHAT","900.00",start.plusSeconds(2));payment(4,"LOCAL_TEST","99.00",start.plusSeconds(86400));
        var result=report();assertEquals("0.03",result.path("payments").path("amount").asText());
        assertEquals(2,result.path("payments").path("success_count").asInt());assertEquals(start.toString(),result.path("window_from").asText());
        assertEquals("900.00",report("2026-10-23","WECHAT").path("payments").path("amount").asText());
        assertTrue(result.path("channel_statement").path("difference").isNull());assertTrue(result.path("test_mode").asBoolean());
    }
    @Test void refundsBelongToOwnDayAndNetCanBeNegative() {
        payment(1,"LOCAL_TEST","0.03",start.minusSeconds(100));refund(1,start.plusSeconds(200));
        var result=report();assertEquals("0.00",result.path("payments").path("amount").asText());
        assertEquals("0.03",result.path("refunds").path("amount").asText());assertEquals("-0.03",result.path("local_net").path("amount").asText());
    }
    @Test void badSuccessEvidenceHidesTotalsAndDoesNotBecomeZero() {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));payment(2,"LOCAL_TEST","20.00",start.plusSeconds(200));
        jdbc.update("DELETE FROM payment_event WHERE payment_id=2");
        var result=report();assertEquals(1,result.path("payments").path("anomaly_count").asInt());
        assertTrue(result.path("payments").path("amount").isNull());assertTrue(result.path("local_net").path("amount").isNull());
        assertEquals("0.00",result.path("refunds").path("amount").asText());
    }
    @Test void missingSuccessTimestampAndUnknownChannelAreVisible() {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));jdbc.update("UPDATE payment SET paid_at=NULL WHERE id=1");
        assertEquals(1,report().path("payments").path("anomaly_count").asInt());
        payment(2,"UNVERIFIED","10.00",start.plusSeconds(200));
        assertEquals(1,report().path("unclassified_count").asInt());
        assertTrue(report("2026-10-23","WECHAT").path("payments").path("amount").isNull());
    }
    @Test void failedPaymentsNeverInflateSuccessAndMalformedRefundIsUnknown() {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));jdbc.update("UPDATE payment SET status='FAILED' WHERE id=1");
        var result=report();assertEquals(1,result.path("payments").path("other_count").asInt());assertEquals("0.00",result.path("payments").path("amount").asText());
        jdbc.update("UPDATE payment SET status='SUCCEEDED' WHERE id=1");refund(1,start.plusSeconds(300));
        jdbc.update("UPDATE refund SET currency='USD' WHERE id=1");assertTrue(report().path("refunds").path("amount").isNull());
    }
    @Test void currentDayClipsObservationAndFutureDayIsRejected() {
        var result=report("2026-10-24","LOCAL_TEST");assertEquals(now.toString(),result.path("window_to").asText());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->report("2026-10-25","LOCAL_TEST")).getStatusCode().value());
    }
    @Test void orphanRefundAndDuplicateSuccessRemainUnknown() {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));refund(1,start.plusSeconds(300));
        jdbc.update("UPDATE refund SET payment_id=999 WHERE id=1");
        assertEquals(1,report().path("unclassified_count").asInt());
        assertTrue(report().path("local_net").path("amount").isNull());
        jdbc.update("DELETE FROM refund");
        jdbc.update("INSERT INTO payment(order_id,channel,amount,status,paid_at,created_at) VALUES(1,'LOCAL_TEST',10,'SUCCEEDED',?,?)",ReservationStore.time(start.plusSeconds(101)),ReservationStore.time(start));
        assertEquals(2,report().path("payments").path("anomaly_count").asInt());
    }
    @Test void refundMissingConsentAndBadTimeCannotClaimLocalSuccess() {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));refund(1,start.plusSeconds(300));
        jdbc.update("UPDATE refund SET owner_consented_at=NULL WHERE id=1");
        assertTrue(report().path("refunds").path("amount").isNull());
        jdbc.update("UPDATE refund SET owner_consented_at=? WHERE id=1",ReservationStore.time(start.plusSeconds(99)));
        assertTrue(report().path("refunds").path("amount").isNull());
        jdbc.update("UPDATE refund SET owner_consented_at=? WHERE id=1",ReservationStore.time(start.plusSeconds(200)));
        assertEquals("10.00",report().path("refunds").path("amount").asText());
        jdbc.update("UPDATE refund SET owner_consented_at=?,refunded_at=? WHERE id=1",ReservationStore.time(start.plusSeconds(200)),ReservationStore.time(start.plusSeconds(99)));
        assertEquals(1,report().path("refunds").path("anomaly_count").asInt());
    }
    @Test void dailyLimitRejectsRatherThanReturningPartialZero() {
        String row="(1,'LOCAL_TEST',1,'PENDING','2026-10-23 00:00:00')";
        jdbc.update("INSERT INTO payment(order_id,channel,amount,status,created_at) VALUES"+String.join(",",Collections.nCopies(LocalFinance.LIMIT+1,row)));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->report()).getStatusCode().value());
    }
    @Test void independentCurrentPermissionAndSessionAreRequired() {
        jdbc.update("UPDATE operator_account SET can_analytics=0,can_assessment=1,can_review=1 WHERE id=1");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->report()).getStatusCode().value());
        jdbc.update("UPDATE operator_account SET can_analytics=1 WHERE id=1");report();
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",actor.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->report()).getStatusCode().value());
    }
    @Test void readsDoNotWriteOrExposeTransactionIdentifiers() throws Exception {
        payment(1,"LOCAL_TEST","10.00",start.plusSeconds(100));
        var before=new LinkedHashMap<String,String>();
        for(String table:List.of("payment","payment_event","refund","refund_event","order","audit_log","idempotency_record","auth_session","operator_account"))before.put(table,json.writeValueAsString(jdbc.queryForList("SELECT * FROM `"+table+"` ORDER BY id")));
        String result=report().toString();report();
        for(var entry:before.entrySet())assertEquals(entry.getValue(),json.writeValueAsString(jdbc.queryForList("SELECT * FROM `"+entry.getKey()+"` ORDER BY id")));
        for(String secret:List.of("payment_no","order_id","user_id","synthetic-channel"))assertFalse(result.contains(secret));
    }
}
