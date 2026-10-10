package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R1a 入驻：真实 MySQL 的配额并发、幂等重放、驳回重提、跨身份隔离与失败回滚。
 *
 * <p>失败回滚与死锁断言依赖库级行为，{@code test} 用户权限不足；死锁报告单独用 root 读取。
 */
@Testcontainers
class MerchantOnboardingMySqlTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;static JdbcTemplate admin;static final ObjectMapper mapper=new ObjectMapper();
    static final String SECRET="synthetic-secret-32-characters-long";
    static final Map<String,Integer> TYPES=MerchantOnboardingInput.CATEGORY_TYPES;
    ReservationStore db;MerchantOnboarding onboarding;OperatorIdentity operators;

    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        // 死锁报告只有 PROCESS 权限可读，业务连接（test 用户）不够。
        admin=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword()));
        try(var connection=source.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
            for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            // V020 必须可重复执行：CI 的 schema-mysql 会重复跑每个迁移。
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(
                Path.of("..","docs","sql","migrations","V020__merchant_onboarding.sql")));
        }
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_merchant_onboarding_account");
        for(String table:List.of("merchant_application_review","merchant_application","merchant_region_category_quota",
            "staff_account","merchant","file_object","auth_session","audit_log","idempotency_record","auth_rate_limit",
            "operator_account","user"))
            jdbc.update("DELETE FROM `"+table+"`");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);
        operators=new OperatorIdentity(jdbc,new NimbusJwtEncoder(new ImmutableSecret<>(SECRET.getBytes())),manager);
        onboarding=new MerchantOnboarding(db,operators,new AuthRateLimiter(jdbc),true);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(7001,'owner-a',1),(7002,'owner-b',1),(7003,'owner-c',1)");
        // 1、2、5 归属对应车主且已扫描干净；3 属于别人；4 归属正确但未扫描完成。
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) "
            + "VALUES(1,'user',7001,'uploads/a','image/jpeg',10,'CLEAN'),(2,'user',7001,'uploads/b','image/jpeg',10,'CLEAN'),"
            + "(3,'user',7002,'uploads/c','image/jpeg',10,'CLEAN'),(4,'user',7001,'uploads/d','image/jpeg',10,'PENDING'),"
            + "(5,'user',7003,'uploads/e','image/jpeg',10,'CLEAN')");
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,status,can_review,can_onboard) "
            + "VALUES(1,'synthetic-onboard',?,'13800000000','ACTIVE',0,1),(2,'synthetic-onboard-2',?,'13800000001','ACTIVE',1,1)",
            new BCryptPasswordEncoder().encode("synthetic-operator-password"),new BCryptPasswordEncoder().encode("synthetic-operator-password"));
    }
    VehicleOwner owner(long id){
        String session="owner-session-"+id;
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES(?,'user',?,'OWNER','miniapp',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",session,id,AuthTokens.sha256(session));
        return new VehicleOwner(id,session,Instant.now().plusSeconds(900));
    }
    OperatorActor operator(long id){
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES(?,'operator_account',?,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",session,id,AuthTokens.sha256(session));
        return new OperatorActor(id,session,Instant.now().plusSeconds(900));
    }
    JsonNode application(String name,String category,String region,List<Long> files){
        var node=mapper.createObjectNode();
        node.put("merchant_name",name);node.put("category",category);node.put("region_code",region);
        node.put("address","广州市天河区演示路1号");node.put("contact_phone","13800000000");
        var array=node.putArray("qualification_file_ids");files.forEach(array::add);
        return node;
    }
    JsonNode decision(int revision,String decision,String reason){
        var node=mapper.createObjectNode();
        node.put("revision",revision);node.put("decision",decision);
        if(reason==null)node.putNull("reason_code");else node.put("reason_code",reason);
        return node;
    }
    JsonNode quotaBody(String region,String category,int maximum){
        var node=mapper.createObjectNode();
        node.put("region_code",region);node.put("category",category);node.put("max_active",maximum);
        return node;
    }
    static String key(){return UUID.randomUUID().toString();}
    int code(Runnable action){return assertThrows(FulfillmentConflict.class,action::run).code;}
    int status(Runnable action){return assertThrows(ResponseStatusException.class,action::run).getStatusCode().value();}
    int outcome(Runnable action){try{action.run();return 200;}catch(FulfillmentConflict error){return error.getStatusCode().value();}}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class);}
    Map<String,Object> row(String table,long id){return jdbc.queryForList("SELECT * FROM `"+table+"` WHERE id=?",id).get(0);}
    long applicationId(){return jdbc.queryForObject("SELECT MAX(id) FROM merchant_application",Long.class);}
    long number(Map<String,Object> source,String name){return ((Number)source.get(name)).longValue();}
    List<Integer> concurrently(List<Callable<Integer>> tasks)throws Exception{
        var pool=Executors.newFixedThreadPool(tasks.size());var gate=new CountDownLatch(1);
        try{
            var futures=new ArrayList<Future<Integer>>();
            for(var task:tasks)futures.add(pool.submit(()->{gate.await();return task.call();}));
            gate.countDown();
            var results=new ArrayList<Integer>();
            for(var future:futures)results.add(future.get(45,TimeUnit.SECONDS));
            return results;
        }finally{pool.shutdownNow();}
    }
    long succeeded(List<Integer> results){return results.stream().filter(value->value==200).count();}
    String deadlockSection(){
        String report=admin.queryForObject("SHOW ENGINE INNODB STATUS",(rs,row)->rs.getString("Status"));
        int start=report.indexOf("LATEST DETECTED DEADLOCK");
        return start<0?"":report.substring(start,Math.min(report.length(),start+240));
    }

    @Test void qualificationFilesMustBeCleanPrivateUploadsOfTheApplicant(){
        var applicant=owner(7001);
        assertEquals(49002,code(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(3L)))));
        assertEquals(49002,code(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(4L)))));
        assertEquals(49002,code(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(99L)))));
        assertEquals(0,count("merchant_application"));

        String first=key();
        var submitted=onboarding.submit(applicant,first,application("演示汽修厂","REPAIR","440106",List.of(1L,2L)));
        var replay=onboarding.submit(applicant,first.toUpperCase(),application("演示汽修厂","REPAIR","440106",List.of(1L,2L)));
        assertEquals(submitted,replay);
        assertEquals(1,count("merchant_application"));
        assertEquals(1,count("idempotency_record"));
        assertEquals(1,count("audit_log"));
        var stored=row("merchant_application",applicationId());
        assertEquals("PENDING_REVIEW",stored.get("status"));
        assertEquals(1L,number(stored,"revision"));
        // 审核轨迹不含手机号与私有对象键
        String after=jdbc.queryForObject("SELECT after_state FROM audit_log",String.class);
        assertEquals("{}",jdbc.queryForObject("SELECT before_state FROM audit_log",String.class));
        assertFalse(after.contains("13800000000"));
        assertFalse(after.contains("uploads/"));
        // 同键不同载荷与重复未终结申请都必须被拒
        assertEquals(400,status(()->onboarding.submit(applicant,first,application("另一家店","REPAIR","440106",List.of(1L)))));
        assertEquals(49001,code(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)))));
        assertEquals(1,count("merchant_application"));
    }

    @Test void concurrentSubmissionsCreateExactlyOneApplication()throws Exception{
        var applicant=owner(7001);
        Callable<Integer> firstSubmit=()->outcome(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L))));
        Callable<Integer> secondSubmit=()->outcome(()->onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(2L))));
        var results=concurrently(new ArrayList<>(List.of(firstSubmit,secondSubmit)));
        assertEquals(Set.of(200,409),new HashSet<>(results));
        assertEquals(1,count("merchant_application"));
        assertEquals(1,count("idempotency_record"));
    }

    @Test void openingStoreConsumesQuotaAtomicallyAndCreatesPendingAccount(){
        var first=owner(7001);var second=owner(7002);
        onboarding.submit(first,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        onboarding.submit(second,key(),application("演示二店","REPAIR","440106",List.of(3L)));
        long one=jdbc.queryForObject("SELECT MIN(id) FROM merchant_application",Long.class);
        long two=jdbc.queryForObject("SELECT MAX(id) FROM merchant_application",Long.class);
        var actor=operator(1);
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",1));
        onboarding.moderate(actor,one,key(),decision(1,"APPROVE",null));
        assertEquals(49010,code(()->onboarding.moderate(actor,two,key(),decision(1,"APPROVE",null))));
        assertEquals(1,count("merchant"));
        // 提高配额后第二家才能开店，且账号形态固定
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",2));
        onboarding.moderate(actor,two,key(),decision(1,"APPROVE",null));
        assertEquals(2,count("merchant"));
        var accounts=jdbc.queryForList("SELECT id,merchant_id,role,account,password_hash,status FROM staff_account ORDER BY id");
        assertEquals(2,accounts.size());
        for(var account:accounts){
            assertEquals("MERCHANT",account.get("role"));
            assertEquals("m"+number(account,"merchant_id"),account.get("account"));
            assertEquals("PENDING_ACTIVATION",account.get("status"));
            assertNull(account.get("password_hash"));
        }
        assertEquals("APPROVED",row("merchant_application",one).get("status"));
        long merchant=number(row("merchant_application",one),"merchant_id");
        assertEquals("演示汽修厂",jdbc.queryForObject("SELECT name FROM merchant WHERE id=?",String.class,merchant));
        assertEquals(2,count("merchant_application_review"));
        assertEquals(TYPES.get("REPAIR").longValue(),
            jdbc.queryForObject("SELECT merchant_type FROM merchant WHERE id=?",Long.class,merchant).longValue());
        assertEquals("440106",jdbc.queryForObject("SELECT region_code FROM merchant WHERE id=?",String.class,merchant));
        assertEquals("[1]",jdbc.queryForObject("SELECT qualification FROM merchant WHERE id=?",String.class,merchant));
        // 已通过的申请不能再提交
        assertEquals(49001,code(()->onboarding.submit(first,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)))));
    }

    @Test void rejectionAndResubmissionKeepImmutableHistory(){
        var applicant=owner(7001);
        onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        long id=applicationId();
        var actor=operator(1);
        assertEquals(49003,code(()->onboarding.moderate(actor,id,key(),decision(2,"APPROVE",null))));
        onboarding.moderate(actor,id,key(),decision(1,"REJECT","QUALIFICATION_INCOMPLETE"));
        assertEquals("REJECTED",row("merchant_application",id).get("status"));
        assertEquals("QUALIFICATION_INCOMPLETE",row("merchant_application",id).get("last_reason_code"));
        assertEquals(49003,code(()->onboarding.moderate(actor,id,key(),decision(1,"APPROVE",null))));
        // 重提推进 revision，旧审核记录不可变
        onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L,2L)));
        assertEquals(2L,number(row("merchant_application",id),"revision"));
        assertNull(row("merchant_application",id).get("last_reason_code"));
        assertEquals(49003,code(()->onboarding.moderate(actor,id,key(),decision(1,"APPROVE",null))));
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",5));
        onboarding.moderate(actor,id,key(),decision(2,"APPROVE",null));
        assertEquals("APPROVED",row("merchant_application",id).get("status"));
        var reviews=jdbc.queryForList("SELECT revision,decision,reason_code FROM merchant_application_review "
            + "WHERE application_id=? ORDER BY revision",id);
        assertEquals(2,reviews.size());
        assertEquals("REJECTED",reviews.get(0).get("decision"));
        assertEquals("QUALIFICATION_INCOMPLETE",reviews.get(0).get("reason_code"));
        assertEquals("APPROVED",reviews.get(1).get("decision"));
        assertEquals(49003,code(()->onboarding.moderate(actor,id,key(),decision(1,"REJECT","DUPLICATE_STORE"))));
    }

    @Test void missingQuotaIsClosedAndQuotaNeverDropsBelowActiveStores(){
        var applicant=owner(7001);
        var actor=operator(1);
        // 未配置配额的 (区域,品类) 视为 0：先驳回而不是自动放行
        onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        long id=applicationId();
        assertEquals(49010,code(()->onboarding.moderate(actor,id,key(),decision(1,"APPROVE",null))));
        onboarding.moderate(actor,id,key(),decision(1,"REJECT","REGION_QUOTA_FULL"));
        assertEquals(0,count("merchant"));
        onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",1));
        onboarding.moderate(actor,id,key(),decision(2,"APPROVE",null));
        assertEquals(1,count("merchant"));
        assertEquals(49011,code(()->onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",0))));
        assertEquals(49011,code(()->onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",0))));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_QUOTA_SET'",Long.class));
        // 同值重复设置不产生第二条审计，其他品类不受已开门店影响
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",1));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_QUOTA_SET'",Long.class));
        var quotas=(List<?>)onboarding.quotas(actor).get("items");
        var item=(Map<?,?>)quotas.get(0);
        assertEquals(1L,((Number)item.get("active_stores")).longValue());
    }

    @Test void failedOpenRollsBackMerchantAccountApplicationAndKey(){
        var applicant=owner(7001);
        var actor=operator(1);
        onboarding.submit(applicant,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        long id=applicationId();
        onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",5));
        jdbc.execute("CREATE TRIGGER reject_merchant_onboarding_account BEFORE INSERT ON staff_account "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private onboarding detail'");
        String attempt=key();
        var error=assertThrows(ResponseStatusException.class,()->onboarding.moderate(actor,id,attempt,decision(1,"APPROVE",null)));
        assertEquals(503,error.getStatusCode().value());
        assertFalse(error.getReason().contains("private"));
        jdbc.execute("DROP TRIGGER reject_merchant_onboarding_account");
        assertEquals(0,count("merchant"));
        assertEquals(0,count("staff_account"));
        assertEquals("PENDING_REVIEW",row("merchant_application",id).get("status"));
        assertNull(row("merchant_application",id).get("merchant_id"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE request_path LIKE '%/moderate'",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_APPLICATION_MODERATE'",Long.class));
        assertEquals(0,count("merchant_application_review"));
        // 回滚彻底：原键可原载荷重试
        onboarding.moderate(actor,id,attempt,decision(1,"APPROVE",null));
        assertEquals(1,count("merchant"));
        assertEquals(1,count("merchant_application_review"));
    }

    @Test void crossIdentityAndPermissionIsolation(){
        var first=owner(7001);var foreign=owner(7002);
        onboarding.submit(first,key(),application("演示汽修厂","REPAIR","440106",List.of(1L)));
        long id=applicationId();
        assertNull(onboarding.mine(foreign).get("application"));
        var mine=(Map<?,?>)onboarding.mine(first).get("application");
        assertEquals(id,((Number)mine.get("application_id")).longValue());
        assertFalse(mine.containsKey("applicant_user_id"));
        // 只有经验审核权限的运营完全看不到入驻
        var actor=operator(2);
        jdbc.update("UPDATE operator_account SET can_onboard=0,can_review=1 WHERE id=2");
        assertEquals(403,status(()->onboarding.pending(actor,1,20)));
        assertEquals(403,status(()->onboarding.detail(actor,id)));
        assertEquals(403,status(()->onboarding.quotas(actor)));
        assertEquals(403,status(()->onboarding.setQuota(actor,key(),quotaBody("440106","REPAIR",1))));
        assertEquals(403,status(()->onboarding.moderate(actor,id,key(),decision(1,"APPROVE",null))));
        assertEquals(403,status(()->onboarding.fileOwner(actor,id,1)));
        // 待审列表只给固定摘要
        jdbc.update("UPDATE operator_account SET can_onboard=1 WHERE id=2");
        var items=(List<?>)onboarding.pending(actor,1,20).get("items");
        var summary=(Map<?,?>)items.get(0);
        assertFalse(summary.containsKey("applicant_user_id"));
        assertFalse(summary.containsKey("contact_phone"));
        assertFalse(summary.containsKey("qualification_file_ids"));
        // 资质文件受控访问：只放行本申请快照内的文件
        assertEquals(404,status(()->onboarding.fileOwner(actor,id,3)));
        assertEquals(404,status(()->onboarding.fileOwner(actor,99,1)));
        var fileOwner=onboarding.fileOwner(actor,id,1);
        assertEquals("user",fileOwner.type());
        assertEquals(7001L,fileOwner.id());
        // 会话撤销后立即失效
        var stale=operator(1);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_id=1");
        assertEquals(401,status(()->onboarding.quotas(stale)));
        assertEquals(401,status(()->onboarding.moderate(stale,id,key(),decision(1,"APPROVE",null))));
    }

    @Test void concurrentApprovalsNeverExceedQuotaAndLeaveNoDeadlock()throws Exception{
        var first=owner(7001);var second=owner(7002);var third=owner(7003);
        onboarding.submit(first,key(),application("演示一店","REPAIR","440106",List.of(1L)));
        onboarding.submit(second,key(),application("演示二店","REPAIR","440106",List.of(3L)));
        onboarding.submit(third,key(),application("演示三店","REPAIR","440106",List.of(5L)));
        var ids=jdbc.queryForList("SELECT id FROM merchant_application ORDER BY id",Long.class);
        var one=operator(1);var two=operator(2);
        onboarding.setQuota(one,key(),quotaBody("440106","REPAIR",2));
        // 配额调整与三次批准交错并发：锁序固定，不得死锁
        Callable<Integer> approveFirst=()->outcome(()->onboarding.moderate(one,ids.get(0),key(),decision(1,"APPROVE",null)));
        Callable<Integer> approveSecond=()->outcome(()->onboarding.moderate(two,ids.get(1),key(),decision(1,"APPROVE",null)));
        Callable<Integer> approveThird=()->outcome(()->onboarding.moderate(one,ids.get(2),key(),decision(1,"APPROVE",null)));
        Callable<Integer> raiseQuota=()->outcome(()->onboarding.setQuota(two,key(),quotaBody("440106","REPAIR",2)));
        Callable<Integer> otherQuota=()->outcome(()->onboarding.setQuota(two,key(),quotaBody("440106","TIRE",2)));
        var results=concurrently(new ArrayList<>(List.of(approveFirst,approveSecond,approveThird,raiseQuota,otherQuota)));
        assertEquals(4L,succeeded(results),"三次批准最多两次成功，两次配额设置必须成功");
        assertEquals(2,count("merchant"),"配额 2 不得被并发批准突破");
        assertEquals(2L,jdbc.queryForObject("SELECT max_active FROM merchant_region_category_quota "
            + "WHERE region_code='440106' AND category='REPAIR'",Long.class));
        assertEquals(2,count("merchant_application_review"));
        assertEquals("",deadlockSection());
    }
}
