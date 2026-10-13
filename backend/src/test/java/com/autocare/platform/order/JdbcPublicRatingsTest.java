package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
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
class JdbcPublicRatingsTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;PublicRatings ratings;VehicleOwner owner,other;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V033__review_publication.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_publication");
        for(String table:List.of("merchant_project_version","merchant_project","standard_project","review_publication","order_review","order_redemption","order_dispute","refund","payment_exception","payment_event","payment","audit_log","idempotency_record","auth_session","order","user","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();ratings=new PublicRatings(new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-public-owner',1),(2,'synthetic-public-other',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-public-shop','test',1),(2,2,'synthetic-other-shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-public-shop','ACTIVE')");owner=actor(1);other=actor(2);fixture(1,5);fixture(2,2);
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
    void quotes(){jdbc.update("INSERT INTO standard_project(id,category,project_name,service_content,base_price_low,base_price_high,status) VALUES(1,1,'synthetic-project','synthetic keyword 100% literal',1,20,1)");for(long i=1;i<=2;i++){jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(?,?,1,?,1)",i,i,i==1?10:5);jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(?,?,1,?,1,1)",i,i,i==1?10:5);}}
    @Test void publicRatingQuotesSortLiveQualifiedSummaryThenUnknownAndCurrentPrice(){quotes();ratings.update(owner,1,key(),true,0);ratings.update(owner,2,key(),true,0);var page=ratings.quotes(other,1,1,null);var items=(List<?>)page.get("items");assertEquals(1L,((Number)((Map<?,?>)items.get(0)).get("merchant_id")).longValue());var sum=(Map<?,?>)((Map<?,?>)items.get(0)).get("rating_summary");assertEquals("3.50",sum.get("average_rating"));assertEquals(2L,((Number)sum.get("count")).longValue());assertFalse(page.toString().contains("13800000000"));ratings.update(owner,1,key(),false,1);ratings.update(owner,2,key(),false,1);var unknown=(List<?>)ratings.quotes(other,1,1,null).get("items");assertEquals(2L,((Number)((Map<?,?>)unknown.get(0)).get("merchant_id")).longValue());assertNull(((Map<?,?>)((Map<?,?>)unknown.get(0)).get("rating_summary")).get("average_rating"));jdbc.update("UPDATE merchant_project SET on_shelf=0 WHERE id=1");assertEquals(1,((List<?>)ratings.quotes(other,1,1,null).get("items")).size());}
    @Test void directorySearchEscapesWildcardsAndUsesActiveCategories(){quotes();var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();var search=new ServiceSearch(new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager));assertEquals(1,((List<?>)search.read(owner,new ServiceSearch.Input("%",0,1)).get("items")).size());assertEquals(0,((List<?>)search.read(owner,new ServiceSearch.Input("_",0,1)).get("items")).size());assertEquals(0,((List<?>)search.read(owner,new ServiceSearch.Input("keyword",2,1)).get("items")).size());jdbc.update("UPDATE standard_project SET status=0 WHERE id=1");assertEquals(0,((List<?>)search.read(owner,new ServiceSearch.Input("keyword",0,1)).get("items")).size());}
    @Test void legacyPrivateConsentAggregationAndProjection(){
        assertEquals(0L,summary().get("count"));assertNull(summary().get("average_rating"));assertEquals(false,ratings.detail(owner,1).get("consented"));
        ratings.update(owner,1,key(),true,0);ratings.update(owner,2,key(),true,0);assertEquals(2L,summary().get("count"));assertEquals(7L,summary().get("rating_sum"));assertEquals("3.50",summary().get("average_rating"));
        var page=ratings.list(other,1,1,1);assertEquals(List.of(Map.of("rating",2,"submitted_date","2026-01-01")),page.get("items"));assertEquals(List.of(Map.of("rating",5,"submitted_date","2026-01-01")),ratings.list(other,1,2,1).get("items"));assertTrue(((List<?>)ratings.list(other,2,1,20).get("items")).isEmpty());
        assertFalse(page.toString().contains("private"));assertFalse(page.toString().contains("user_id"));assertFalse(page.toString().contains("review_id"));assertFalse(page.toString().contains("12:34"));
    }
    @Test void revokeVersionAndIdempotencyReplayCannotRepublish(){
        String k=key();var first=ratings.update(owner,1,k,true,0);assertEquals(first,ratings.update(owner,1,k,true,0));ratings.update(owner,1,key(),false,1);assertEquals(0L,summary().get("count"));
        assertEquals(first,ratings.update(owner,1,k,true,0));assertEquals(false,ratings.detail(owner,1).get("consented"));assertEquals(2L,ratings.detail(owner,1).get("version"));assertEquals(409,status(()->ratings.update(owner,1,key(),true,0)));
        assertEquals(400,status(()->ratings.update(owner,1,k,false,0)));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
    }
    @Test void authorizationAndLiveSessionApplyToReplay(){
        assertEquals(404,status(()->ratings.detail(other,1)));assertEquals(404,status(()->ratings.update(other,1,key(),true,0)));String k=key();ratings.update(owner,1,k,true,0);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->ratings.update(owner,1,k,true,0)));assertEquals(401,status(()->ratings.list(owner,1,1,20)));
    }
    @Test void testAndUntrustedSourcesHideImmediatelyButRevocationStillWorks(){
        ratings.update(owner,1,key(),true,0);
        for(String sql:List.of("UPDATE payment SET amount=11 WHERE id=1","UPDATE payment SET currency='USD' WHERE id=1","UPDATE payment SET channel='LOCAL_TEST' WHERE id=1","UPDATE payment SET paid_at=NULL WHERE id=1","UPDATE order_redemption SET test_mode=1 WHERE id=1","UPDATE order_review SET test_mode=1 WHERE id=1","UPDATE payment_event SET is_deleted=1 WHERE payment_id=1","UPDATE staff_account SET merchant_id=2 WHERE id=1","UPDATE `order` SET status='CLOSED' WHERE id=1")){
            jdbc.update(sql);assertEquals(0L,summary().get("count"));assertEquals(false,ratings.detail(owner,1).get("visible"));assertEquals(409,status(()->ratings.update(owner,1,key(),true,1)));
            jdbc.update("UPDATE payment SET amount=10,currency='CNY',channel='WECHAT',paid_at=UTC_TIMESTAMP() WHERE id=1");jdbc.update("UPDATE order_redemption SET test_mode=0 WHERE id=1");jdbc.update("UPDATE order_review SET test_mode=0 WHERE id=1");jdbc.update("UPDATE payment_event SET is_deleted=0 WHERE payment_id=1");jdbc.update("UPDATE staff_account SET merchant_id=1 WHERE id=1");jdbc.update("UPDATE `order` SET status='COMPLETED' WHERE id=1");
        }
        jdbc.update("UPDATE payment SET amount=11 WHERE id=1");ratings.update(owner,1,key(),false,1);assertEquals(false,ratings.detail(owner,1).get("consented"));
    }
    @Test void authorAndShopStopAndRefundDisputeExceptionsNeverCount(){
        ratings.update(owner,1,key(),true,0);
        jdbc.update("UPDATE user SET status=0 WHERE id=1");assertEquals(0L,summary().get("count"));jdbc.update("UPDATE user SET status=1 WHERE id=1");
        jdbc.update("UPDATE merchant SET status=0 WHERE id=1");assertEquals(404,status(()->ratings.list(other,1,1,20)));ratings.update(owner,1,key(),false,1);jdbc.update("UPDATE merchant SET status=1 WHERE id=1");ratings.update(owner,1,key(),true,2);
        jdbc.update("INSERT INTO payment_exception(payment_id,order_id,channel,amount,reason) VALUES(1,1,'WECHAT',10,'synthetic')");assertEquals(0L,summary().get("count"));jdbc.update("DELETE FROM payment_exception");
        jdbc.update("INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,opened_by,from_status,reason,status,opened_at) VALUES(1,1,1,1,'PENDING_VERIFY','synthetic','OPEN',UTC_TIMESTAMP())");assertEquals(0L,summary().get("count"));jdbc.update("DELETE FROM order_dispute");
        jdbc.update("INSERT INTO refund(order_id,payment_id,amount,status) VALUES(1,1,10,'SUCCEEDED')");assertEquals(0L,summary().get("count"));
    }
    @Test void duplicateSuccessAndMissingReceiptOrEventFailClosed(){
        ratings.update(owner,1,key(),true,0);jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('WECHAT',?,1,?,'PAID')",key(),"b".repeat(64));assertEquals(0L,summary().get("count"));
        jdbc.update("DELETE FROM payment_event WHERE event_hash=?","b".repeat(64));jdbc.update("DELETE FROM order_redemption WHERE id=1");assertEquals(0L,summary().get("count"));assertEquals(409,status(()->ratings.update(owner,1,key(),true,1)));
    }
    @Test void auditFailureRollsBackAndSameKeyRetries(){
        String k=key();jdbc.execute("CREATE TRIGGER reject_publication BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->ratings.update(owner,1,k,true,0)));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM review_publication",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record",Integer.class));jdbc.execute("DROP TRIGGER reject_publication");ratings.update(owner,1,k,true,0);assertEquals(1L,summary().get("count"));
    }
    @Test void concurrentStaleVersionsAllowOnlyOneMutation()throws Exception{
        var second=actor(1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var tasks=new ArrayList<Future<Integer>>();for(var a:List.of(owner,second))tasks.add(pool.submit(()->{start.await();try{ratings.update(a,1,key(),true,0);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));start.countDown();var outcomes=new ArrayList<Integer>();for(var t:tasks)outcomes.add(t.get(30,TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(List.of(200,409),outcomes);assertEquals(1L,ratings.detail(owner,1).get("version"));}finally{pool.shutdownNow();}
    }
}
