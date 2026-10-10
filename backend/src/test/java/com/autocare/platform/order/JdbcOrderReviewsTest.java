package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
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

@Testcontainers
class JdbcOrderReviewsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;ReservationStore db;OrderReviews reviews;VehicleOwner owner,other;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V016__order_reviews.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_review");
        for(String table:List.of("order_review_file","order_review","order_redemption","order_dispute","payment_exception","payment_event","payment","file_object","audit_log","idempotency_record","auth_session","order","user","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);reviews=new OrderReviews(db);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-review-owner',1),(2,'synthetic-review-other',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-review-shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-review-shop','ACTIVE')");
        owner=actor(1);other=actor(2);
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(1,'synthetic-reviewed',1,1,1,10,10,'COMPLETED')");
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(1,1,'LOCAL_TEST','synthetic-paid','CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),'synthetic-channel-paid')");
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST',?,1,?,'PAID')",key(),"a".repeat(64));
        jdbc.update("INSERT INTO order_redemption(id,order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES(1,1,1,1,1,1,UTC_TIMESTAMP())");
        for(int id=101;id<=103;id++)jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'user',1,?,'image/png',100,'CLEAN')",id,"synthetic-review-"+id);
    }
    String key(){return UUID.randomUUID().toString();}
    VehicleOwner actor(long id){String session=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,id,key());return new VehicleOwner(id,session,Instant.now().plusSeconds(3600));}
    JsonNode body(){return db.mapper.valueToTree(Map.of("order_id",1,"rating",5,"content","  实际服务评价🙂  ","photo_file_ids",List.of(103,101)));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    int code(Runnable f){return assertThrows(FulfillmentConflict.class,f::run).code;}
    int status(Runnable f){return assertThrows(ResponseStatusException.class,f::run).getStatusCode().value();}
    @Test void successSameAndNewKeyAreOneReviewAndMinimalAudit(){
        String k=key();var first=reviews.submit(owner,k,body());assertEquals(first,reviews.submit(owner,k,body()));assertEquals(first.path("data"),reviews.submit(owner,key(),body()).path("data"));
        assertEquals(1,count("order_review"));assertEquals(2,count("order_review_file"));assertEquals(1,count("audit_log"));assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class));
        var result=first.path("data");assertEquals(2,result.size());assertEquals(6,result.path("review").size());assertEquals("实际服务评价🙂",result.path("review").path("content").asText());assertEquals(db.mapper.valueToTree(List.of(103,101)),result.path("review").path("photo_file_ids"));assertTrue(result.path("review").path("test_mode").asBoolean());
        String audit=jdbc.queryForObject("SELECT after_state FROM audit_log",String.class);assertFalse(audit.contains("实际服务"));assertFalse(audit.contains("photo_file_ids"));assertFalse(result.toString().contains("user_id"));
        var read=reviews.detail(owner,1);assertEquals("ALREADY_REVIEWED",read.get("unavailable_reason"));assertEquals(false,read.get("can_submit"));assertNotNull(read.get("review"));
    }
    @Test void sameKeyDifferentBodyFails400AndNewKeyDifferentBodyConflicts(){
        String k=key();reviews.submit(owner,k,body());var changed=body().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("rating",1);
        assertEquals(400,status(()->reviews.submit(owner,k,changed)));assertEquals(44002,code(()->reviews.submit(owner,key(),changed)));assertEquals(1,count("order_review"));assertEquals(1,count("audit_log"));
    }
    @Test void authorizationAndRevocationApplyToReadsAndCachedWrites(){
        assertEquals(404,status(()->reviews.detail(other,1)));assertEquals(404,status(()->reviews.submit(other,key(),body())));
        String k=key();reviews.submit(owner,k,body());jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->reviews.submit(owner,k,body())));assertEquals(401,status(()->reviews.detail(owner,1)));
    }
    @Test void wrongStatusesAndLegacyCompletedCannotGainEligibility(){
        for(String state:List.of("PENDING_PAYMENT","PAID","RECEIVED","IN_SERVICE","PENDING_VERIFY","DISPUTED","CLOSED")){jdbc.update("UPDATE `order` SET status=? WHERE id=1",state);assertEquals("ORDER_NOT_COMPLETED",reviews.detail(owner,1).get("unavailable_reason"));assertEquals(44001,code(()->reviews.submit(owner,key(),body())));}
        jdbc.update("UPDATE `order` SET status='COMPLETED' WHERE id=1");jdbc.update("DELETE FROM order_redemption");assertEquals("REDEMPTION_UNVERIFIED",reviews.detail(owner,1).get("unavailable_reason"));assertEquals(44001,code(()->reviews.submit(owner,key(),body())));
    }
    @Test void openDisputeAndInconsistentReceiptBlockSubmission(){
        jdbc.update("INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,opened_by,from_status,reason,status,opened_at) VALUES(1,1,1,1,'PENDING_VERIFY','synthetic','OPEN',UTC_TIMESTAMP())");assertEquals("OPEN_DISPUTE",reviews.detail(owner,1).get("unavailable_reason"));assertEquals(44001,code(()->reviews.submit(owner,key(),body())));jdbc.update("DELETE FROM order_dispute");
        jdbc.update("UPDATE order_redemption SET merchant_id=2");assertEquals("REDEMPTION_UNVERIFIED",reviews.detail(owner,1).get("unavailable_reason"));jdbc.update("UPDATE order_redemption SET merchant_id=1,payment_id=2");assertEquals("PAYMENT_UNVERIFIED",reviews.detail(owner,1).get("unavailable_reason"));
    }
    @Test void paymentAnomaliesAndReceiptTestModeCannotBeIgnored(){
        for(String change:List.of("amount=11","currency='USD'","status='FAILED'","is_deleted=1","paid_at=NULL","channel_payment_no=NULL","channel='WECHAT'")){jdbc.update("UPDATE payment SET "+change+" WHERE id=1");assertEquals("PAYMENT_UNVERIFIED",reviews.detail(owner,1).get("unavailable_reason"));assertEquals(44001,code(()->reviews.submit(owner,key(),body())));jdbc.update("UPDATE payment SET amount=10,currency='CNY',status='SUCCEEDED',is_deleted=0,paid_at=UTC_TIMESTAMP(),channel_payment_no='synthetic-channel-paid',channel='LOCAL_TEST' WHERE id=1");}
        jdbc.update("UPDATE payment_event SET is_deleted=1");assertEquals(44001,code(()->reviews.submit(owner,key(),body())));jdbc.update("UPDATE payment_event SET is_deleted=0");
        jdbc.update("INSERT INTO payment_exception(payment_id,order_id,channel,amount,reason,is_deleted) VALUES(1,1,'LOCAL_TEST',10,'synthetic',1)");assertEquals(44001,code(()->reviews.submit(owner,key(),body())));assertEquals(0,count("order_review"));
    }
    @Test void unsafeImagesAndWrongOwnersRejectIncludingReplay(){
        for(String change:List.of("owner_id=2","owner_type='staff_account'","scan_status='INFECTED'","scan_status='PENDING'","is_deleted=1","content_type='text/plain'","size_bytes=0","size_bytes=10485761")){jdbc.update("UPDATE file_object SET "+change+" WHERE id=101");assertEquals(422,status(()->reviews.submit(owner,key(),body())));jdbc.update("UPDATE file_object SET owner_id=1,owner_type='user',scan_status='CLEAN',is_deleted=0,content_type='image/png',size_bytes=100 WHERE id=101");}
        String k=key();reviews.submit(owner,k,body());jdbc.update("UPDATE file_object SET is_deleted=1 WHERE id=101");assertEquals(422,status(()->reviews.submit(owner,k,body())));assertNotNull(reviews.detail(owner,1).get("review"));assertEquals(1,count("order_review"));
    }
    @Test void qualificationIsRecheckedOnReplayButHistoryRemainsReadable(){
        String k=key();reviews.submit(owner,k,body());jdbc.update("UPDATE payment SET amount=11 WHERE id=1");assertEquals(44001,code(()->reviews.submit(owner,k,body())));assertNotNull(reviews.detail(owner,1).get("review"));jdbc.update("UPDATE payment SET amount=10 WHERE id=1");jdbc.update("UPDATE `order` SET is_deleted=1 WHERE id=1");assertEquals(404,status(()->reviews.detail(owner,1)));
    }
    @Test void textCodePointsAndZeroOrThreePhotosAreSupported(){
        var b=(com.fasterxml.jackson.databind.node.ObjectNode)body();b.put("content","🙂".repeat(500));b.set("photo_file_ids",db.mapper.valueToTree(List.of(101,102,103)));assertEquals(500,reviews.submit(owner,key(),b).path("data").path("review").path("content").asText().codePointCount(0,1000));
        jdbc.update("DELETE FROM order_review_file");jdbc.update("DELETE FROM order_review");b.put("content","一");b.set("photo_file_ids",db.mapper.valueToTree(List.of()));assertEquals(0,reviews.submit(owner,key(),b).path("data").path("review").path("photo_file_ids").size());
    }
    @Test void allWritesRollbackAndSameKeyCanRetryAfterFailure(){
        String k=key();for(String table:List.of("order_review","order_review_file","audit_log","idempotency_record")){
            jdbc.execute("CREATE TRIGGER reject_review BEFORE "+(table.equals("idempotency_record")?"UPDATE":"INSERT")+" ON "+table+" FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->reviews.submit(owner,k,body())));for(String t:List.of("order_review","order_review_file","audit_log","idempotency_record"))assertEquals(0,count(t));jdbc.execute("DROP TRIGGER reject_review");
        }reviews.submit(owner,k,body());assertEquals(1,count("order_review"));
    }
    @Test void concurrentSameContentAcrossSessionsCommitsOneReview()throws Exception{race(false);}
    @Test void concurrentDifferentContentsOnlyOneSucceeds()throws Exception{race(true);}
    void race(boolean different)throws Exception{
        var second=actor(1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var jobs=new ArrayList<Future<Integer>>();int index=0;for(var actor:List.of(owner,second)){var b=(com.fasterxml.jackson.databind.node.ObjectNode)body();if(different && index++==1)b.put("rating",1);jobs.add(pool.submit(()->{start.await();try{reviews.submit(actor,key(),b);return 200;}catch(FulfillmentConflict e){return e.code;}}));}start.countDown();var outcomes=new ArrayList<Integer>();for(var job:jobs)outcomes.add(job.get(30,TimeUnit.SECONDS));assertEquals(different?1:2,Collections.frequency(outcomes,200));if(different)assertEquals(1,Collections.frequency(outcomes,44002));assertEquals(1,count("order_review"));assertEquals(2,count("order_review_file"));assertEquals(1,count("audit_log"));}finally{pool.shutdownNow();}
    }
}
