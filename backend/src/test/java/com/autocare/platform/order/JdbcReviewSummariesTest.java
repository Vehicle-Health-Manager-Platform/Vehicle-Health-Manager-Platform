package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.ai.DeepSeekClient;
import static org.mockito.Mockito.*;
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
class JdbcReviewSummariesTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;PublicRatings ratings;PublicReviewTexts texts,disabled;ReviewSummaries summaries;DeepSeekClient client;ReservationStore store;VehicleOwner owner,other;OperatorActor operator;
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V035__review_summary_consent.sql")));}
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_review_text");
        for(String table:List.of("review_summary_consent","public_review_text_moderation","public_review_text","operator_account","review_publication","order_review","order_redemption","order_dispute","refund","payment_exception","payment_event","payment","audit_log","idempotency_record","auth_session","order","user","staff_account","merchant"))jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();ratings=new PublicRatings(new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-public-owner',1),(2,'synthetic-public-other',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-public-shop','test',1),(2,2,'synthetic-other-shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-public-shop','ACTIVE')");owner=actor(1);other=actor(2);fixture(1,5);fixture(2,2);
        store=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);
        var ops=new OperatorIdentity(jdbc,null,manager);texts=new PublicReviewTexts(store,ops,true);disabled=new PublicReviewTexts(store,ops,false);
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,can_review) VALUES(1,'synthetic-text-operator','unused','13800000000',1)");
        String session=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,key());operator=new OperatorActor(1,session,Instant.now().plusSeconds(3600));
        client=mock(DeepSeekClient.class);summaries=new ReviewSummaries(store,client,true,true);
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
    com.fasterxml.jackson.databind.JsonNode grant(long version,long textVersion,boolean consent){return new ObjectMapper().valueToTree(Map.of("consented",consent,"expected_version",version,"expected_text_version",textVersion,"consent_version",ReviewSummaries.CONSENT));}
    void approve(long id,String text){texts.update(owner,id,key(),submit(0,text));texts.moderate(operator,id,key(),decision(1,"APPROVE",null));}
    String publicId(){return ((Map<?,?>)((List<?>)texts.list(other,1,1,20).get("items")).get(0)).get("public_id").toString();}
    @Test void privatePublicAndAiPurposesAreIndependentAndDefaultEmpty(){
        assertEquals(List.of("！？"),ReviewSummaries.sentences("！？"));
        approve(1,"服务没有问题。处理完成。");assertEquals("EMPTY",summaries.summarize(other,1).get("status"));verifyNoInteractions(client);
        var state=summaries.detail(owner,1);assertEquals(false,state.get("consented"));assertEquals(true,state.get("can_consent"));summaries.update(owner,1,key(),grant(0,2,true));var result=summaries.summarize(other,1);assertEquals("FALLBACK",result.get("status"));assertEquals(1,result.get("sample_count"));assertEquals(1L,result.get("eligible_count"));assertEquals(List.of(Map.of("public_id",publicId(),"quote","服务没有问题。")),result.get("highlights"));
    }
    @Test void aiSendsOnlyOpaqueIdsAndExactCandidatesAndReturnsSourceBackedQuotes(){
        approve(1,"服务没有问题。处理完成。");summaries.update(owner,1,key(),grant(0,2,true));String id=publicId();when(client.configured()).thenReturn(true);
        when(client.complete(anyString(),anyList(),eq(1200))).thenAnswer(call->{List<DeepSeekClient.Message> turns=call.getArgument(1);assertEquals(1,turns.size());var payload=new ObjectMapper().readTree(turns.get(0).content());var fields=new HashSet<String>();payload.fieldNames().forEachRemaining(fields::add);assertEquals(Set.of(id),fields);assertFalse(turns.get(0).content().contains("private phone"));assertFalse(turns.get(0).content().contains("2026-"));assertFalse(turns.get(0).content().contains("merchant_id"));return new DeepSeekClient.Completion("{\"highlights\":[{\"public_id\":\""+id+"\",\"quote\":\"服务没有问题。\"}]}","synthetic",0,0);});
        var result=summaries.summarize(other,1);assertEquals("AI_READY",result.get("status"));assertEquals("AI_EXTRACTIVE_V1",result.get("method"));assertEquals(1,((List<?>)result.get("sources")).size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM review_summary_consent",Integer.class));
    }
    @Test void unsupportedPartialSentenceDuplicateOrInjectedOutputFallsBack(){
        approve(1,"服务没有问题。处理完成。");summaries.update(owner,1,key(),grant(0,2,true));String id=publicId();when(client.configured()).thenReturn(true);
        for(String raw:List.of("not json","{\"highlights\":[]}","{\"highlights\":[{\"public_id\":\""+id+"\",\"quote\":\"有问题\"}]}","{\"highlights\":[{\"public_id\":\"unknown\",\"quote\":\"服务没有问题。\"}]}","{\"highlights\":[],\"summary\":\"private\"}","{\"highlights\":[],\"highlights\":[]} ","{\"highlights\":[]}{}")){
            when(client.complete(anyString(),anyList(),anyInt())).thenReturn(new DeepSeekClient.Completion(raw,"synthetic",0,0));assertEquals("FALLBACK",summaries.summarize(other,1).get("status"));}
    }
    @Test void revokeAndNewTextVersionsInvalidateOldPurposeConsent(){
        approve(1,"独立文字");String key=key();var first=summaries.update(owner,1,key,grant(0,2,true));summaries.update(owner,1,key(),grant(1,2,false));assertEquals(first,summaries.update(owner,1,key,grant(0,2,true)));assertEquals(false,summaries.detail(owner,1).get("effective"));assertEquals("EMPTY",summaries.summarize(other,1).get("status"));
        summaries.update(owner,1,key(),grant(2,2,true));texts.update(owner,1,key(),submit(2,"新文字"));texts.moderate(operator,1,key(),decision(3,"APPROVE",null));assertEquals(false,summaries.detail(owner,1).get("effective"));assertEquals(409,status(()->summaries.update(owner,1,key(),grant(3,2,true))));summaries.update(owner,1,key(),grant(3,4,true));assertEquals(true,summaries.detail(owner,1).get("effective"));
    }
    @Test void revokeDuringExternalIoDiscardsAllReturnedContent(){
        approve(1,"原授权文字");summaries.update(owner,1,key(),grant(0,2,true));String id=publicId();when(client.configured()).thenReturn(true);
        when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());summaries.update(owner,1,key(),grant(1,2,false));return new DeepSeekClient.Completion("{\"highlights\":[{\"public_id\":\""+id+"\",\"quote\":\"原授权文字\"}]}","synthetic",0,0);});
        var result=summaries.summarize(other,1);assertEquals("SOURCE_CHANGED",result.get("status"));assertEquals(List.of(),result.get("sources"));assertEquals(List.of(),result.get("highlights"));
    }
    @Test void sourceInvalidationAndViewerRevocationDuringIoAreRechecked(){
        approve(1,"原授权文字");summaries.update(owner,1,key(),grant(0,2,true));when(client.configured()).thenReturn(true);
        when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE payment SET amount=11 WHERE id=1");throw new IllegalStateException("synthetic upstream");});assertEquals("SOURCE_CHANGED",summaries.summarize(other,1).get("status"));
        jdbc.update("UPDATE payment SET amount=10 WHERE id=1");reset(client);when(client.configured()).thenReturn(true);when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",other.session());throw new IllegalStateException("synthetic upstream");});assertEquals(401,status(()->summaries.summarize(other,1)));
    }
    @Test void disabledAndUntrustedSourcesNeverSendDataAndWithdrawalStillWorks(){
        approve(1,"独立文字");summaries.update(owner,1,key(),grant(0,2,true));var disabled=new ReviewSummaries(store,client,false,true);assertEquals("DISABLED",disabled.summarize(other,1).get("status"));assertEquals(false,disabled.detail(owner,1).get("can_consent"));disabled.update(owner,1,key(),grant(1,0,false));
        jdbc.update("UPDATE payment SET channel='LOCAL_TEST' WHERE id=1");assertEquals(409,status(()->summaries.update(owner,1,key(),grant(2,2,true))));assertEquals("EMPTY",summaries.summarize(other,1).get("status"));verifyNoInteractions(client);
    }
    @Test void crossOwnerAuditFailureAndStaleVersionProtectGrant(){
        approve(1,"独立文字");assertEquals(404,status(()->summaries.detail(other,1)));String key=key();jdbc.execute("CREATE TRIGGER reject_review_text BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rollback'");assertEquals(503,status(()->summaries.update(owner,1,key,grant(0,2,true))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM review_summary_consent",Integer.class));jdbc.execute("DROP TRIGGER reject_review_text");summaries.update(owner,1,key,grant(0,2,true));assertEquals(409,status(()->summaries.update(owner,1,key(),grant(0,2,false))));
    }
    @Test void concurrentOldVersionGrantsOnlyMutateOnce()throws Exception{
        approve(1,"独立文字");var second=actor(1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var jobs=new ArrayList<Future<Integer>>();for(var a:List.of(owner,second))jobs.add(pool.submit(()->{start.await();try{summaries.update(a,1,key(),grant(0,2,true));return 200;}catch(org.springframework.web.server.ResponseStatusException e){return e.getStatusCode().value();}}));start.countDown();var outcomes=new ArrayList<Integer>();for(var j:jobs)outcomes.add(j.get(30,TimeUnit.SECONDS));Collections.sort(outcomes);assertEquals(List.of(200,409),outcomes);}finally{pool.shutdownNow();}
    }
    @Test void latestTwentyLimitDoesNotClaimAllEligibleSamples(){
        approve(1,"服务记录");summaries.update(owner,1,key(),grant(0,2,true));for(long id=3;id<=22;id++){fixture(id,3);approve(id,"独立服务记录");summaries.update(owner,id,key(),grant(0,2,true));}
        var r=summaries.summarize(other,1);assertEquals(20,r.get("sample_count"));assertEquals(21L,r.get("eligible_count"));assertTrue(((List<?>)r.get("sources")).size()<=3);
    }
    @Test void thirdConcurrentRequestDegradesInsteadOfStartingAnotherUpstreamCall()throws Exception{
        approve(1,"独立文字");summaries.update(owner,1,key(),grant(0,2,true));String id=publicId();when(client.configured()).thenReturn(true);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{entered.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));return new DeepSeekClient.Completion("{\"highlights\":[{\"public_id\":\""+id+"\",\"quote\":\"独立文字\"}]}","synthetic",0,0);});
        try{var a=pool.submit(()->summaries.summarize(other,1));var b=pool.submit(()->summaries.summarize(other,1));assertTrue(entered.await(20,TimeUnit.SECONDS));assertEquals("FALLBACK",summaries.summarize(other,1).get("status"));release.countDown();assertEquals("AI_READY",a.get(20,TimeUnit.SECONDS).get("status"));assertEquals("AI_READY",b.get(20,TimeUnit.SECONDS).get("status"));verify(client,times(2)).complete(anyString(),anyList(),anyInt());}finally{release.countDown();pool.shutdownNow();}
    }
}
