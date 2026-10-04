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
            for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V004__upload_http.sql","V004__upload_http.sql"))
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
    @Test void sharedRateCountersIncludeRejectedRequestsAndAccessHasSeparateBudget() {
        // Keep the test within one fixed UTC minute; a boundary would reset the budget by design.
        long remaining=60-Instant.now().getEpochSecond()%60;
        if(remaining<3) {jdbc.update("INSERT INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES ('file_upload',?,FROM_UNIXTIME(?),10)",
            com.autocare.platform.gateway.identity.AuthTokens.sha256("1001"),Math.floorDiv(Instant.now().getEpochSecond(),60)*60);}
        else for(int i=0;i<10;i++) requests.admission(owner,true);
        assertEquals(429,assertThrows(UploadHttpException.class,()->requests.admission(owner,true)).status());
        requests.admission(owner,false);
    }
    @Test void resolvedSuccessfulCommitIsNeverTurnedIntoCleanup() {
        String key=UUID.randomUUID().toString();var response=upload(key);
        var row=jdbc.queryForMap("SELECT id,attempt_id,object_key FROM upload_request");
        var reservation=new JdbcUploadRequests.Reservation(((Number)row.get("id")).longValue(),(String)row.get("attempt_id"),(String)row.get("object_key"),null);
        assertEquals(response,requests.failed(reservation,503,true));assertEquals(0,count("upload_cleanup_task"));assertEquals(1,objects.size());
    }
}
