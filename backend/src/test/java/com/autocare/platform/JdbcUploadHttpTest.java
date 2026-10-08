package com.autocare.platform;

import com.autocare.platform.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcUploadHttpTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    JdbcUploadRequests requests;
    UploadOwner owner;
    final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    int puts;
    boolean failPut,failDelete;
    final PrivateObjectStore store=new PrivateObjectStore() {
        public boolean isPrivate(){return true;}
        public void put(String key,String type,byte[] bytes){objects.put(key,bytes.clone());puts++;if(failPut)throw new IllegalStateException("private");}
        public void delete(String key){if(failDelete)throw new IllegalStateException("private");objects.remove(key);}
    };
    @BeforeAll static void schema() throws Exception {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(source);
        try(var connection=source.getConnection()) {
            for(String file:List.of("V001__baseline.sql","V002__staff_wechat_identity.sql","V003__auth_lifecycle.sql","V004__upload_http.sql","V004__upload_http.sql","V010__pickup_inspection.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));
        }
    }
    @BeforeEach void prepare() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_upload_audit");
        for(String table:List.of("upload_cleanup_task","upload_request","file_object","audit_log","auth_session","auth_rate_limit","user")) jdbc.update("DELETE FROM "+table);
        owner=new UploadOwner(1001,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO user(id,status) VALUES (1001,1),(1002,1)");
        session(owner);
        requests=new JdbcUploadRequests(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new ObjectMapper());
        objects.clear();puts=0;failPut=false;failDelete=false;
    }
    void session(UploadOwner subject) {
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES (?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY))",subject.session(),subject.id(),UUID.randomUUID().toString());
    }
    UploadHttpService service(){return new UploadHttpService(requests,bytes->VirusScanner.Result.CLEAN,store);}
    com.fasterxml.jackson.databind.JsonNode upload(String key){return service().upload(owner,key,"private-name.png",new ByteArrayInputStream(PrivateUploadTest.PNG));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void successReplayIsAtomicAndDoesNotRetainPrivateFields() {
        String key=UUID.randomUUID().toString();var first=upload(key);assertEquals(first,upload(key));assertEquals(1,puts);
        assertEquals(1,count("file_object"));assertEquals(1,count("audit_log"));
        String response=first.toString();assertFalse(response.contains("uploads/"));assertFalse(response.contains("private-name"));
        var error=assertThrows(UploadHttpException.class,()->service().upload(owner,key,"a.jpg",new ByteArrayInputStream(PrivateUploadTest.JPEG)));
        assertEquals(409,error.status());
        jdbc.update("UPDATE file_object SET is_deleted=1");assertEquals(404,assertThrows(UploadHttpException.class,()->upload(key)).status());
    }
    @Test void merchantAndOwnerSameIdAndKeyRemainIsolated() {
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1001,2,'测试店','合成地址',1) ON DUPLICATE KEY UPDATE status=1");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1001,1001,'MERCHANT','upload-merchant','ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE'");
        var staff=new UploadOwner(1001,UUID.randomUUID().toString(),Instant.now().plusSeconds(600),"staff_account",1001);
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',1001,'MERCHANT','merchant-account',1001,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",staff.session(),UUID.randomUUID().toString());
        String key=UUID.randomUUID().toString();var a=upload(key);var b=service().upload(staff,key,"merchant.png",new ByteArrayInputStream(PrivateUploadTest.PNG));
        assertNotEquals(a.path("data").path("file_id"),b.path("data").path("file_id"));assertEquals(b,service().upload(staff,key,"merchant.png",new ByteArrayInputStream(PrivateUploadTest.PNG)));assertEquals(2,count("upload_request"));assertEquals(2,count("audit_log"));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",staff.session());assertEquals(401,assertThrows(UploadHttpException.class,()->service().upload(staff,key,"merchant.png",new ByteArrayInputStream(PrivateUploadTest.PNG))).status());
    }
    @Test void technicianUploadRevalidatesCurrentBindingEmployeeSessionAndShop(){
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1002,2,'synthetic-tech-upload','test',1) ON DUPLICATE KEY UPDATE status=1");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1002,1002,'TECHNICIAN','upload-tech','ACTIVE') ON DUPLICATE KEY UPDATE role='TECHNICIAN',status='ACTIVE'");
        jdbc.update("DELETE FROM staff_wechat_identity WHERE staff_account_id=1002");jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1002,'test-app','synthetic-upload-tech',1002)");
        String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,'staff_account',1002,'TECHNICIAN','test-app',1002,1002,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,UUID.randomUUID().toString());
        var tech=new com.autocare.platform.order.TechnicianActor(1002,1002,1002,"test-app",session,Instant.now().plusSeconds(600));var subject=new UploadOwner(1002,session,tech.expires(),"staff_account",1002,tech);String key=UUID.randomUUID().toString();
        var first=service().upload(subject,key,"tech.png",new ByteArrayInputStream(PrivateUploadTest.PNG));assertEquals(first,service().upload(subject,key,"tech.png",new ByteArrayInputStream(PrivateUploadTest.PNG)));assertEquals(1,puts);assertEquals(1,count("file_object"));assertEquals("/api/tech/files/upload",jdbc.queryForObject("SELECT request_path FROM upload_request",String.class));
        var merchantWrapper=new UploadOwner(1002,session,tech.expires(),"staff_account",1002);assertEquals(401,assertThrows(UploadHttpException.class,()->requests.checkOwner(merchantWrapper)).status());
        jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED',unbound_at=UTC_TIMESTAMP() WHERE id=1002");assertEquals(401,assertThrows(UploadHttpException.class,()->service().upload(subject,key,"tech.png",new ByteArrayInputStream(PrivateUploadTest.PNG))).status());jdbc.update("UPDATE staff_wechat_identity SET status='ACTIVE',unbound_at=NULL WHERE id=1002");
        jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=1002");assertEquals(401,assertThrows(UploadHttpException.class,()->requests.checkOwner(subject)).status());jdbc.update("UPDATE staff_account SET status='ACTIVE' WHERE id=1002");jdbc.update("UPDATE merchant SET status=0 WHERE id=1002");assertEquals(401,assertThrows(UploadHttpException.class,()->requests.checkOwner(subject)).status());jdbc.update("UPDATE merchant SET status=1 WHERE id=1002");jdbc.update("UPDATE auth_session SET binding_id=9999 WHERE id=?",session);assertEquals(401,assertThrows(UploadHttpException.class,()->requests.checkOwner(subject)).status());
    }
    @Test void concurrentReservationHasExactlyOneWinnerAndActorIsolation() throws Exception {
        String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
        try {
            var work=new ArrayList<Future<Boolean>>();
            for(int i=0;i<6;i++) work.add(pool.submit(()->{start.await();try{return requests.reserve(owner,key,"a".repeat(64)).replay()==null;}
                catch(UploadHttpException e){assertEquals(409,e.status());return false;}}));
            start.countDown();int winners=0;for(var result:work)if(result.get(20,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,count("upload_request"));
            var other=new UploadOwner(1002,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));session(other);
            requests.reserve(other,key,"a".repeat(64));assertEquals(2,count("upload_request"));
        } finally {pool.shutdownNow();}
    }
    @Test void auditFailureRollsBackMetadataAndQueuesPersistentCleanup() {
        jdbc.execute("CREATE TRIGGER reject_upload_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test-only'");
        String key=UUID.randomUUID().toString();assertEquals(503,assertThrows(UploadHttpException.class,()->upload(key)).status());
        assertEquals(0,count("file_object"));assertEquals(0,count("audit_log"));assertEquals(1,count("upload_cleanup_task"));assertEquals(1,objects.size());
        service().reconcile();assertTrue(objects.isEmpty());assertEquals("REJECTED",jdbc.queryForObject("SELECT state FROM upload_request",String.class));
    }
    @Test void uncertainPutAndFailedDeleteRecoverAndRecheckLateObjects() {
        failPut=true;failDelete=true;String key=UUID.randomUUID().toString();assertThrows(UploadHttpException.class,()->upload(key));
        service().reconcile();assertEquals(1,objects.size());assertEquals(1,count("upload_cleanup_task"));
        failDelete=false;jdbc.update("UPDATE upload_cleanup_task SET next_run=UTC_TIMESTAMP()");service().reconcile();assertTrue(objects.isEmpty());
        String objectKey=jdbc.queryForObject("SELECT object_key FROM upload_cleanup_task",String.class);
        objects.put(objectKey,PrivateUploadTest.PNG);jdbc.update("UPDATE upload_cleanup_task SET next_run=UTC_TIMESTAMP()");
        service().reconcile();assertTrue(objects.isEmpty());assertEquals(1,count("upload_cleanup_task"));
    }
    @Test void expiredAttemptCannotCommitAndCleanupNeverDeletesSucceededFile() {
        var reservation=requests.reserve(owner,UUID.randomUUID().toString(),"a".repeat(64));
        jdbc.update("UPDATE upload_request SET deadline=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        requests.expireProcessing();assertThrows(UploadHttpException.class,()->requests.succeed(owner,reservation,new FileValidator.Validated("image/png",PrivateUploadTest.PNG)));
        service().reconcile();assertEquals(0,count("file_object"));
        var response=upload(UUID.randomUUID().toString());long id=response.path("data").path("file_id").asLong();
        jdbc.update("INSERT INTO upload_cleanup_task(request_id,object_key) SELECT id,object_key FROM upload_request WHERE state='SUCCEEDED'");
        service().reconcile();assertEquals(1,objects.size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE id=?",Integer.class,id));
    }
    @Test void tokenRevocationAndUserDisableAreRecheckedBeforeCommitAndReplay() {
        var reservation=requests.reserve(owner,UUID.randomUUID().toString(),"a".repeat(64));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP()");
        assertEquals(401,assertThrows(UploadHttpException.class,()->requests.succeed(owner,reservation,new FileValidator.Validated("image/png",PrivateUploadTest.PNG))).status());
        assertEquals(0,count("file_object"));jdbc.update("UPDATE auth_session SET revoked_at=NULL");
        String key=UUID.randomUUID().toString();upload(key);jdbc.update("UPDATE user SET status=2 WHERE id=1001");
        assertEquals(401,assertThrows(UploadHttpException.class,()->upload(key)).status());
    }
    @Test void infectionUnknownScanAndMissingAdaptersNeverWriteObjects() {
        var infected=new UploadHttpService(requests,bytes->VirusScanner.Result.INFECTED,store);
        String key=UUID.randomUUID().toString();
        assertEquals(422,assertThrows(UploadHttpException.class,()->infected.upload(owner,key,"a.png",new ByteArrayInputStream(PrivateUploadTest.PNG))).status());
        assertEquals(422,assertThrows(UploadHttpException.class,()->upload(key)).status());
        assertEquals(0,puts);assertEquals(0,count("file_object"));
        var absent=new UploadHttpService(requests,null,store);assertThrows(UploadHttpException.class,()->absent.admission(owner,true));
    }
    @Test void expiredSuccessStartsNewAttemptWithoutDeletingEarlierFile() {
        String key=UUID.randomUUID().toString();var first=upload(key);
        jdbc.update("UPDATE upload_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        var second=upload(key);assertNotEquals(first.path("data").path("file_id"),second.path("data").path("file_id"));
        assertEquals(2,count("file_object"));assertEquals(2,objects.size());assertEquals(2,count("audit_log"));
    }
    @Test void sharedRateCountersIncludeRejectedRequestsAndAccessHasSeparateBudget() throws Exception {
        long remaining=60-Instant.now().getEpochSecond()%60;
        if(remaining<5) Thread.sleep((remaining+1)*1000);
        for(int i=0;i<10;i++) requests.admission(owner,true);
        assertEquals(429,assertThrows(UploadHttpException.class,()->requests.admission(owner,true)).status());
        assertEquals(429,assertThrows(UploadHttpException.class,()->requests.admission(owner,true)).status());
        requests.admission(owner,false);
        assertEquals(12,jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='file_upload'",Integer.class));
    }
    @Test void resolvedSuccessfulCommitIsNeverTurnedIntoCleanup() {
        String key=UUID.randomUUID().toString();var response=upload(key);
        var row=jdbc.queryForMap("SELECT id,attempt_id,object_key FROM upload_request");
        var reservation=new JdbcUploadRequests.Reservation(((Number)row.get("id")).longValue(),(String)row.get("attempt_id"),(String)row.get("object_key"),null);
        assertEquals(response,requests.failed(reservation,503,true));assertEquals(0,count("upload_cleanup_task"));assertEquals(1,objects.size());
    }
    @Test void actualCommitAcknowledgementFailureRecoversWithoutDeletingObject() {
        var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager=new DataSourceTransactionManager(jdbc.getDataSource()) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                if(count("file_object")>0 && once.compareAndSet(true,false))
                    throw new org.springframework.transaction.TransactionSystemException("injected lost acknowledgement after commit");
            }
        };
        requests=new JdbcUploadRequests(jdbc,manager,new ObjectMapper());
        String key=UUID.randomUUID().toString();var response=upload(key);
        assertEquals(response,upload(key));assertEquals(1,objects.size());assertEquals(1,count("audit_log"));assertEquals(0,count("upload_cleanup_task"));
    }
    @Test void unknownScanAndStoreFailureBeforeOrAfterPutAreFailClosed() {
        var unknown=new UploadHttpService(requests,bytes->VirusScanner.Result.UNAVAILABLE,store);
        assertEquals(503,assertThrows(UploadHttpException.class,()->unknown.upload(owner,UUID.randomUUID().toString(),"a.png",new ByteArrayInputStream(PrivateUploadTest.PNG))).status());
        assertEquals(0,puts);assertEquals(0,count("file_object"));
        failPut=true;assertThrows(UploadHttpException.class,()->upload(UUID.randomUUID().toString()));
        assertEquals(1,count("upload_cleanup_task"));service().reconcile();assertTrue(objects.isEmpty());
    }

    @Test void sameKeyRetriesOnlyAfterFailureIsConfirmedAndCleanupCannotDeleteRetry() {
        String key=UUID.randomUUID().toString();failPut=true;assertThrows(UploadHttpException.class,()->upload(key));
        failPut=false;assertThrows(UploadHttpException.class,()->upload(key));assertEquals(1,puts);
        service().reconcile();var result=upload(key);assertEquals(2,puts);assertEquals(1,count("file_object"));
        jdbc.update("UPDATE upload_cleanup_task SET next_run=UTC_TIMESTAMP()");service().reconcile();
        assertEquals(1,objects.size());assertEquals(result,upload(key));assertEquals(1,count("audit_log"));
    }
    @Test void cleanupTasksAreClaimedOnlyOnceAcrossConcurrentWorkers() throws Exception {
        failPut=true;assertThrows(UploadHttpException.class,()->upload(UUID.randomUUID().toString()));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return requests.claimCleanup();});
            var second=pool.submit(()->{start.await();return requests.claimCleanup();});start.countDown();
            var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);
            assertTrue((a==null)!=(b==null));assertEquals(1,count("upload_cleanup_task"));
        } finally {pool.shutdownNow();}
    }

    @Test void outerTransactionIsRejectedBeforeExternalEffectsOrReservation() {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.execute(status->{assertThrows(UploadHttpException.class,()->upload(UUID.randomUUID().toString()));return null;});
        assertEquals(0,puts);assertEquals(0,count("upload_request"));
    }

}
