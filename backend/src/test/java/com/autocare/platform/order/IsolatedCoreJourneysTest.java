package com.autocare.platform.order;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP HTTP + production beans/security + fresh MySQL, never a pre-existing database.
 * Identity sessions and CLEAN file metadata are synthetic fixtures; funds are LOCAL_TEST only.
 */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "JWT_SECRET=isolated-journey-test-secret-at-least-32-characters",
    "WECHAT_APP_ID=isolated-journeys", "DEEPSEEK_API_KEY=unconfigured",
    "PAYMENT_LOCAL_TEST_ENABLED=true", "PAYMENT_LOCAL_TEST_SECRET=isolated-payment-secret-at-least-32-characters",
    "MERCHANT_ONBOARDING_ENABLED=true", "SERVICE_ARCHIVE_ENABLED=true", "SERVICE_ARCHIVE_INTERVAL_MS=250"
})
@ActiveProfiles("local-payment-test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsolatedCoreJourneysTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("isolated_journeys").withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        // PER_CLASS injection may build the context before Testcontainers.beforeAll.
        MYSQL.start();
        // Migrate before context creation: scheduled production beans can safely start immediately.
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (var connection=source.getConnection(); var paths=Files.list(Path.of("..","docs","sql","migrations"))) {
            for (var path: paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
        }
        registry.add("MYSQL_HOST", MYSQL::getHost);
        registry.add("MYSQL_PORT", MYSQL::getFirstMappedPort);
        registry.add("MYSQL_DATABASE", MYSQL::getDatabaseName);
        registry.add("MYSQL_USER", MYSQL::getUsername);
        registry.add("MYSQL_PASSWORD", MYSQL::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper mapper;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    String owner, foreign, applicant, merchant, otherMerchant, technician, operator;
    final Map<String,String> issuedSessions=new HashMap<>();

    @BeforeAll void fixtures() {
        assertEquals(134, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-journey-owner',1),(2,'synthetic-journey-foreign',1),(3,'synthetic-journey-applicant',1)");
        jdbc.update("INSERT INTO brand(id,name,status) VALUES(1,'合成品牌',1)");
        jdbc.update("INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'合成车系',1)");
        jdbc.update("INSERT INTO model(id,series_id,year,status) VALUES(1,1,'2026',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'合成测试门店','合成地址',1),(2,2,'合成他店','合成地址',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-other-shop','ACTIVE'),(3,1,'TECHNICIAN','synthetic-tech','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'isolated-journeys','synthetic-tech-openid',3)");
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,status,can_onboard) VALUES(1,'synthetic-operator','not-a-login-password','13800000000','ACTIVE',1)");
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'合成保养',1,'检查清洁',1,100)");
        jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(1,1,1,12.34,1)");
        jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(1,1,1,12.34,1,1)");
        owner=token(1,"user","OWNER","isolated-journeys",null,null);
        foreign=token(2,"user","OWNER","isolated-journeys",null,null);
        applicant=token(3,"user","OWNER","isolated-journeys",null,null);
        merchant=token(1,"staff_account","MERCHANT","merchant-account",1L,null);
        otherMerchant=token(2,"staff_account","MERCHANT","merchant-account",2L,null);
        technician=token(3,"staff_account","TECHNICIAN","isolated-journeys",1L,1L);
        operator=token(1,"operator_account","OPERATOR","operator-account",null,null);
        for(int id=1;id<=11;id++) file(id,"staff_account",id<=8?1:3);
        file(20,"user",3);
    }

    String key(){return UUID.randomUUID().toString();}
    String token(long subject,String type,String role,String app,Long shop,Long binding) {
        String session=key(); var now=Instant.now();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",
            session,type,subject,role,app,shop,binding,com.autocare.platform.gateway.identity.AuthTokens.sha256(key()));
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+subject)
            .issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("jti",session)
            .claim("subject_type",type).claim("role",role).claim("app_id",app);
        if(shop!=null)claims.claim("merchant_id",shop);
        if(binding!=null)claims.claim("binding_id",binding);
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();
        issuedSessions.put(token,session);
        return token;
    }
    void file(long id,String type,long subject) {
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,?,?,?,'image/png',100,'CLEAN')",id,type,subject,"synthetic-journey/"+id);
    }
    JsonNode request(String method,String path,String token,String key,Object body,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        builder.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        // Do not include bodies/tokens or private material in assertion messages.
        assertEquals(expected,response.statusCode(),method+" "+path);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),path);
        return mapper.readTree(response.body());
    }
    JsonNode get(String path,String token,int expected)throws Exception{return request("GET",path,token,null,null,expected);}
    JsonNode post(String path,String token,Object body,int expected)throws Exception{return request("POST",path,token,key(),body,expected);}
    long vehicle(String plate)throws Exception {
        return post("/api/vehicle/add",owner,Map.of("add_type",4,"model_id",1,"current_mileage",100,"plate_no",plate),200).path("data").path("vehicle_id").asLong();
    }
    void state(long id,String state){assertEquals(state,jdbc.queryForObject("SELECT status FROM `order` WHERE id=?",String.class,id));}

    @Test void ownerVehicleArchiveAndInquiryPreparation() throws Exception {
        get("/api/vehicle/list",null,401);
        get("/api/vehicle/list",merchant,403);
        var body=Map.of("add_type",4,"model_id",1,"current_mileage",12345,"plate_no","粤A12345","vin","LTEST123456789012");
        String replay=key(); var added=request("POST","/api/vehicle/add",owner,replay,body,200);
        long id=added.path("data").path("vehicle_id").asLong();assertTrue(id>0);
        assertEquals(added,request("POST","/api/vehicle/add",owner,replay,body,200));
        var changed=new HashMap<String,Object>(body);changed.put("current_mileage",1);
        request("POST","/api/vehicle/add",owner,replay,changed,400);
        var list=get("/api/vehicle/list",owner,200);assertFalse(list.toString().contains("LTEST123456789012"));
        assertFalse(list.toString().contains("粤A12345"));
        assertEquals(0,get("/api/vehicle/list",foreign,200).path("data").path("total").asInt());
        var archive=Map.of("vehicle_id",id,"archive_type",1,"recorded_date",LocalDate.now(ZoneOffset.UTC).toString(),"mileage",12345,"title","合成保养","notes","准备问诊，无外部模型调用","file_ids",List.of());
        String archiveKey=key();var saved=request("POST","/api/archive/add",owner,archiveKey,archive,200);
        assertEquals(saved,request("POST","/api/archive/add",owner,archiveKey,archive,200));
        assertEquals(1,get("/api/archive/list?vehicle_id="+id,owner,200).path("data").path("total").asInt());
        get("/api/archive/list?vehicle_id="+id,foreign,404);
        post("/api/ai/chat",foreign,Map.of("vehicle_id",id,"message","检查建议"),404);
        post("/api/ai/chat",owner,Map.of("vehicle_id",id,"message","检查建议"),503);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,id));
        String revoked=token(1,"user","OWNER","isolated-journeys",null,null);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",issuedSessions.get(revoked));
        get("/api/vehicle/list",revoked,401);
    }

    @Test void reservationThroughPickupServiceRedemptionAndArchive() throws Exception {
        long vehicle=vehicle("粤B23456");
        var start=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).atTime(10,0).atZone(ZoneId.of("Asia/Shanghai"));
        long slot=post("/api/merchant/slots",merchant,Map.of("standard_project_id",1,"starts_at",start.toOffsetDateTime().toString(),"ends_at",start.plusHours(1).toOffsetDateTime().toString(),"capacity",2),200).path("data").path("slot_id").asLong();assertTrue(slot>0);
        assertEquals("12.34",get("/api/order/quote/1",owner,200).path("data").path("price").asText());
        var booking=Map.of("merchant_project_id",1,"quote_version_id",1,"vehicle_id",vehicle,"slot_id",slot);
        var preview=new HashMap<String,Object>(booking);preview.put("selected_user_coupon_id",null);preview.put("page",1);
        assertFalse(post("/api/order/coupon-preview",owner,preview,200).path("data").path("can_create").asBoolean());
        var unavailableCoupon=new HashMap<String,Object>(booking);
        unavailableCoupon.put("user_coupon_id",999999);unavailableCoupon.put("publication_revision",1);unavailableCoupon.put("rule_version","R5B_CASH_V1");
        post("/api/order/coupon-create",owner,unavailableCoupon,404);
        assertEquals(0,jdbc.queryForObject("SELECT reserved_count FROM appointment_slot WHERE id=?",Integer.class,slot));
        String bookKey=key();var booked=request("POST","/api/order/create",owner,bookKey,booking,200);
        assertEquals(booked,request("POST","/api/order/create",owner,bookKey,booking,200));
        long id=booked.path("data").path("order_id").asLong();assertTrue(id>0);state(id,"PENDING_PAYMENT");
        get("/api/order/"+id,foreign,404);get("/api/merchant/orders/"+id,otherMerchant,404);
        post("/api/payments/create",owner,Map.of("order_id",id,"channel","WECHAT"),503);
        var paid=post("/api/payments/create",owner,Map.of("order_id",id,"channel","LOCAL_TEST"),200).path("data");
        assertTrue(paid.path("test_mode").asBoolean());
        callback(id,paid.path("payment_id").asLong());state(id,"PAID");
        String code=jdbc.queryForObject("SELECT verify_code FROM `order` WHERE id=?",String.class,id);
        var photos=new LinkedHashMap<String,Long>(); for(int i=0;i<PickupInput.SLOTS.size();i++)photos.put(PickupInput.SLOTS.get(i),(long)i+1);
        var pickup=Map.of("order_id",id,"appointment_code",code,"photos",photos,"mileage",110,"fuel_level","HALF","damage_status","NONE","damages",List.of(),"arrival_reason","合成测试提前到店");
        String pickupKey=key();var received=request("POST","/api/check/pickup/submit",merchant,pickupKey,pickup,200);
        assertEquals(received,request("POST","/api/check/pickup/submit",merchant,pickupKey,pickup,200));state(id,"RECEIVED");
        post("/api/merchant/orders/"+id+"/assign",merchant,Map.of("technician_id",3),409);
        post("/api/check/pickup/confirm",owner,Map.of("order_id",id,"decision","CONFIRM"),200);
        post("/api/merchant/orders/"+id+"/assign",merchant,Map.of("technician_id",3),200);
        post("/api/check/protection/upload",merchant,Map.of("order_id",id,"items",List.of("SEAT_COVER","STEERING_COVER"),"photo_file_id",8),200);
        post("/api/tech/orders/"+id+"/accept",technician,Map.of(),200);state(id,"IN_SERVICE");
        var report=Map.of("order_id",id,"process_photos",List.of(9),"fault_part_photos",List.of(),"finish_photos",List.of(10),"no_fault_parts",true,"repair_plan","合成检查清洁","fault_analysis","合成无故障","parts_used",List.of(),"no_parts",true,"work_hours",30);
        post("/api/tech/report/submit",merchant,report,403);
        String reportKey=key();var submitted=request("POST","/api/tech/report/submit",technician,reportKey,report,200);
        assertEquals(submitted,request("POST","/api/tech/report/submit",technician,reportKey,report,200));
        post("/api/tech/sign",technician,Map.of("order_id",id,"signature_file_id",11),200);state(id,"PENDING_VERIFY");
        String redeemKey=key();var redeemed=request("POST","/api/merchant/orders/"+id+"/redeem",merchant,redeemKey,Map.of("code",code),200);
        assertEquals(redeemed,request("POST","/api/merchant/orders/"+id+"/redeem",merchant,redeemKey,Map.of("code",code),200));state(id,"COMPLETED");
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline && !"DONE".equals(jdbc.queryForObject("SELECT status FROM service_archive_job WHERE order_id=?",String.class,id)))Thread.sleep(100);
        assertEquals("DONE",jdbc.queryForObject("SELECT status FROM service_archive_job WHERE order_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM order_redemption WHERE order_id=? AND test_mode=1",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM technician_report WHERE order_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_event WHERE payment_id=?",Integer.class,paid.path("payment_id").asLong()));
        var archives=get("/api/archive/list?vehicle_id="+vehicle,owner,200);
        assertEquals(1,archives.path("data").path("total").asInt());assertTrue(archives.toString().contains("test_mode"));
        get("/api/archive/list?vehicle_id="+vehicle,foreign,404);
    }

    void callback(long order,long payment)throws Exception {
        var body=Map.of("event_id",key(),"payment_id",payment,"channel_payment_no","synthetic-journey-"+payment,"status","SUCCEEDED","amount","12.34","currency","CNY","order_no",jdbc.queryForObject("SELECT order_no FROM `order` WHERE id=?",String.class,order),"occurred_at",Instant.now().toString());
        byte[] raw=mapper.writeValueAsBytes(body);
        String time=""+Instant.now().getEpochSecond(),nonce=key();
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("isolated-payment-secret-at-least-32-characters".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((time+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));mac.update(raw);
        String signature=HexFormat.of().formatHex(mac.doFinal(new byte[]{10}));
        for(int attempt=0;attempt<2;attempt++) {
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/payments/callback/LOCAL_TEST")).timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/json").header("X-Test-Timestamp",time).header("X-Test-Nonce",nonce).header("X-Test-Signature",signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
            assertEquals(200,http.send(req,HttpResponse.BodyHandlers.discarding()).statusCode(),"signed LOCAL_TEST callback/replay");
        }
    }

    @Test void merchantApplicationReviewAndPendingActivationBoundary() throws Exception {
        var application=Map.of("merchant_name","合成入驻店","category","REPAIR","region_code","440106","address","合成地址1号","contact_phone","13800000000","qualification_file_ids",List.of(20));
        post("/api/merchant-applications",foreign,application,409);
        String submitKey=key();var submitted=request("POST","/api/merchant-applications",applicant,submitKey,application,200);
        assertEquals(submitted,request("POST","/api/merchant-applications",applicant,submitKey,application,200));
        long id=jdbc.queryForObject("SELECT id FROM merchant_application WHERE applicant_user_id=3",Long.class);
        get("/api/admin/merchant-applications/"+id,applicant,403);
        get("/api/admin/merchant-applications/"+id,operator,200);
        String reviewKey=key();var review=Map.of("revision",1,"decision","APPROVE");
        // Strict request shape requires an explicit null reason on approval.
        var decision=new HashMap<String,Object>(review);decision.put("reason_code",null);
        assertEquals(49010,request("POST","/api/admin/merchant-applications/"+id+"/moderate",operator,reviewKey,decision,409).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application_review WHERE application_id=?",Integer.class,id));
        request("PUT","/api/admin/merchant-quotas",operator,key(),Map.of("region_code","440106","category","REPAIR","max_active",1),200);
        var approved=request("POST","/api/admin/merchant-applications/"+id+"/moderate",operator,reviewKey,decision,200);
        assertEquals(approved,request("POST","/api/admin/merchant-applications/"+id+"/moderate",operator,reviewKey,decision,200));
        long shop=jdbc.queryForObject("SELECT merchant_id FROM merchant_application WHERE id=?",Long.class,id);
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM merchant_application WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application_review WHERE application_id=?",Integer.class,id));
        assertEquals("PENDING_ACTIVATION",jdbc.queryForObject("SELECT status FROM staff_account WHERE merchant_id=?",String.class,shop));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM staff_account WHERE merchant_id=?",String.class,shop));
        post("/api/auth/merchant/login",null,Map.of("account","m"+shop,"password","not-a-real-password","sms_code","123456"),401);
        long staff=jdbc.queryForObject("SELECT id FROM staff_account WHERE merchant_id=?",Long.class,shop);
        String forged=token(staff,"staff_account","MERCHANT","merchant-account",shop,null);
        get("/api/merchant/orders",forged,401);
    }
}
