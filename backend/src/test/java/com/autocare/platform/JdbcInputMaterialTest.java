package com.autocare.platform;

import com.autocare.platform.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcInputMaterialTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    JdbcUploadRequests requests;UploadOwner owner;
    final InputMaterialScope scope=new InputMaterialScope("AI",11);
    final Map<String,byte[]> objects=new HashMap<>();
    int puts;boolean failPut,failDelete;Runnable beforePut=()->{},beforeSign=()->{};
    VirusScanner scanner=bytes->VirusScanner.Result.CLEAN;
    final SignedObjectStore store=new SignedObjectStore(){
        public boolean isPrivate(){return true;}
        public void put(String key,String type,byte[] bytes){beforePut.run();objects.put(key,bytes);puts++;if(failPut)throw new IllegalStateException();}
        public void delete(String key){if(failDelete)throw new IllegalStateException();objects.remove(key);}
        public StoredObject stat(String key){return new StoredObject("audio/mpeg",objects.get(key).length);}
        public String signGet(String key,int seconds){fail("must use attachment signing");return null;}
        public String signAttachment(String key,int seconds,String type){assertEquals(60,seconds);beforeSign.run();return "https://synthetic.invalid/attachment";}
    };
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()){
            for(String name:List.of("V001__baseline.sql","V002__staff_wechat_identity.sql","V003__auth_lifecycle.sql","V004__upload_http.sql","V010__pickup_inspection.sql","V062__input_material.sql","V062__input_material.sql"))
                ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations",name)));
        }
    }
    @BeforeEach void prepare(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_material_audit");
        for(String table:List.of("upload_cleanup_task","upload_request","input_material","file_object","audit_log","vehicle","auth_session","auth_rate_limit","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,status) VALUES(1,1),(2,1)");jdbc.update("INSERT INTO vehicle(id,user_id) VALUES(11,1),(12,1),(21,2)");
        owner=new UploadOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session(),UUID.randomUUID().toString());
        requests=new JdbcUploadRequests(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new ObjectMapper());
        objects.clear();puts=0;failPut=false;failDelete=false;beforePut=()->{};beforeSign=()->{};scanner=bytes->VirusScanner.Result.CLEAN;
    }
    UploadHttpService uploads(){return new UploadHttpService(requests,scanner,store);}
    InputMaterialService service(){return new InputMaterialService(uploads(),requests,jdbc,store,true);}
    com.fasterxml.jackson.databind.JsonNode upload(String key,InputMaterialScope target){return service().upload(owner,target,key,"material.mp3",new ByteArrayInputStream(InputMaterialValidatorTest.audio(30)));}
    String key(){return UUID.randomUUID().toString();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    int error(org.junit.jupiter.api.function.Executable action){return assertThrows(UploadHttpException.class,action).status();}
    @Test void successReplayIsSeparateFromImagesAndPrivateFields(){
        String key=key();var result=upload(key,scope);assertEquals(result,upload(key,scope));assertEquals(1,puts);assertEquals(1,count("input_material"));assertEquals(0,count("file_object"));assertEquals(1,count("audit_log"));
        assertFalse(result.toString().contains("uploads/"));assertFalse(result.toString().contains("material.mp3"));assertFalse(result.path("data").has("file_id"));
        assertEquals(409,error(()->service().upload(owner,scope,key,"material.mp3",new ByteArrayInputStream(InputMaterialValidatorTest.audio(31)))));
    }
    @Test void purposesVehiclesAndOldImageRequestsHaveIndependentNamespaces(){
        String key=key();upload(key,scope);upload(key,new InputMaterialScope("ARCHIVE",11));upload(key,new InputMaterialScope("AI",12));
        uploads().upload(owner,key,"image.png",new ByteArrayInputStream(PrivateUploadTest.PNG));assertEquals(4,count("upload_request"));assertEquals(3,count("input_material"));assertEquals(1,count("file_object"));
    }
    @Test void pdfPersistsServerDerivedPageCountWithoutImageMetadata()throws Exception{
        var result=service().upload(owner,new InputMaterialScope("ARCHIVE",11),key(),"material.pdf",new ByteArrayInputStream(InputMaterialValidatorTest.pdf(2)));
        assertEquals(2,result.path("data").path("pages").asInt());assertEquals(0,result.path("data").path("duration_ms").asInt());
        assertEquals("application/pdf",jdbc.queryForObject("SELECT content_type FROM input_material",String.class));assertEquals(0,count("file_object"));
    }
    @Test void wrongVehicleAndDeletedOrTransferredVehicleRejectedBeforeScanning(){
        scanner=b->{fail("must reject before scan");return VirusScanner.Result.CLEAN;};
        assertEquals(404,error(()->upload(key(),new InputMaterialScope("AI",21))));
        jdbc.update("UPDATE vehicle SET is_deleted=1 WHERE id=11");assertEquals(404,error(()->upload(key(),scope)));assertEquals(0,puts);
    }
    @Test void replaysAndSignedAccessRevalidateCurrentSessionPurposeAndVehicle(){
        String key=key();long id=upload(key,scope).path("data").path("material_id").asLong();
        assertTrue(service().access(owner,scope,id).url().contains("attachment"));
        assertEquals(404,error(()->service().access(owner,new InputMaterialScope("ARCHIVE",11),id)));
        assertEquals(404,error(()->service().access(owner,new InputMaterialScope("AI",12),id)));
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=11");assertEquals(404,error(()->upload(key,scope)));assertEquals(404,error(()->service().access(owner,scope,id)));
        jdbc.update("UPDATE vehicle SET user_id=1 WHERE id=11");jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,error(()->upload(key,scope)));assertEquals(401,error(()->service().access(owner,scope,id)));
    }
    @Test void softDeleteInvalidatesReplayAndAccess(){
        String key=key();long id=upload(key,scope).path("data").path("material_id").asLong();jdbc.update("UPDATE input_material SET is_deleted=1");
        assertEquals(404,error(()->upload(key,scope)));assertEquals(404,error(()->service().access(owner,scope,id)));
    }
    @Test void scanFailureNeverStoresAndUnavailableCanRetrySameKey(){
        String key=key();scanner=b->VirusScanner.Result.UNAVAILABLE;assertEquals(503,error(()->upload(key,scope)));assertEquals(0,puts);
        scanner=b->VirusScanner.Result.CLEAN;upload(key,scope);assertEquals(1,puts);
        scanner=b->VirusScanner.Result.INFECTED;assertEquals(422,error(()->upload(key(),scope)));assertEquals(1,puts);
    }
    @Test void uncertainPutCompensatesAndRetriesWithoutDeletingSuccessfulMaterials(){
        String key=key();failPut=true;assertEquals(503,error(()->upload(key,scope)));assertEquals(1,objects.size());assertEquals(0,count("input_material"));
        failDelete=true;uploads().reconcile();assertEquals(1,objects.size());failDelete=false;jdbc.update("UPDATE upload_cleanup_task SET next_run=UTC_TIMESTAMP()");uploads().reconcile();assertEquals(0,objects.size());
        failPut=false;upload(key,scope);jdbc.update("UPDATE upload_cleanup_task SET next_run=UTC_TIMESTAMP()");uploads().reconcile();assertEquals(1,objects.size());assertEquals(1,count("input_material"));
    }
    @Test void vehicleTransferDuringExternalWriteRollsBackMetadataAndQueuesCleanup(){
        beforePut=()->jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=11");assertEquals(404,error(()->upload(key(),scope)));assertEquals(0,count("input_material"));assertEquals(0,count("audit_log"));
        uploads().reconcile();assertEquals(0,objects.size());
    }
    @Test void auditFailureRollsBackMaterialAndCleansStoredObject(){
        jdbc.execute("CREATE TRIGGER reject_material_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic'");
        assertEquals(503,error(()->upload(key(),scope)));assertEquals(0,count("input_material"));assertEquals(1,objects.size());uploads().reconcile();assertEquals(0,objects.size());
    }
    @Test void signatureRechecksIdentityAfterExternalCallAndDefaultFlagIsClosed(){
        long id=upload(key(),scope).path("data").path("material_id").asLong();beforeSign=()->jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=11");assertEquals(404,error(()->service().access(owner,scope,id)));
        assertEquals(503,error(()->new InputMaterialService(uploads(),requests,jdbc,store,false).admission(owner,scope,true)));
    }
}
