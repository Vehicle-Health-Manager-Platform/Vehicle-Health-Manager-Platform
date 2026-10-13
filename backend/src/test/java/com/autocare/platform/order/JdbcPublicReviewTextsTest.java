package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class JdbcPublicReviewTextsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;PublicRatings ratings;PublicReviewTexts texts,disabled;VehicleOwner owner,other;OperatorActor operator;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V034__public_review_text.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_review_text");
        for(String table:List.of("public_review_text_moderation","public_review_text","operator_account","review_publication","order_review","order_redemption","order_dispute","refund","payment_exception","payment_event","payment","audit_log","idempotency_record","auth_session","order","user","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();ratings=new PublicRatings(new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-public-owner',1),(2,'synthetic-public-other',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-public-shop','test',1),(2,2,'synthetic-other-shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-public-shop','ACTIVE')");owner=actor(1);other=actor(2);fixture(1,5);fixture(2,2);
        var store=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);
        var ops=new OperatorIdentity(jdbc,null,manager);texts=new PublicReviewTexts(store,ops,true);disabled=new PublicReviewTexts(store,ops,false);
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,can_review) VALUES(1,'synthetic-text-operator','unused','13800000000',1)");
        String session=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,key());operator=new OperatorActor(1,session,Instant.now().plusSeconds(3600));
    }
    String key(){return UUID.randomUUID().toString();}
    VehicleOwner actor(long id){String session=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,id,key());return new VehicleOwner(id,session,Instant.now().plusSeconds(3600));}
    void fixture(long id,int rating){
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(?,?,1,1,1,10,10,'COMPLETED')",id,"synthetic-public-"+id);
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(?,?,'WECHAT',?,'CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),?)",id,id,"synthetic-payment-"+id,"synthetic-channel-"+id);
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('WECHAT',?,?,?,'PAID')",key(),id,"a".repeat(64));
        jdbc.update("INSERT INTO order_redemption(id,order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES(?,?,1,1,?,0,UTC_TIMESTAMP())",id,id,id);
        jdbc.update("INSERT INTO order_review(id,order_id,user_id,redemption_id,rating,content,test_mode,submitted_at) VALUES(?,?,1,?,?,?,0,'2026-01-01 12:34:56')",id,id,id,rating,"private phone 13800000000 plate 粤A12345");
    }
    int status(Runnable f){return assertThrows(ResponseStatusException.class,f::run).getStatusCode().value();}
    Map<?,?> summary(){return (Map<?,?>)ratings.list(other,1,1,20).get("summary");}
    com.fasterxml.jackson.databind.JsonNode submit(long v,String text){return new ObjectMapper().valueToTree(Map.of("action","SUBMIT","expected_version",v,"public_text",text,"consent_version",PublicReviewTextInput.CONSENT));}
    com.fasterxml.jackson.databind.JsonNode withdraw(long v){return new ObjectMapper().valueToTree(Map.of("action","WITHDRAW","expected_version",v));}
    com.fasterxml.jackson.databind.JsonNode decision(long v,String d,String reason){var b=new LinkedHashMap<String,Object>();b.put("expected_version",v);b.put("decision",d);b.put("reason_code",reason);return new ObjectMapper().valueToTree(b);}
    long count(){return ((Number)texts.list(other,1,1,20).get("total")).longValue();}
    @Test void previewRedactsWithoutPersistenceAndPrivateSourceIsNotCopied(){
        var p=texts.preview(owner,1,new ObjectMapper().valueToTree(Map.of("public_text","服务完成 联系 １３８００００００００ 邮箱a@b.com 粤A12345 VIN 1HGCM82633A004352 https://x.invalid")));
        String s=p.get("public_text").toString();assertFalse(s.contains("138"));assertFalse(s.contains("粤A"));assertFalse(s.contains("@"));assertFalse(s.contains("1HGC"));assertFalse(s.contains("https"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
        assertEquals(422,status(()->texts.update(owner,1,key(),submit(0,"联系13800000000"))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record",Integer.class));
    }
    @Test void explicitConsentPendingApprovalAndProjectionAreSeparateFromStars(){
        assertEquals("NONE",texts.detail(owner,1).get("status"));assertEquals(0,count());texts.update(owner,1,key(),submit(0,"服务完成,问题已处理"));assertEquals(0,count());assertEquals("PENDING",texts.detail(owner,1).get("status"));
        var pending=texts.pending(operator,1,20);assertEquals(1L,pending.get("total"));assertFalse(pending.toString().contains("private phone"));texts.moderate(operator,1,key(),decision(1,"APPROVE",null));
        assertEquals(1,count());var row=(Map<?,?>)((List<?>)texts.list(other,1,1,20).get("items")).get(0);assertEquals(Set.of("public_id","public_text","published_date"),row.keySet());assertEquals("服务完成,问题已处理",row.get("public_text"));assertEquals(0L,summary().get("count"));assertEquals(true,texts.detail(owner,1).get("visible"));
        assertFalse(jdbc.queryForObject("SELECT GROUP_CONCAT(after_state) FROM audit_log",String.class).contains("服务完成"));
    }
    @Test void withdrawPurgesTextAndOldModerationCannotRestoreIt(){
        String submittedKey=key(),approvedKey=key();var first=texts.update(owner,1,submittedKey,submit(0,"维修过程说明"));texts.moderate(operator,1,approvedKey,decision(1,"APPROVE",null));texts.update(owner,1,key(),withdraw(2));assertEquals(0,count());assertNull(texts.detail(owner,1).get("public_text"));assertEquals(409,status(()->texts.moderate(operator,1,approvedKey,decision(1,"APPROVE",null))));
        assertEquals(first,texts.update(owner,1,submittedKey,submit(0,"维修过程说明")));assertEquals("WITHDRAWN",texts.detail(owner,1).get("status"));assertEquals(409,status(()->texts.update(owner,1,key(),submit(0,"新文字"))));
    }
    @Test void rejectionAndResubmissionRequireNewReviewAndNewPublicId(){
        texts.update(owner,1,key(),submit(0,"服务文本"));texts.moderate(operator,1,key(),decision(1,"REJECT","PRIVACY"));assertEquals("PRIVACY",texts.detail(owner,1).get("reason_code"));assertEquals(0,count());
        texts.update(owner,1,key(),submit(2,"重新确认文字"));String k=key();var result=texts.moderate(operator,1,k,decision(3,"APPROVE",null));assertEquals(result,texts.moderate(operator,1,k,decision(3,"APPROVE",null)));var first=texts.list(other,1,1,20).get("items").toString();
        texts.update(owner,1,key(),submit(4,"修改后的文字"));assertEquals(0,count());texts.moderate(operator,1,key(),decision(5,"APPROVE",null));assertNotEquals(first,texts.list(other,1,1,20).get("items").toString());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text_moderation",Integer.class));
    }
    @Test void authorizationOperatorPermissionAndSourceChecksApplyToReplay(){
        assertEquals(404,status(()->texts.detail(other,1)));assertEquals(404,status(()->texts.update(other,1,key(),submit(0,"文字"))));String k=key();texts.update(owner,1,k,submit(0,"独立文字"));jdbc.update("UPDATE operator_account SET can_review=0");assertEquals(403,status(()->texts.pending(operator,1,20)));assertEquals(403,status(()->texts.moderate(operator,1,key(),decision(1,"APPROVE",null))));jdbc.update("UPDATE operator_account SET can_review=1");
        jdbc.update("UPDATE payment SET channel='LOCAL_TEST' WHERE id=1");assertEquals(409,status(()->texts.update(owner,1,k,submit(0,"独立文字"))));assertEquals(409,status(()->texts.moderate(operator,1,key(),decision(1,"APPROVE",null))));texts.update(owner,1,key(),withdraw(1));assertEquals("WITHDRAWN",texts.detail(owner,1).get("status"));
    }
    @Test void disabledFeatureAllowsOwnerReadAndWithdrawButNoPublication(){
        texts.update(owner,1,key(),submit(0,"独立文字"));texts.moderate(operator,1,key(),decision(1,"APPROVE",null));assertEquals(0L,disabled.list(other,1,1,20).get("total"));assertEquals("DISABLED",disabled.detail(owner,1).get("unavailable_reason"));assertEquals(503,status(()->disabled.update(owner,1,key(),submit(2,"修改"))));assertEquals(503,status(()->disabled.pending(operator,1,20)));disabled.update(owner,1,key(),withdraw(2));assertEquals(0,count());
    }
    @Test void liveAnomaliesAndTamperedTextAreExcluded(){
        texts.update(owner,1,key(),submit(0,"独立文字"));texts.moderate(operator,1,key(),decision(1,"APPROVE",null));jdbc.update("UPDATE public_review_text SET public_text='未经授权篡改' WHERE review_id=1");assertEquals(0,count());assertEquals(false,texts.detail(owner,1).get("visible"));jdbc.update("UPDATE public_review_text SET public_text='独立文字' WHERE review_id=1");assertEquals(1,count());
        jdbc.update("INSERT INTO payment_exception(payment_id,order_id,channel,amount,reason) VALUES(1,1,'WECHAT',10,'synthetic')");assertEquals(0,count());texts.update(owner,1,key(),withdraw(2));
    }
    @Test void approvalAndOwnerChangeRollbackWithAuditFailure(){
        String k=key();jdbc.execute("CREATE TRIGGER reject_review_text BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->texts.update(owner,1,k,submit(0,"独立文字"))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text",Integer.class));jdbc.execute("DROP TRIGGER reject_review_text");texts.update(owner,1,k,submit(0,"独立文字"));
        jdbc.execute("CREATE TRIGGER reject_review_text BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->texts.moderate(operator,1,k,decision(1,"APPROVE",null))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text_moderation",Integer.class));assertEquals("PENDING",texts.detail(owner,1).get("status"));jdbc.execute("DROP TRIGGER reject_review_text");texts.moderate(operator,1,k,decision(1,"APPROVE",null));assertEquals(1,count());
    }
    @Test void concurrentWithdrawAndApproveNeverLeaveWithdrawnTextVisible()throws Exception{
        texts.update(owner,1,key(),submit(0,"独立文字"));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{start.await();try{texts.moderate(operator,1,key(),decision(1,"APPROVE",null));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});var b=pool.submit(()->{start.await();try{texts.update(owner,1,key(),withdraw(1));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});start.countDown();var outcomes=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));assertEquals(1,Collections.frequency(outcomes,200));assertEquals(1,Collections.frequency(outcomes,409));var current=texts.detail(owner,1);if(current.get("status").equals("APPROVED"))texts.update(owner,1,key(),withdraw(2));assertEquals(0,count());}finally{pool.shutdownNow();}
    }
}
