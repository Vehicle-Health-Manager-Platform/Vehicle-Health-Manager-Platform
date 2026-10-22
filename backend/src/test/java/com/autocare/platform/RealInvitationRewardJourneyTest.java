package com.autocare.platform;

import com.autocare.platform.order.InvitationRegistration;
import com.autocare.platform.order.InvitationRewards;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.exception.NotFoundException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + scheduled production worker. Upstream identity/phone results are explicitly synthetic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "JWT_SECRET=synthetic-reward-journey-secret-at-least-32-characters","WECHAT_APP_ID=reward-journey",
    "INVITE_REWARD_ENABLED=true","INVITE_REWARD_SWEEP_MS=250","INVITE_VERIFIED_IDENTITY_ENABLED=false",
    "POINTS_EARNING_ENABLED=false","POINTS_EXCHANGE_ENABLED=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealInvitationRewardJourneyTest {
    static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withDatabaseName("reward_journey")
        .withCommand("--log-bin-trust-function-creators=1").withLabel("autocare.reward.run",UUID.randomUUID().toString());
    static boolean prepared,completed;static String mysqlId;static ConfigurableApplicationContext application;
    static final List<Map<String,Object>> CASES=new ArrayList<>();
    static final Map<String,Boolean> CHECKS=new LinkedHashMap<>();
    @DynamicPropertySource static synchronized void prepare(DynamicPropertyRegistry r)throws Exception{
        MYSQL.start();mysqlId=MYSQL.getContainerId();
        if(!prepared){var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            try(var connection=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
                for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(connection,new FileSystemResource(p));}
            prepared=true;
        }
        r.add("MYSQL_HOST",MYSQL::getHost);r.add("MYSQL_PORT",MYSQL::getFirstMappedPort);r.add("MYSQL_DATABASE",MYSQL::getDatabaseName);
        r.add("MYSQL_USER",MYSQL::getUsername);r.add("MYSQL_PASSWORD",MYSQL::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired JwtEncoder encoder;
    @Autowired ConfigurableApplicationContext app;@Autowired InvitationRegistration registration;
    @Autowired InvitationRewards rewards;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<String,String> tokens=new HashMap<>(),keys=new HashMap<>();
    static final String POLICY="/api/admin/invite/reward-policies";
    String token(String label,long id,String type,String role,long shop){
        String session=UUID.randomUUID().toString(),appId=role.equals("OPERATOR")?"operator-account":role.equals("MERCHANT")?"merchant-account":"reward-journey";
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,type,id,role,appId,shop==0?null:shop,UUID.randomUUID().toString());
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("subject_type",type).claim("role",role).claim("app_id",appId).claim("jti",session);
        if(shop>0)claims.claim("merchant_id",shop);
        tokens.put(label,encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue());return session;
    }
    static String operation(String path){return path.split("\\?")[0].replaceAll("/\\d+", "/{id}");}
    JsonNode call(String name,String kind,String role,String method,String path,String body,String key,int status)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(!role.equals("anonymous"))b.header("Authorization","Bearer "+Objects.requireNonNull(tokens.get(role)));
        if(key!=null)b.header("Idempotency-Key",key);if(body!=null)b.header("Content-Type","application/json");
        var sent=b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
        var response=client.send(sent,HttpResponse.BodyHandlers.ofString());assertEquals(status,response.statusCode(),name);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),name);
        var json=mapper.readTree(response.body());assertEquals(status==200?0:status==400?40001:status*100,json.path("code").asInt(-1),name);
        CASES.add(Map.of("name",name,"operation",operation(path),"kind",kind,"role",role,"method",sent.method(),"path",path,"status",response.statusCode(),"passed",true));return json;
    }
    JsonNode write(String name,String method,String path,String role,String body)throws Exception{
        String key=UUID.randomUUID().toString();keys.put(name,key);
        var first=call(name,"normal",role,method,path,body,key,200);
        assertEquals(first,call(name+"_replay","boundary",role,method,path,body,key,200));
        call(name+"_invalid","error",role,method,path,path.endsWith("/invite/code")?"{\"user_id\":2}":"{}",UUID.randomUUID().toString(),400);
        call(name+"_anonymous","unauthorized","anonymous",method,path,body,UUID.randomUUID().toString(),401);
        call(name+"_wrong_role","unauthorized",role.equals("owner")?"merchant":"owner",method,path,body,UUID.randomUUID().toString(),403);
        return first;
    }
    JsonNode read(String name,String path,String role,boolean paged)throws Exception{
        var result=call(name,"normal",role,"GET",path,null,null,200);
        var boundary=call(name+"_boundary","boundary",role,"GET",paged?path+"?page=1000000":path.replace("scope=OWNER","scope=STAFF"),null,null,200);
        if(paged){assertTrue(boundary.path("data").path("items").isArray(),name);assertEquals(0,boundary.path("data").path("items").size(),name);}
        call(name+"_invalid","error",role,"GET",path+(path.contains("?")?"&":"?")+"user_id=2",null,null,400);
        call(name+"_anonymous","unauthorized","anonymous","GET",path,null,null,401);
        call(name+"_wrong_role","unauthorized",role.equals("owner")?"merchant":"owner","GET",path,null,null,403);return result;
    }
    ObjectNode status(long revision,String value){return mapper.createObjectNode().put("expected_revision",revision).put("status",value);}
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    String state(long batch){return jdbc.queryForObject("SELECT state FROM invite_reward_batch WHERE id=?",String.class,batch);}
    void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Scheduled reward worker did not reach expected state");Thread.sleep(100);}}
    long qualify(String label,String code,String source,boolean verified,int n){
        long user=registration.registerForLogin("synthetic-reward-"+label,code,source,"reward-journey").ownerId();
        registration.bindPhone(user,"139"+String.format("%08d",n),"reward-journey",verified);
        token(label,user,"user","OWNER",0);return user;
    }
    long batch(long user){return jdbc.queryForObject("SELECT id FROM invite_reward_batch WHERE invitee_id=?",Long.class,user);}
    String policy(String scope,long coupon,Instant from,Instant to){var b=mapper.createObjectNode().put("scope",scope).put("source_note","SYNTHETIC reward protocol fixture").put("valid_from",from.toString()).put("valid_to",to.toString()).put("invitee_coupon_id",coupon).put("invitee_publication_revision",2);
        if(scope.equals("OWNER"))b.put("inviter_coupon_id",coupon).put("inviter_publication_revision",2);else b.putNull("inviter_coupon_id").putNull("inviter_publication_revision");return b.toString();}
    @Test void realHttpQualificationFreezeAndScheduledAtomicRewardDelivery()throws Exception{
        application=app;assertEquals(131,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-reward-owner',1),(2,'synthetic-reward-other',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic shop','test',1),(2,2,'synthetic other shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-reward-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-reward-other','ACTIVE')");
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,status,can_invite,can_coupon) VALUES(1,'synthetic-invite','unused','13800000001','ACTIVE',1,0),(2,'synthetic-coupon','unused','13800000002','ACTIVE',0,1)");
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'synthetic reward',1,'test',1,100)");
        jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(1,1,1,12.34,1)");
        token("owner",1,"user","OWNER",0);token("other",2,"user","OWNER",0);token("merchant",1,"staff_account","MERCHANT",1);token("other_merchant",2,"staff_account","MERCHANT",2);token("invite",1,"operator_account","OPERATOR",0);token("coupon",2,"operator_account","OPERATOR",0);
        String ownerCode=write("owner_code","POST","/api/invite/code","owner","{}").path("data").path("code").path("value").asText();
        String staffCode=write("staff_code","POST","/api/merchant/invite/code","merchant","{}").path("data").path("code").path("value").asText();
        long unknown=qualify("unknown_policy",ownerCode,"VERIFIED_WECHAT",true,1),unknownBatch=batch(unknown);
        check("missing_policy_is_terminal",state(unknownBatch).equals("UNKNOWN_POLICY")&&count("invite_reward_item")==0);
        read("empty_policies",POLICY,"invite",true);read("empty_sources","/api/admin/invite/reward-coupons","invite",true);
        assertEquals("UNKNOWN",read("empty_current","/api/invite/reward-policy?scope=OWNER","owner",false).path("data").path("state").asText());
        Instant from=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(60),to=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(86400);
        for(int id=1;id<=2;id++){
            var terms=mapper.createObjectNode().put("name","SYNTHETIC reward "+id).put("type",id==1?"DISCOUNT":"SERVICE").put("project_id",1).put("amount",id==1?"5.00":"0.00").put("min_spend",id==1?"10.00":"0.00").put("total_quantity",id==1?3:1).put("valid_from",from.toString()).put("valid_to",to.toString());
            write("coupon_"+id,"POST","/api/merchant/coupons","merchant",mapper.createObjectNode().set("terms",terms).toString());
            write("coupon_publish_"+id,"PUT","/api/merchant/coupons/"+id+"/status","merchant",status(1,"PUBLISHED").toString());
            write("coupon_pool_"+id,"PUT","/api/admin/coupons/"+id+"/pool","coupon","{\"expected_pool_revision\":0,\"selected\":true}");
        }
        assertEquals(2,call("selected_sources","normal","invite","GET","/api/admin/invite/reward-coupons",null,null,200).path("data").path("total").asInt());
        String first=policy("OWNER",1,from,to),second=policy("OWNER",2,from,to),staff=policy("STAFF",1,from,to);
        write("policy_owner","POST",POLICY,"invite",first);
        String updated=mapper.readTree(first).deepCopy().toString();var edit=(ObjectNode)mapper.readTree(updated);edit.put("expected_revision",1).put("source_note","SYNTHETIC updated frozen source");
        write("policy_edit","PUT",POLICY+"/1","invite",edit.toString());
        call("stale_policy","boundary","invite","PUT",POLICY+"/1",edit.toString(),UUID.randomUUID().toString(),409);
        call("missing_policy","boundary","invite","PUT",POLICY+"/999999",edit.toString(),UUID.randomUUID().toString(),404);
        call("coupon_cannot_manage","unauthorized","coupon","POST",POLICY,first,UUID.randomUUID().toString(),403);
        var published=write("owner_publish","PUT",POLICY+"/1/status","invite",status(2,"PUBLISHED").toString());
        assertEquals("CONFIGURED",call("configured_owner","normal","owner","GET","/api/invite/reward-policy?scope=OWNER",null,null,200).path("data").path("state").asText());
        jdbc.execute("CREATE TRIGGER reject_reward_completion BEFORE INSERT ON audit_log FOR EACH ROW BEGIN IF NEW.action='INVITE_REWARD_COMPLETE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic reward audit failure'; END IF; END");
        long recipient=qualify("recipient",ownerCode,"VERIFIED_WECHAT",true,2),frozen=batch(recipient);
        await(()->jdbc.queryForObject("SELECT attempts FROM invite_reward_batch WHERE id=?",Integer.class,frozen)>=1);
        check("audit_failure_rolls_back_entire_pair",count("user_coupon")==0&&count("coupon_issue")==0&&count("coupon_stock_event")==0&&state(frozen).equals("READY"));
        check("policy_snapshot_frozen",jdbc.queryForObject("SELECT policy_revision FROM invite_reward_batch WHERE id=?",Integer.class,frozen)==3&&jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_item WHERE batch_id=? AND coupon_id=1",Integer.class,frozen)==2);
        write("policy_replacement","POST",POLICY,"invite",second);
        call("unique_active_policy","boundary","invite","PUT",POLICY+"/2/status",status(1,"PUBLISHED").toString(),UUID.randomUUID().toString(),409);
        write("owner_stop","PUT",POLICY+"/1/status","invite",status(3,"STOPPED").toString());
        assertEquals(published,call("old_publish_receipt","boundary","invite","PUT",POLICY+"/1/status",status(2,"PUBLISHED").toString(),keys.get("owner_publish"),200));
        call("stopped_cannot_publish","boundary","invite","PUT",POLICY+"/1/status",status(4,"PUBLISHED").toString(),UUID.randomUUID().toString(),409);
        write("replacement_publish","PUT",POLICY+"/2/status","invite",status(1,"PUBLISHED").toString());
        jdbc.execute("DROP TRIGGER reject_reward_completion");jdbc.update("UPDATE invite_reward_batch SET next_attempt_at=UTC_TIMESTAMP() WHERE id=?",frozen);
        await(()->state(frozen).equals("COMPLETE"));
        check("retry_uses_old_snapshot_once",count("coupon_issue")==2&&jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=1 AND origin='INVITE'",Integer.class)==2);
        long waitingUser=qualify("waiting",ownerCode,"VERIFIED_WECHAT",true,3),waiting=batch(waitingUser);await(()->state(waiting).equals("WAIT_STOCK"));
        check("insufficient_stock_never_partially_issues",count("coupon_issue")==2&&jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_item WHERE batch_id=? AND user_coupon_id IS NOT NULL",Integer.class,waiting)==0);
        write("policy_staff","POST",POLICY,"invite",staff);write("staff_publish","PUT",POLICY+"/3/status","invite",status(1,"PUBLISHED").toString());
        long staffRecipient=qualify("staff_recipient",staffCode,"VERIFIED_WECHAT",true,4),staffBatch=batch(staffRecipient);await(()->state(staffBatch).equals("COMPLETE"));
        check("staff_reward_is_invitee_only",jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_item WHERE batch_id=? AND role='INVITEE' AND user_id=?",Integer.class,staffBatch,staffRecipient)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_item WHERE batch_id=?",Integer.class,staffBatch)==1);
        qualify("test_identity",ownerCode,"TEST",true,5);qualify("unknown_identity",ownerCode,"UNKNOWN",true,6);qualify("unverified_phone",ownerCode,"VERIFIED_WECHAT",false,7);
        check("test_unknown_and_unverified_phone_have_no_rewards",count("invite_registration")==7&&count("invite_reward_batch")==4);
        write("stop_service_source","PUT","/api/merchant/coupons/2/status","merchant",status(2,"STOPPED").toString());
        jdbc.update("UPDATE invite_reward_batch SET next_attempt_at=UTC_TIMESTAMP() WHERE id=?",waiting);await(()->state(waiting).equals("WAIT_SOURCE"));
        check("stopped_source_does_not_issue",count("coupon_issue")==3&&jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issue WHERE coupon_id=2",Integer.class)==0);
        assertEquals("SOURCE_UNAVAILABLE",call("current_source_unavailable","boundary","owner","GET","/api/invite/reward-policy?scope=OWNER",null,null,200).path("data").path("state").asText());
        write("replacement_stop","PUT",POLICY+"/2/status","invite",status(2,"STOPPED").toString());write("staff_stop","PUT",POLICY+"/3/status","invite",status(2,"STOPPED").toString());
        int issuedBeforeReads=count("coupon_issue"),stockBeforeReads=count("coupon_stock_event"),batchesBeforeReads=count("invite_reward_batch");
        var own=read("owner_rewards","/api/invite/rewards","owner",true).path("data");assertEquals(3,own.path("total").asInt());
        var recipientRewards=call("recipient_rewards","normal","recipient","GET","/api/invite/rewards",null,null,200).path("data").path("items").get(0).path("rewards");
        int visible=0;for(var item:recipientRewards){if(item.path("own").asBoolean()){visible++;assertTrue(item.path("user_coupon_id").asLong()>0);}else assertTrue(item.path("user_coupon_id").isNull());}check("recipient_sees_only_own_coupon_id",visible==1);
        var staffView=read("staff_rewards","/api/merchant/invite/rewards","merchant",true).path("data");assertEquals(1,staffView.path("total").asInt());
        var detail=staffView.path("items").get(0).path("rewards");check("staff_owner_id_overlap_does_not_leak_coupon",detail.size()==1&&!detail.get(0).path("own").asBoolean()&&detail.get(0).path("user_coupon_id").isNull());
        assertEquals(0,call("foreign_owner_rewards","unauthorized","other","GET","/api/invite/rewards",null,null,200).path("data").path("total").asInt());
        assertEquals(0,call("foreign_shop_rewards","unauthorized","other_merchant","GET","/api/merchant/invite/rewards",null,null,200).path("data").path("total").asInt());
        assertEquals(1,read("owner_coupon","/api/coupon/mine","owner",true).path("data").path("total").asInt());
        assertEquals(1,call("recipient_coupon","normal","recipient","GET","/api/coupon/mine",null,null,200).path("data").path("total").asInt());
        assertEquals(1,call("staff_recipient_coupon","normal","staff_recipient","GET","/api/coupon/mine",null,null,200).path("data").path("total").asInt());
        assertEquals(0,call("waiting_coupon","boundary","waiting","GET","/api/coupon/mine",null,null,200).path("data").path("total").asInt());
        assertEquals(3,call("final_policies","normal","invite","GET",POLICY,null,null,200).path("data").path("total").asInt());
        assertEquals("UNKNOWN",call("current_after_stop","boundary","owner","GET","/api/invite/reward-policy?scope=OWNER",null,null,200).path("data").path("state").asText());
        call("duplicate_scope","error","owner","GET","/api/invite/reward-policy?scope=OWNER&scope=STAFF",null,null,400);
        call("duplicate_page","error","owner","GET","/api/invite/rewards?page=1&page=2",null,null,400);
        check("reads_never_issue_rewards",count("coupon_issue")==issuedBeforeReads&&count("coupon_stock_event")==stockBeforeReads&&count("invite_reward_batch")==batchesBeforeReads);
        check("unknown_policy_not_backfilled",state(unknownBatch).equals("UNKNOWN_POLICY"));
        check("final_inventory_and_audit_unique",count("user_coupon")==3&&count("coupon_issue")==3&&count("coupon_stock_event")==3&&jdbc.queryForObject("SELECT issued_quantity FROM coupon WHERE id=1",Integer.class)==3&&jdbc.queryForObject("SELECT issued_quantity FROM coupon WHERE id=2",Integer.class)==0&&jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='INVITE_REWARD_COMPLETE'",Integer.class)==2);
        check("no_money_or_points",count("payment")==0&&count("point_task_event")==0);
        check("policy_stops_not_undone",jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_policy WHERE status='STOPPED'",Integer.class)==3);
        int before=count("coupon_issue");rewards.process(frozen);rewards.process(staffBatch);Thread.sleep(600);check("completed_batches_never_issue_twice",count("coupon_issue")==before);
        for(var response:List.of(own,staffView)){String text=response.toString();check("public_projection_has_no_identity_evidence",!text.contains("identity_session")&&!text.contains("phone_hash")&&!text.contains("source_id")&&!text.contains("openid"));}
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations")){try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->dir.equals("backend/src")?(p.toString().endsWith(".java")||p.startsWith(root.resolve("backend/src/main"))):p.toString().endsWith(".sql")).forEach(files::add);}}
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_reward_acceptance.py"));files.sort(Comparator.comparing(p->root.relativize(p).toString().replace('\\','/')));
        var digest=MessageDigest.getInstance("SHA-256");for(var file:files){digest.update(root.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(file));}return HexFormat.of().formatHex(digest.digest());
    }
    @AfterAll static void onNormalClose()throws Exception{
        if(application==null){cleanup();return;}
        application.addApplicationListener(event->{if(event instanceof ContextClosedEvent closed&&closed.getApplicationContext()==application)try{cleanup();}catch(Exception|AssertionError failure){completed=false;}});
    }
    static void cleanup()throws Exception{
        MYSQL.stop();if(mysqlId!=null)assertThrows(NotFoundException.class,()->DockerClientFactory.instance().client().inspectContainerCmd(mysqlId).exec());
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","invitation-reward-evidence.json").toFile(),Map.of("passed",true,"source_sha256",sourceDigest(),"cases",CASES,"checks",CHECKS,"cleanup",Map.of("mysql",true),"scope","real HTTP and scheduled worker; synthetic upstream registration/phone protocol results, no real provider"));
    }
}
