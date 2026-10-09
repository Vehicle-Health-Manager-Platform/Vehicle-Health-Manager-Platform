package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.*;
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
class JdbcServiceArchiveTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc; ReservationStore db; ServiceArchiveJobs jobs; ArchiveService archives; VehicleOwner owner, other;
    @BeforeAll static void schema() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); jdbc = new JdbcTemplate(ds);
        try (var c = ds.getConnection(); var paths = Files.list(Path.of("..", "docs", "sql", "migrations"))) {
            for (var p : paths.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) ScriptUtils.executeSqlScript(c, new FileSystemResource(p));
            ScriptUtils.executeSqlScript(c, new FileSystemResource(Path.of("..", "docs", "sql", "migrations", "V017__service_archive_jobs.sql")));
        }
    }
    String key() { return UUID.randomUUID().toString(); }
    VehicleOwner actor(long id) {
        String session = key(); jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))", session, id, key());
        return new VehicleOwner(id, session, Instant.now().plusSeconds(3600));
    }
    @BeforeEach void setup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_service_archive");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_experience_card");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_moderation");
        jdbc.update("DELETE FROM experience_card_moderation");
        jdbc.update("DELETE FROM operator_account");
        for(String table:List.of("model","series","brand"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM experience_card");
        for (String t : List.of("service_archive_job","vehicle_archive_file","vehicle_archive","service_evidence_file","service_report_submission","technician_report","order_review_file","order_review","order_redemption","order_dispute","payment_exception","payment_event","payment","file_object","audit_log","idempotency_record","auth_session","order","vehicle","user","staff_account","merchant")) jdbc.update("DELETE FROM `" + t + "`");
        var manager = new DataSourceTransactionManager(jdbc.getDataSource()); var mapper = new ObjectMapper();
        var integrity = new WriteIntegrityService(jdbc, mapper, manager); db = new ReservationStore(jdbc, mapper, integrity, Clock.systemUTC(), manager);
        jobs = new ServiceArchiveJobs(db, true); archives = new ArchiveService(jdbc, integrity, mapper);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-archive-owner',1),(2,'synthetic-archive-other',1)");
        owner=actor(1); other=actor(2);
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(1,1,12345),(2,2,0)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic-archive-shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-archive-shop','ACTIVE'),(2,1,'TECHNICIAN','synthetic-archive-tech','ACTIVE')");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,service_report_ready_at) VALUES(1,'synthetic-archive-order',1,1,1,10,10,'COMPLETED',UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(1,1,'LOCAL_TEST','synthetic-paid','CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),'synthetic-channel')");
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST',?,1,?,'PAID')",key(),"a".repeat(64));
        jdbc.update("INSERT INTO order_redemption(id,order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES(1,1,1,1,1,1,UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO technician_report(id,order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status,signed_at) VALUES(1,1,2,'[101]','[]','[102]','检查与清洁','未发现需更换故障件','[]',40,1,'2026-10-09 01:00:00')");
        jdbc.update("INSERT INTO service_report_submission(order_id,report_id,no_parts,no_fault_parts,submitted_at) VALUES(1,1,1,1,'2026-10-09 00:50:00')");
        for (int file=101;file<=103;file++) {
            jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'staff_account',2,?,'image/png',100,'CLEAN')",file,"synthetic-archive-"+file);
            jdbc.update("INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES(1,1,?,?)",file,file==101?"PROCESS":file==102?"FINISH":"SIGNATURE");
        }
    }
    long submit() {
        var body=db.mapper.valueToTree(Map.of("order_id",1,"rating",5,"content","施工反馈","photo_file_ids",List.of()));
        new OrderReviews(db).submit(owner,key(),body);
        return jdbc.queryForObject("SELECT id FROM service_archive_job",Long.class);
    }
    int count(String t) { return jdbc.queryForObject("SELECT COUNT(*) FROM `"+t+"`",Integer.class); }
    long archive() { return jdbc.queryForObject("SELECT archive_id FROM service_archive_job",Long.class); }
    @Test void reviewEnqueuesAndConsumerAddsTraceableArchiveWithoutInventingMileage() {
        long id=submit(); assertEquals(0,count("vehicle_archive")); jobs.consume(id); jobs.consume(id);
        assertEquals(1,count("vehicle_archive")); assertEquals(2,count("vehicle_archive_file"));
        var row=db.mapper.valueToTree(archives.list(owner,1,1,20)).path("list").get(0);
        assertEquals(4,row.path("input_type").asInt()); assertTrue(row.path("mileage").isNull());
        assertEquals(40,row.path("work_minutes").asInt()); assertTrue(row.path("test_mode").asBoolean());
        assertEquals(1,row.path("source").path("order_id").asLong()); assertFalse(row.path("source").has("user_id"));
        assertEquals("2026-10-09",row.path("recorded_date").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SERVICE_ARCHIVE_CREATE'",Integer.class));
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class));
    }
    @Test void missingReportDoesNotUndoReviewAndRepairResumesTask() {
        jdbc.update("UPDATE technician_report SET is_deleted=1"); long id=submit();
        assertEquals(1,count("order_review")); assertThrows(ServiceArchiveJobs.InvalidSource.class,()->jobs.consume(id));
        jdbc.update("UPDATE technician_report SET is_deleted=0"); jobs.consume(id); assertEquals(1,count("vehicle_archive"));
    }
    @Test void workerRollbackRecoveryAndAttemptMetadataAreIndependentFromReview() {
        submit(); jdbc.execute("CREATE TRIGGER reject_service_archive BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        jobs.sweep(); assertEquals(0,count("vehicle_archive")); assertEquals(0,count("vehicle_archive_file")); assertEquals(1,count("order_review"));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM service_archive_job",Integer.class));
        jdbc.execute("DROP TRIGGER reject_service_archive"); jdbc.update("UPDATE service_archive_job SET next_attempt_at=UTC_TIMESTAMP()"); jobs.sweep(); assertEquals(1,count("vehicle_archive"));
    }
    @Test void queueInsertFailureRollsBackReviewAndSameKeyCanRetry() {
        jdbc.execute("CREATE TRIGGER reject_service_archive BEFORE INSERT ON service_archive_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        assertThrows(ResponseStatusException.class,this::submit); assertEquals(0,count("order_review")); assertEquals(0,count("audit_log")); assertEquals(0,count("idempotency_record"));
        jdbc.execute("DROP TRIGGER reject_service_archive"); submit(); assertEquals(1,count("service_archive_job"));
    }
    @Test void frozenWorkCannotBeSilentlyReplacedOrUnsafeFilesArchived() {
        long id=submit(); jdbc.update("UPDATE technician_report SET repair_plan='修改方案'"); assertThrows(ServiceArchiveJobs.InvalidSource.class,()->jobs.consume(id));
        jdbc.update("UPDATE technician_report SET repair_plan='检查与清洁'"); jdbc.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=101"); assertThrows(ServiceArchiveJobs.InvalidSource.class,()->jobs.consume(id));
        assertEquals(0,count("vehicle_archive")); jdbc.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=101"); jobs.consume(id);
    }
    @Test void privateImagesRequireOriginalOwnerCurrentVehicleAndSafeFile() {
        jobs.consume(submit()); assertEquals(2,archives.fileOwner(owner,archive(),101).id());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->archives.fileOwner(other,archive(),101)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->archives.fileOwner(owner,archive(),103));
        jdbc.update("UPDATE file_object SET is_deleted=1 WHERE id=101"); assertThrows(ResponseStatusException.class,()->archives.fileOwner(owner,archive(),101));
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=1"); assertEquals(0L,archives.list(other,1,1,20).get("total"));
    }
    @Test void disabledWorkerPreservesPendingTasksAndManualArchivesRemain() {
        submit(); new ServiceArchiveJobs(db,false).sweep(); assertEquals(0,count("vehicle_archive"));
        archives.add(owner,key(),new ArchiveInput(1,1,LocalDate.of(2026,10,8),12345,"手动记录","旧记录",List.of(),3));
        jobs.sweep(); assertEquals(2L,archives.list(owner,1,1,20).get("total"));
    }
    @Test void testServiceDoesNotEnterAiRealHistory() {
        jobs.consume(submit());
        var ai = new com.autocare.platform.ai.AiContextProvider(jdbc,db.mapper);
        assertFalse(ai.forVehicle(owner,1).summary().contains("检查与清洁"));
        // A real archive must explicitly carry a non-test marker; absent markers stay excluded.
        jdbc.update("UPDATE vehicle_archive SET content=JSON_SET(content,'$.test_mode',CAST('false' AS JSON))");
        assertTrue(ai.forVehicle(owner,1).summary().contains("检查与清洁"));
    }
    @Test void concurrentConsumersCreateOneArchiveAndAudit() throws Exception {
        long id=submit(); var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try { var tasks=new ArrayList<Future<?>>(); for(int n=0;n<2;n++) tasks.add(pool.submit(()->{start.await();jobs.consume(id);return null;}));
            start.countDown();for(var task:tasks) task.get(30,TimeUnit.SECONDS);
            assertEquals(1,count("vehicle_archive")); assertEquals(2,count("vehicle_archive_file"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='SERVICE_ARCHIVE_CREATE'",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    long card(boolean real) {
        if (real) {
            // Synthetic trusted payment in an isolated MySQL container; no real charge.
            jdbc.update("UPDATE payment SET channel='WECHAT'"); jdbc.update("UPDATE payment_event SET channel='WECHAT'");
            jdbc.update("UPDATE order_redemption SET test_mode=0");
        }
        new ServiceArchiveJobs(db,true,true).consume(submit());
        return jdbc.queryForObject("SELECT id FROM experience_card",Long.class);
    }
    com.fasterxml.jackson.databind.JsonNode consent() { return ExperienceCardInput.parse("{\"agree\":true,\"consent_version\":\"experience-v1\"}",true); }
    ExperienceCards cards() { return new ExperienceCards(db); }
    @Test void cardSummaryNeverCopiesSensitiveSourceOrPublishes() {
        jdbc.update("UPDATE technician_report SET repair_plan='客户电话13812345678 VIN 私有对象键',fault_analysis='车牌与签名保密'");
        long id=card(false); var c=db.mapper.valueToTree(cards().list(owner,1,1,20)).path("items").get(0);
        assertEquals("DRAFT",c.path("status").asText()); assertTrue(c.path("consent_version").isNull());
        assertEquals(40,c.path("summary").path("work_minutes").asInt()); assertEquals("2026-10",c.path("summary").path("recorded_month").asText());
        assertEquals(Set.of("version","work_minutes","part_kinds","no_parts","recorded_month"), db.mapper.convertValue(c.path("summary"),Map.class).keySet());
        for(String privateField:List.of("13812345678","VIN","私有对象键","车牌","签名","file_ids","notes","user_id","review_id"))assertFalse(c.toString().contains(privateField));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->cards().change(owner,id,key(),consent(),true)).getStatusCode().value());
        assertEquals(0,count("community_content"));
    }
    @Test void consentWithdrawAndNewConsentRequireFreshRevisionAndNoDuplicateAudit() {
        long id=card(true); String first=key(), withdrawal=key();
        var accepted=cards().change(owner,id,first,consent(),true);
        assertEquals("PENDING_REVIEW",accepted.path("data").path("card").path("status").asText());
        assertEquals(accepted,cards().change(owner,id,first,consent(),true));
        cards().change(owner,id,key(),consent(),true);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EXPERIENCE_CARD_CONSENT'",Integer.class));
        var withdrawn=cards().change(owner,id,withdrawal,db.mapper.createObjectNode(),false);
        assertTrue(withdrawn.path("data").path("card").path("consent_version").isNull());
        assertTrue(withdrawn.path("data").path("card").path("consented_at").isNull());
        assertEquals(2,withdrawn.path("data").path("card").path("revision").asInt());
        assertEquals(45002,((FulfillmentConflict)assertThrows(ResponseStatusException.class,()->cards().change(owner,id,first,consent(),true))).code);
        jdbc.update("UPDATE idempotency_record SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE idempotency_key=?",first);
        assertEquals(45002,((FulfillmentConflict)assertThrows(ResponseStatusException.class,()->cards().change(owner,id,first,consent(),true))).code);
        cards().change(owner,id,key(),consent(),true);
        assertThrows(ResponseStatusException.class,()->cards().change(owner,id,withdrawal,db.mapper.createObjectNode(),false));
        assertEquals(3,jdbc.queryForObject("SELECT revision FROM experience_card",Integer.class));
        assertEquals(0,count("community_content"));
    }
    @Test void ownershipTransferAndRevokedSessionsBlockCardReadsWritesAndCachedReplay() {
        long id=card(true); String k=key(); cards().change(owner,id,k,consent(),true);
        assertThrows(ResponseStatusException.class,()->cards().change(other,id,key(),consent(),true));
        assertThrows(ResponseStatusException.class,()->cards().list(other,1,1,20));
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=1");
        assertEquals(0L,cards().list(other,1,1,20).get("total"));
        assertThrows(ResponseStatusException.class,()->cards().change(owner,id,k,consent(),true));
        jdbc.update("UPDATE vehicle SET user_id=1 WHERE id=1"); jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->cards().list(owner,1,1,20)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->cards().change(owner,id,k,consent(),true));
    }
    @Test void sourceChangeBlocksAuthorizationButWithdrawalStillSucceeds() {
        long id=card(true); cards().change(owner,id,key(),consent(),true);
        jdbc.update("UPDATE technician_report SET is_deleted=1");
        assertThrows(ResponseStatusException.class,()->cards().change(owner,id,key(),consent(),true));
        var result=cards().change(owner,id,key(),db.mapper.createObjectNode(),false);
        assertEquals("WITHDRAWN",result.path("data").path("card").path("status").asText());
    }
    @Test void cardFailureRollsBackArchiveAndRecoveryCreatesOnlyOne() {
        long job=submit(); var worker=new ServiceArchiveJobs(db,true,true);
        jdbc.execute("CREATE TRIGGER reject_experience_card BEFORE INSERT ON experience_card FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        worker.sweep(); assertEquals(0,count("experience_card")); assertEquals(0,count("vehicle_archive")); assertEquals(0,count("vehicle_archive_file"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM service_archive_job",String.class));
        assertEquals(1,count("order_review"));
        jdbc.execute("DROP TRIGGER reject_experience_card"); worker.consume(job); worker.consume(job);
        assertEquals(1,count("experience_card")); assertEquals(1,count("vehicle_archive"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EXPERIENCE_CARD_CREATE'",Integer.class));
    }
    @Test void enablingCardsDoesNotBackfillCompletedArchives() {
        long job=submit(); jobs.consume(job); new ServiceArchiveJobs(db,true,true).consume(job);
        assertEquals(0,count("experience_card")); assertEquals(1,count("vehicle_archive"));
    }
    @Test void cardAndConsentAuditFailureRollbackCanRetrySameKey() {
        long id=card(true); String k=key();
        jdbc.execute("CREATE TRIGGER reject_experience_card BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->cards().change(owner,id,k,consent(),true)).getStatusCode().value());
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM experience_card",String.class));
        jdbc.execute("DROP TRIGGER reject_experience_card"); cards().change(owner,id,k,consent(),true);
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM experience_card",Integer.class));
    }
    @Test void concurrentCardWorkersAndOwnerActionsCreateOneOutputAndTransition() throws Exception {
        long job=submit(); var worker=new ServiceArchiveJobs(db,true,true); var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=List.of(pool.submit(()->worker.consume(job)),pool.submit(()->worker.consume(job)));
            for(var f:futures)f.get(30,TimeUnit.SECONDS); assertEquals(1,count("experience_card"));
            long id=jdbc.queryForObject("SELECT id FROM experience_card",Long.class);
            futures=List.of(pool.submit(()->cards().change(owner,id,key(),db.mapper.createObjectNode(),false)),pool.submit(()->cards().change(owner,id,key(),db.mapper.createObjectNode(),false)));
            for(var f:futures)f.get(30,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT revision FROM experience_card",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EXPERIENCE_CARD_WITHDRAW'",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidConsentAndWithdrawalCannotCreateAuditOrIdempotencyRecords() {
        long id=card(true); int audits=count("audit_log"),keys=count("idempotency_record");
        for(String bad:List.of("{}","{\"agree\":false,\"consent_version\":\"experience-v1\"}","{\"agree\":true,\"consent_version\":\"old\"}","{\"agree\":true,\"consent_version\":\"experience-v1\",\"extra\":1}","{\"agree\":true,\"agree\":false,\"consent_version\":\"experience-v1\"}","{\"agree\":true,\"consent_version\":\"experience-v1\"} {}"))assertThrows(ResponseStatusException.class,()->ExperienceCardInput.parse(bad,true));
        assertThrows(ResponseStatusException.class,()->cards().change(owner,id,key(),db.mapper.valueToTree(Map.of("agree",true)),false));
        assertEquals(audits,count("audit_log")); assertEquals(keys,count("idempotency_record"));
    }
    com.autocare.platform.gateway.identity.OperatorActor operator() {
        jdbc.update("INSERT IGNORE INTO operator_account(id,account,password_hash,phone,can_review) VALUES(1,'synthetic-reviewer','synthetic-unused-password-hash','13800000000',1)");
        String session=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",session,key());
        return new com.autocare.platform.gateway.identity.OperatorActor(1,session,Instant.now().plusSeconds(900));
    }
    ExperienceModeration moderation(boolean enabled) {
        var identity=new com.autocare.platform.gateway.identity.OperatorIdentity(jdbc,
            new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>("synthetic-secret-32-characters-long".getBytes())),new DataSourceTransactionManager(jdbc.getDataSource()));
        return new ExperienceModeration(db,identity,enabled);
    }
    void models() {
        jdbc.update("INSERT INTO brand(id,name) VALUES(1,'合成测试品牌')");jdbc.update("INSERT INTO series(id,brand_id,name) VALUES(1,1,'合成测试车系')");
        jdbc.update("INSERT INTO model(id,series_id,year) VALUES(1,1,'2026'),(2,1,'2025')");jdbc.update("UPDATE vehicle SET model_id=1");
    }
    com.fasterxml.jackson.databind.JsonNode decision(int revision,boolean approve) {
        return ExperienceModerationInput.parse("{\"revision\":"+revision+",\"decision\":\""+(approve?"APPROVE":"REJECT")+"\",\"reason_code\":"+(approve?"null":"\"NOT_SUITABLE\"")+"}");
    }
    long awaiting() { long id=card(true);cards().change(owner,id,key(),consent(),true);return id; }
    @Test void approvedExperienceUsesPublicDtoAndWithdrawalImmediatelyHides() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);
        var pending=db.mapper.valueToTree(service.pending(ops,1,20)).path("items").get(0);
        assertFalse(pending.has("user_id"));assertFalse(pending.has("order_id"));assertFalse(pending.has("archive_id"));
        assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());
        var accepted=service.moderate(ops,id,key(),decision(1,true));assertEquals("PUBLISHED",accepted.path("data").path("status").asText());
        var feed=db.mapper.valueToTree(service.experiences(other,2,null)).path("items");assertEquals(1,feed.size());
        assertEquals(Set.of("experience_id","title","summary","model_id","published_at"),db.mapper.convertValue(feed.get(0),Map.class).keySet());
        assertDoesNotThrow(()->UUID.fromString(feed.get(0).path("experience_id").textValue()));
        cards().change(owner,id,key(),db.mapper.createObjectNode(),false);assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());
        assertEquals(1,count("vehicle_archive"));assertEquals(1,count("order_review"));
    }
    @Test void missingModelCannotApproveButCanRejectAndOwnerReauthorize() {
        long id=awaiting();var ops=operator();var service=moderation(true);
        assertEquals(45004,((FulfillmentConflict)assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,key(),decision(1,true)))).code);
        service.moderate(ops,id,key(),decision(1,false));
        var row=db.mapper.valueToTree(cards().list(owner,1,1,20)).path("items").get(0);assertEquals("NOT_SUITABLE",row.path("review_reason").textValue());
        cards().change(owner,id,key(),consent(),true);assertEquals(3,jdbc.queryForObject("SELECT revision FROM experience_card",Integer.class));
        models();service.moderate(ops,id,key(),decision(3,true));assertEquals(2,count("experience_card_moderation"));
    }
    @Test void staleModerationKeysAndVersionsCannotOverrideWithdrawnConsent() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);String k=key();
        var response=service.moderate(ops,id,k,decision(1,true));assertEquals(response,service.moderate(ops,id,k,decision(1,true)));
        service.moderate(ops,id,key(),decision(1,true));assertEquals(1,count("experience_card_moderation"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EXPERIENCE_CARD_MODERATE'",Integer.class));
        cards().change(owner,id,key(),db.mapper.createObjectNode(),false);
        assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,k,decision(1,true)));
        cards().change(owner,id,key(),consent(),true);assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,k,decision(1,true)));
        assertEquals("PENDING_REVIEW",jdbc.queryForObject("SELECT status FROM experience_card",String.class));
    }
    @Test void permissionRemovalRevokedSessionAndDisabledFlagBlockOperations() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);
        assertThrows(ResponseStatusException.class,()->moderation(false).pending(ops,1,20));assertEquals(0,((List<?>)moderation(false).experiences(other,2,null).get("items")).size());
        jdbc.update("UPDATE operator_account SET can_review=0");assertEquals(403,assertThrows(ResponseStatusException.class,()->service.pending(ops,1,20)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,key(),decision(1,true)));
        jdbc.update("UPDATE operator_account SET can_review=1");jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",ops.session());assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,key(),decision(1,true)));
    }
    @Test void testDraftsAndWrongModelNeverAppearAsRealExperience() {
        models();long id=card(false);var ops=operator();var service=moderation(true);
        assertEquals(0L,service.pending(ops,1,20).get("total"));assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,key(),decision(1,true)));
        assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());
    }
    @Test void unsafeOrChangedSourcesCannotPublishOrContinueBeingShown() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);
        jdbc.update("UPDATE technician_report SET repair_plan='变更来源'");assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,key(),decision(1,true)));
        jdbc.update("UPDATE technician_report SET repair_plan='检查与清洁'");service.moderate(ops,id,key(),decision(1,true));
        jdbc.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=101");assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());
        jdbc.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=101");jdbc.update("UPDATE experience_card SET summary=JSON_SET(summary,'$.work_minutes',999)");assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());
    }
    @Test void deletedMovedVehiclesInactiveOwnersAndModelChangeHidePublicCards() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);service.moderate(ops,id,key(),decision(1,true));
        for(String update:List.of("UPDATE vehicle SET is_deleted=1 WHERE id=1","UPDATE vehicle SET user_id=2 WHERE id=1","UPDATE vehicle SET model_id=2 WHERE id=1","UPDATE user SET status=2 WHERE id=1","UPDATE model SET is_deleted=1 WHERE id=1")){
            jdbc.update(update);assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());jdbc.update("UPDATE vehicle SET is_deleted=0,user_id=1,model_id=1 WHERE id=1");jdbc.update("UPDATE user SET status=1 WHERE id=1");jdbc.update("UPDATE model SET is_deleted=0 WHERE id=1");
        }
    }
    @Test void moderationAuditFailureRollsBackAndSameKeyRetries() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);String k=key();
        jdbc.execute("CREATE TRIGGER reject_moderation BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.moderate(ops,id,k,decision(1,true))).getStatusCode().value());assertEquals(0,count("experience_card_moderation"));
        assertEquals("PENDING_REVIEW",jdbc.queryForObject("SELECT status FROM experience_card",String.class));jdbc.execute("DROP TRIGGER reject_moderation");service.moderate(ops,id,k,decision(1,true));assertEquals(1,count("experience_card_moderation"));
    }
    @Test void concurrentApprovalAndWithdrawalNeverLeaveVisibleRevokedCard() throws Exception {
        models();long id=awaiting();var ops=operator();var service=moderation(true);var pool=Executors.newFixedThreadPool(2);
        try{var approve=pool.submit(()->{try{service.moderate(ops,id,key(),decision(1,true));}catch(FulfillmentConflict e){}});var revoke=pool.submit(()->cards().change(owner,id,key(),db.mapper.createObjectNode(),false));approve.get(30,TimeUnit.SECONDS);revoke.get(30,TimeUnit.SECONDS);
            assertEquals("WITHDRAWN",jdbc.queryForObject("SELECT status FROM experience_card",String.class));assertEquals(0,((List<?>)service.experiences(other,2,null).get("items")).size());assertTrue(count("experience_card_moderation")<=1);
        }finally{pool.shutdownNow();}
    }
    @Test void cursorPaginationScansBoundedCandidatesAndNeverReturnsPrivateFields() {
        models();long id=awaiting();var ops=operator();var service=moderation(true);service.moderate(ops,id,key(),decision(1,true));
        long marker=jdbc.queryForObject("SELECT id FROM experience_card_moderation",Long.class);
        assertEquals(0,((List<?>)service.experiences(other,2,marker).get("items")).size());assertNull(service.experiences(other,2,null).get("next_cursor"));
        assertThrows(ResponseStatusException.class,()->service.experiences(owner,2,null));
    }
}
