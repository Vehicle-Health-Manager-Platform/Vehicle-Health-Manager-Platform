package com.autocare.platform.merchant;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
import com.autocare.platform.order.MerchantOrders;
import com.autocare.platform.order.ReservationStore;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.MerchantQuotes;
import com.autocare.platform.service.QuoteInput;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** R1b：店长只管本店、停用即时阻断原会话、员工码轮换与门店资料审计。 */
@Testcontainers
class JdbcMerchantStaffTest {
    private static final Set<String> PROFILE_FIELDS = Set.of("name", "address", "contact_phone", "lng", "lat");
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper=new ObjectMapper();
    DataSourceTransactionManager transactions;
    MerchantStaff service;MerchantProfile profiles;MerchantQuotes quotes;MerchantOrders orders;
    MerchantActor manager,foreignManager,clerk;

    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
            for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
        }
    }

    @BeforeEach void setup(){
        for(String table:List.of("audit_log","idempotency_record","auth_session","staff_wechat_identity","staff_account","merchant"))
            jdbc.update("DELETE FROM `"+table+"`");
        var transactions=new DataSourceTransactionManager(jdbc.getDataSource());
        this.transactions=transactions;
        var integrity=new WriteIntegrityService(jdbc,mapper,transactions);
        service=new MerchantStaff(jdbc,integrity,mapper,new StaffCodeOperations(jdbc),"test-app",true,transactions);
        profiles=new MerchantProfile(jdbc,integrity,mapper,true,transactions);
        orders=new MerchantOrders(new ReservationStore(jdbc,mapper,integrity,Clock.systemUTC(),transactions));
        quotes=new MerchantQuotes(jdbc,integrity,mapper,new ServiceCatalog(jdbc,transactions),transactions);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,contact_phone,status) VALUES"
            + "(1,2,'本店甲','广州市天河区演示路1号','13800000000',1),(2,2,'本店乙','广州市海珠区演示路2号','13800000001',1),"
            + "(3,2,'未在营','广州市越秀区演示路3号','13800000002',0)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,display_name,status) VALUES"
            + "(1,1,'MERCHANT','m1','店长甲','ACTIVE'),(2,2,'MERCHANT','m2','店长乙','ACTIVE'),"
            + "(3,3,'MERCHANT','m3','店长丙','ACTIVE'),"
            + "(20,1,'STAFF','s1-9','店员甲','ACTIVE'),(21,1,'TECHNICIAN','t1-9','技师甲','ACTIVE'),"
            + "(22,2,'TECHNICIAN','t2-9','技师乙','ACTIVE')");
        manager=actor(1,1,"MERCHANT");foreignManager=actor(2,2,"MERCHANT");clerk=actor(20,1,"STAFF");
    }

    String key(){return UUID.randomUUID().toString();}
    JsonNode json(String raw){try{return mapper.readTree(raw);}catch(Exception error){throw new IllegalStateException(error);}}

    MerchantActor actor(long staffId,long shop,String role){
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) "
            + "VALUES(?,'staff_account',?,?,'merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",
            session,staffId,role,shop,UUID.randomUUID().toString());
        return new MerchantActor(staffId,shop,role,session,Instant.now().plusSeconds(3600));
    }

    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Integer.class);}
    int count(String table,String where,Object... args){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"` WHERE "+where,Integer.class,args);}
    int audits(String action,long resource){return count("audit_log","action=? AND resource_id=?",action,resource);}

    /** 幂等记录与审计是真实落库载荷，任何明文口令或员工码出现在这里都是泄露。 */
    String persisted(){
        var responses=jdbc.queryForList("SELECT COALESCE(response_body,'') FROM idempotency_record",String.class);
        var audits=jdbc.queryForList("SELECT CONCAT(COALESCE(CAST(before_state AS CHAR),''),'|',"
            + "COALESCE(CAST(after_state AS CHAR),'')) FROM audit_log",String.class);
        return String.join("\n",String.join("|",responses),String.join("|",audits));
    }

    static List<String> names(JsonNode node){var result=new ArrayList<String>();node.fieldNames().forEachRemaining(result::add);return result;}

    @Test void createUsesGeneratedAccountsAndNeverPersistsPlaintextSecrets(){
        var created=service.create(manager,key(),json("{\"role\":\"STAFF\",\"display_name\":\"前台小李\",\"phone\":\"13800000000\",\"password\":\"passw0rd1\"}")).path("data");
        long id=created.path("staff_id").asLong();
        assertEquals("s1-1",created.path("account").asText());
        assertEquals("STAFF",created.path("role").asText());
        assertEquals("ACTIVE",created.path("status").asText());
        assertEquals("138****0000",created.path("phone_masked").asText());
        assertFalse(created.has("password"));
        var row=jdbc.queryForMap("SELECT account,display_name,created_by,status,password_hash FROM staff_account WHERE id=?",id);
        assertEquals("s1-1",row.get("account"));
        assertEquals(1L,((Number)row.get("created_by")).longValue());
        assertTrue(row.get("password_hash").toString().startsWith("$2"));
        assertEquals("t1-1",service.create(manager,key(),json("{\"role\":\"TECHNICIAN\",\"display_name\":\"机修小张\",\"password\":\"passw0rd2\"}")).path("data").path("account").asText());
        assertFalse(persisted().contains("passw0rd1"));
        assertFalse(persisted().contains("passw0rd2"));
        var page=service.list(manager,null,1,20);
        assertEquals(4L,((Number)page.get("total")).longValue());
        assertFalse(page.get("items").toString().contains("13800000000"));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)page.get("items");
        assertTrue(items.stream().noneMatch(item->"MERCHANT".equals(item.get("role"))));
        assertEquals(2L,((Number)service.list(manager,"TECHNICIAN",1,20).get("total")).longValue());
        // 创建响应必须与列表行逐字段同形：少字段会让客户端把合法响应判成协议错误。
        var createdRow=items.stream().filter(item->((Number)item.get("staff_id")).longValue()==id).findFirst().orElseThrow();
        assertEquals(new TreeSet<>(names(created)),new TreeSet<>(createdRow.keySet()));
    }

    @Test void anotherStoreCannotSeeOrTouchThisStoreStaff(){
        assertEquals(1L,((Number)service.list(foreignManager,null,1,20).get("total")).longValue());
        service.create(foreignManager,key(),json("{\"role\":\"STAFF\",\"display_name\":\"店员乙\",\"phone\":\"13800000009\",\"password\":\"passw0rd3\"}"));
        assertEquals("s2-1",jdbc.queryForObject("SELECT account FROM staff_account WHERE merchant_id=2 AND display_name='店员乙'",String.class));
        for(long id:List.of(20L,21L)){
            assertEquals(404,status(()->service.disable(foreignManager,id,key())));
            assertEquals(404,status(()->service.enable(foreignManager,id,key())));
            assertEquals(404,status(()->service.issueCode(foreignManager,id)));
            assertEquals(404,status(()->service.revokeCode(foreignManager,id)));
        }
        // 店长账号不可经本接口启停：本店与外店一样按不存在处理。
        assertEquals(404,status(()->service.disable(manager,1L,key())));
        assertEquals(404,status(()->service.disable(foreignManager,1L,key())));
    }

    @Test void disableRevokesExistingSessionsImmediatelyAndIsIdempotent(){
        String clerkSession=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) "
            + "VALUES(?,'staff_account',20,'STAFF','merchant-account',1,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",clerkSession,UUID.randomUUID().toString());
        String technicianSession=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) "
            + "VALUES(?,'staff_account',21,'TECHNICIAN','test-app',1,101,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",technicianSession,UUID.randomUUID().toString());
        jdbc.update("INSERT INTO staff_wechat_identity(app_id,openid,staff_account_id) VALUES('test-app','wx-t1',21)");
        var liveClerk=new MerchantActor(20,1,"STAFF",clerkSession,Instant.now().plusSeconds(3600));
        orders.list(liveClerk,null,null,1,20); // 店员的履约执行面本来可用

        String disableKey=key();
        var first=service.disable(manager,20,disableKey).path("data");
        assertEquals("DISABLED",first.path("status").asText());
        assertTrue(first.path("sessions_revoked").asInt()>=1);
        assertEquals(0,count("auth_session","id=? AND revoked_at IS NULL",clerkSession));
        // 会话被撤销后，同一令牌立即不可再用。
        assertEquals(401,status(()->orders.list(liveClerk,null,null,1,20)));
        // 重复停用是幂等成功，且同幂等键返回原响应、不多写审计。
        assertEquals(service.disable(manager,20,disableKey).toString(),service.disable(manager,20,disableKey).toString());
        assertEquals(1,audits("MERCHANT_STAFF_DISABLE",20));
        assertEquals(0,service.disable(manager,20,key()).path("data").path("sessions_revoked").asInt());

        service.disable(manager,21,key());
        assertEquals("DISABLED",jdbc.queryForObject("SELECT status FROM staff_account WHERE id=21",String.class));
        assertNull(jdbc.queryForObject("SELECT employee_code_hash FROM staff_account WHERE id=21",String.class));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM staff_wechat_identity WHERE staff_account_id=21 AND is_deleted=0",String.class));
        assertEquals(0,count("auth_session","id=? AND revoked_at IS NULL",technicianSession));

        // 启用只恢复状态，不恢复已撤销的微信绑定。
        service.enable(manager,21,key());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM staff_account WHERE id=21",String.class));
        assertEquals(0,count("staff_wechat_identity","staff_account_id=21 AND status='ACTIVE' AND is_deleted=0"));
    }

    @Test void clerkKeepsExecutionPlaneButIsBlockedFromManagementPlane(){
        assertEquals(403,status(()->service.create(clerk,key(),json("{\"role\":\"STAFF\",\"display_name\":\"新店员\",\"phone\":\"13800000008\",\"password\":\"passw0rd4\"}"))));
        assertEquals(403,status(()->service.disable(clerk,21L,key())));
        assertEquals(403,status(()->service.enable(clerk,21L,key())));
        assertEquals(403,status(()->service.issueCode(clerk,21L)));
        assertEquals(403,status(()->service.revokeCode(clerk,21L)));
        assertEquals(403,status(()->profiles.update(clerk,key(),json("{\"name\":\"改名\",\"address\":\"换址\",\"contact_phone\":\"13800000000\",\"lng\":null,\"lat\":null}"))));
        assertEquals(403,status(()->quotes.save(clerk,key(),QuoteInput.parse(json("{\"standard_project_id\":1,\"price\":\"9.99\",\"status\":1}")))));
        // 执行面与只读面仍可用。
        assertEquals(0L,((Number)orders.list(clerk,null,null,1,20).get("total")).longValue());
        assertEquals(2L,((Number)service.list(clerk,null,1,20).get("total")).longValue());
        var profile=profiles.read(clerk);
        assertEquals(1L,((Number)profile.get("merchant_id")).longValue());
        assertEquals(Boolean.FALSE,profile.get("can_edit"));
        quotes.ownList(clerk,1,20);
        // 令牌角色与员工行角色不一致时同样不可用。
        var forged=new MerchantActor(20,1,"MERCHANT",clerk.session(),Instant.now().plusSeconds(3600));
        assertEquals(401,status(()->service.list(forged,null,1,20)));
        assertEquals(401,status(()->orders.list(forged,null,null,1,20)));
    }

    @Test void employeeCodeRotatesRevokesAndGuardsTargets(){
        String first=service.issueCode(manager,21).get("employee_code").toString();
        assertEquals(AuthTokens.sha256(first),jdbc.queryForObject("SELECT employee_code_hash FROM staff_account WHERE id=21",String.class));
        String rotated=service.issueCode(manager,21).get("employee_code").toString();
        assertNotEquals(first,rotated);
        assertEquals(AuthTokens.sha256(rotated),jdbc.queryForObject("SELECT employee_code_hash FROM staff_account WHERE id=21",String.class));
        assertFalse(persisted().contains(first));
        assertFalse(persisted().contains(rotated));
        assertEquals(2,audits("STAFF_CODE_ISSUE",21));

        jdbc.update("INSERT INTO staff_wechat_identity(app_id,openid,staff_account_id) VALUES('test-app','wx-rotate',21)");
        service.revokeCode(manager,21);
        assertNull(jdbc.queryForObject("SELECT employee_code_hash FROM staff_account WHERE id=21",String.class));
        assertEquals(0,count("staff_wechat_identity","staff_account_id=21 AND status='ACTIVE' AND is_deleted=0"));
        assertEquals(1,audits("STAFF_CODE_REVOKE",21));

        // 非技师、跨店与已停用目标都被拒绝。
        assertEquals(404,status(()->service.issueCode(manager,20L)));
        assertEquals(404,status(()->service.issueCode(foreignManager,21L)));
        assertEquals(404,status(()->service.revokeCode(foreignManager,21L)));
        service.disable(manager,21,key());
        assertEquals(409,status(()->service.issueCode(manager,21L)));
    }

    @Test void profileUpdateIsWhitelistedAuditedAndManagerOnly(){
        String updateKey=key();
        var result=profiles.update(manager,updateKey,json(
            "{\"name\":\"本店甲改名\",\"address\":\"广州市天河区新路9号\",\"contact_phone\":\"13900000000\",\"lng\":113.2644,\"lat\":23.1291}"));
        assertEquals("本店甲改名",result.path("data").path("name").asText());
        var row=jdbc.queryForMap("SELECT name,status,merchant_type,region_code FROM merchant WHERE id=1");
        assertEquals("本店甲改名",row.get("name"));
        assertEquals(1,((Number)row.get("status")).intValue());
        assertEquals(2,((Number)row.get("merchant_type")).intValue());
        assertEquals("",row.get("region_code"));
        assertEquals("本店乙",jdbc.queryForObject("SELECT name FROM merchant WHERE id=2",String.class));
        var audit=jdbc.queryForMap("SELECT before_state,after_state FROM audit_log WHERE action='MERCHANT_PROFILE_UPDATE' AND resource_id=1");
        assertEquals(new TreeSet<>(PROFILE_FIELDS),new TreeSet<>(names(json(audit.get("before_state").toString()))));
        assertEquals(new TreeSet<>(PROFILE_FIELDS),new TreeSet<>(names(json(audit.get("after_state").toString()))));
        // 同幂等键重放返回原响应且不产生第二条审计。
        // response_body 是 MySQL JSON 列，读回后键序被规范化，只能做结构比较。
        var replay=profiles.update(manager,updateKey,json(
            "{\"name\":\"本店甲改名\",\"address\":\"广州市天河区新路9号\",\"contact_phone\":\"13900000000\",\"lng\":113.2644,\"lat\":23.1291}"));
        assertEquals(result,replay);
        // 必须是原响应本身，而不是"内容相同的新响应"。
        assertEquals(result.path("request_id").asText(),replay.path("request_id").asText());
        assertEquals(1,audits("MERCHANT_PROFILE_UPDATE",1));
        // 未在营门店不可改资料；跨店读只返回自己的店。
        assertEquals(404,status(()->profiles.update(actor(3,3,"MERCHANT"),key(),json(
            "{\"name\":\"未在营\",\"address\":\"换址\",\"contact_phone\":\"13800000002\",\"lng\":null,\"lat\":null}"))));
        assertEquals(2L,((Number)profiles.read(foreignManager).get("merchant_id")).longValue());
        assertEquals(Boolean.TRUE,profiles.read(manager).get("can_edit"));
    }

    /**
     * 未填经纬度是可保存的合法状态，读写两条路径也必须给出同一个坐标字符串。
     * 这两点都曾经是真实缺陷：`Map.of` 不接受 null 值，坐标为空时成功路径抛 NPE，
     * 又被 WriteIntegrityService 兜底成 50300「写入服务暂不可用」——现象与后端不可用完全一样。
     */
    @Test void profileCoordinatesAreOptionalAndNormalized(){
        var cleared=profiles.update(manager,key(),json("{\"name\":\"本店甲\",\"address\":\"广州市天河区演示路1号\","
            + "\"contact_phone\":\"13800000000\",\"lng\":null,\"lat\":null}")).path("data");
        assertTrue(cleared.path("lng").isNull());
        assertTrue(cleared.path("lat").isNull());
        assertNull(jdbc.queryForObject("SELECT lng FROM merchant WHERE id=1",String.class));
        assertNull(profiles.read(manager).get("lng"));
        assertNull(profiles.read(manager).get("lat"));

        // DECIMAL(10,7) 直接读回会补满小数位（113.2644000），写响应却来自请求 JSON（113.2644）。
        String savedKey=key();
        var saved=profiles.update(manager,savedKey,json("{\"name\":\"本店甲\",\"address\":\"广州市天河区演示路1号\","
            + "\"contact_phone\":\"13800000000\",\"lng\":113.2644,\"lat\":23.1291}")).path("data");
        assertEquals("113.2644",saved.path("lng").asText());
        assertEquals(saved.path("lng").asText(),profiles.read(manager).get("lng"));
        assertEquals(saved.path("lat").asText(),profiles.read(manager).get("lat"));
        // 尾随 0 不同是同一个意图：同键重放必须返回原响应，而不是「同一幂等键用于不同请求」。
        var replay=profiles.update(manager,savedKey,json("{\"name\":\"本店甲\",\"address\":\"广州市天河区演示路1号\","
            + "\"contact_phone\":\"13800000000\",\"lng\":113.26440000,\"lat\":23.1291000}")).path("data");
        assertEquals("113.2644",replay.path("lng").asText());
        assertEquals(2,audits("MERCHANT_PROFILE_UPDATE",1));
    }

    @Test void switchOffBlocksReadsAndWrites(){
        var integrity=new WriteIntegrityService(jdbc,mapper,transactions);
        var closed=new MerchantStaff(jdbc,integrity,mapper,new StaffCodeOperations(jdbc),"test-app",false,transactions);
        var closedProfiles=new MerchantProfile(jdbc,integrity,mapper,false,transactions);
        assertEquals(503,status(()->closed.list(manager,null,1,20)));
        assertEquals(503,status(()->closed.create(manager,key(),json("{\"role\":\"STAFF\",\"display_name\":\"前台小李\",\"phone\":\"13800000000\",\"password\":\"passw0rd1\"}"))));
        assertEquals(503,status(()->closed.disable(manager,20L,key())));
        assertEquals(503,status(()->closed.issueCode(manager,21L)));
        assertEquals(503,status(()->closedProfiles.read(manager)));
        assertEquals(503,status(()->closedProfiles.update(manager,key(),json("{\"name\":\"改名\",\"address\":\"换址\",\"contact_phone\":\"13800000000\",\"lng\":null,\"lat\":null}"))));
        assertEquals(0,count("audit_log"));
    }

    @Test void concurrentCreatesGetDistinctAccounts()throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=new ArrayList<Callable<String>>();
            for(int index=0;index<2;index++){
                final int seq=index;
                tasks.add(()->service.create(manager,key(),json("{\"role\":\"STAFF\",\"display_name\":\"并店员"+seq+"\",\"phone\":\"1380000001"+seq+"\",\"password\":\"passw0rd"+seq+"\"}"))
                    .path("data").path("account").asText());
            }
            var accounts=new TreeSet<String>();
            for(var future:pool.invokeAll(tasks)) accounts.add(future.get());
            assertEquals(Set.of("s1-1","s1-2"),accounts);
            assertEquals(2,count("staff_account","merchant_id=1 AND role='STAFF' AND is_deleted=0 AND account IN ('s1-1','s1-2')"));
        }finally{pool.shutdownNow();}
    }
}
