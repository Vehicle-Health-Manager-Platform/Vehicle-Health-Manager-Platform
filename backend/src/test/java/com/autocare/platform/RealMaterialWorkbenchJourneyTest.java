package com.autocare.platform;

import com.autocare.platform.file.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;
import com.github.dockerjava.api.exception.NotFoundException;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, scanner engine, storage, database and production beans. No production freshness bypass. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "JWT_SECRET=synthetic-upload-journey-secret-at-least-32-characters","WECHAT_APP_ID=upload-journey",
    "UPLOAD_STORAGE_ENABLED=true","UPLOAD_SCAN_ENABLED=false","UPLOAD_ALLOW_INSECURE=true",
    "INPUT_MATERIAL_UPLOAD_ENABLED=true","UPLOAD_SIGNED_URL_SECONDS=10","UPLOAD_CLEANUP_INTERVAL_MS=600000"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealMaterialWorkbenchJourneyTest {
    static final String RUN=UUID.randomUUID().toString(), ROOT_PASSWORD=UUID.randomUUID().toString();
    static final String USER="test-upload-user", SECRET=UUID.randomUUID().toString(), BUCKET="upload-journey";
    static final Network NETWORK=Network.newNetwork();
    // Structural MPEG fixtures only; this is not a playback or recognition claim.
    static final byte[] CLEAN=InputMaterialValidatorTest.audio(28);
    static final byte[] INFECTED=InputMaterialValidatorTest.audio(29);
    static byte[] concat(byte[] a,byte[] b){var out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
    static Future<String> image(String name,String dir,boolean script){
        if(Boolean.getBoolean("upload.test.images.prebuilt"))return CompletableFuture.completedFuture(name);
        var img=new ImageFromDockerfile(name,false).withDockerfile(Path.of("..","deploy",dir,"Dockerfile"));
        if(script)img.withFileFromPath("initialize.sh",Path.of("..","deploy",dir,"initialize.sh"));return img;
    }
    static String signature(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(INFECTED))+":"+INFECTED.length+":Autocare.Synthetic.Test\n";}catch(Exception e){throw new IllegalStateException(e);}}
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withDatabaseName("upload_journey")
        .withCommand("--log-bin-trust-function-creators=1").withLabel("autocare.upload.run",RUN);
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(image("autocare-upload-minio-ci","minio",false))
        .withEnv("MINIO_ROOT_USER","test-upload-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
        .withNetwork(NETWORK).withNetworkAliases("upload-minio").withExposedPorts(9000).withCommand("server","/data")
        .withLabel("autocare.upload.run",RUN).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> CLAM=new GenericContainer<>("clamav/clamav:1.4.3_base")
        .withCopyToContainer(Transferable.of(signature()),"/test-db/test.hdb")
        .withCopyToContainer(Transferable.of("Foreground yes\nUser root\nDatabaseDirectory /test-db\nTCPSocket 3310\nTCPAddr 0.0.0.0\nStreamMaxLength 10M\nMaxFileSize 12M\nMaxScanSize 12M\nAlertExceedsMax yes\nPidFile /tmp/clamd.pid\n"),"/test-clamd.conf")
        .withCreateContainerCmdModifier(c->c.withEntrypoint("clamd")).withCommand("--config-file=/test-clamd.conf")
        .withExposedPorts(3310).withLabel("autocare.upload.run",RUN).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static GenericContainer<?> initializer;
    static LoopbackStorage publicStorage;
    /** Keeps the production HTTP-public-endpoint restriction on loopback, including Docker Desktop. */
    static final class LoopbackStorage implements AutoCloseable {
        final ServerSocket listener;
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        LoopbackStorage()throws IOException {this(MINIO.getHost(),MINIO.getMappedPort(9000));}
        LoopbackStorage(String host,int port)throws IOException {
            listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
            daemon(()->{while(!listener.isClosed())try{
                Socket incoming=listener.accept();sockets.add(incoming);
                Socket outgoing=new Socket(host,port);sockets.add(outgoing);
                daemon(()->pipe(incoming,outgoing));daemon(()->pipe(outgoing,incoming));
            }catch(IOException stopped){if(!listener.isClosed())throw new UncheckedIOException(stopped);}});
        }
        static void daemon(Runnable action){Thread thread=new Thread(action,"upload-loopback-forwarder");thread.setDaemon(true);thread.start();}
        void pipe(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(IOException ignored){}finally{for(Socket socket:List.of(from,to)){try{socket.close();}catch(IOException ignored){}sockets.remove(socket);}}}
        String endpoint(){return "http://127.0.0.1:"+listener.getLocalPort();}
        @Override public void close()throws IOException{listener.close();for(Socket socket:sockets)socket.close();assertTrue(listener.isClosed());}
    }
    static boolean prepared,completed;
    static final Map<String,String> RESOURCE_IDS=new HashMap<>();
    static final List<Map<String,Object>> EVIDENCE=new ArrayList<>();
    static final Map<String,Boolean> CHECKS=new LinkedHashMap<>(),CLEANUP=new LinkedHashMap<>();
    @TestConfiguration static class ScannerFixture {
        // Real engine + explicit synthetic database; formal freshness is tested separately, never disabled in main code.
        @Bean VirusScanner syntheticDatabaseScanner(){return new ClamdVirusScanner(CLAM.getHost(),CLAM.getMappedPort(3310),2000,10000,false);}
    }
    @DynamicPropertySource static synchronized void prepare(DynamicPropertyRegistry r)throws Exception{
        MYSQL.start();MINIO.start();CLAM.start();
        if(!prepared){
            initializer=new GenericContainer<>(image("autocare-upload-init-ci","upload-init",true)).withNetwork(NETWORK)
                .withLabel("autocare.upload.run",RUN).withEnv("MINIO_ENDPOINT","http://upload-minio:9000")
                .withEnv("MINIO_ROOT_USER","test-upload-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
                .withEnv("UPLOAD_MINIO_BUCKET",BUCKET).withEnv("UPLOAD_MINIO_ACCESS_KEY",USER).withEnv("UPLOAD_MINIO_SECRET_KEY",SECRET)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
            initializer.start();
            RESOURCE_IDS.put("initializer",initializer.getContainerId());RESOURCE_IDS.put("minio",MINIO.getContainerId());RESOURCE_IDS.put("clamav",CLAM.getContainerId());RESOURCE_IDS.put("mysql",MYSQL.getContainerId());
            var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            try(var connection=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
                for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            }
            prepared=true;
            publicStorage=new LoopbackStorage();
        }
        r.add("MYSQL_HOST",MYSQL::getHost);r.add("MYSQL_PORT",MYSQL::getFirstMappedPort);r.add("MYSQL_DATABASE",MYSQL::getDatabaseName);
        r.add("MYSQL_USER",MYSQL::getUsername);r.add("MYSQL_PASSWORD",MYSQL::getPassword);
        r.add("MINIO_ENDPOINT",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));
        r.add("MINIO_PUBLIC_ENDPOINT",publicStorage::endpoint);
        RealMileageAcceptance.configure(r);
        r.add("UPLOAD_MINIO_ACCESS_KEY",()->USER);r.add("UPLOAD_MINIO_SECRET_KEY",()->SECRET);r.add("UPLOAD_MINIO_BUCKET",()->BUCKET);r.add("UPLOAD_MINIO_REGION",()->"us-east-1");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired JwtEncoder encoder;@Autowired ConfigurableApplicationContext app;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<String,String> tokens=new HashMap<>();
    static ConfigurableApplicationContext application;
    String token(String label,long id,String type,String role,long merchant,Long binding){
        String session=UUID.randomUUID().toString(),appId=role.equals("MERCHANT")?"merchant-account":"upload-journey";
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,type,id,role,appId,merchant==0?null:merchant,binding,UUID.randomUUID().toString());
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("subject_type",type).claim("role",role).claim("app_id",appId).claim("jti",session);
        if(merchant>0)claims.claim("merchant_id",merchant);if(binding!=null)claims.claim("binding_id",binding);
        String value=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();tokens.put(label,value);return value;
    }
    JsonNode request(String name,String operation,String kind,String role,String method,String path,byte[] body,String contentType,String key,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(!role.equals("anonymous"))b.header("Authorization","Bearer "+tokens.get(role));
        if(method.equals("POST") && path.startsWith("/api/input-materials/"))b.header("X-Material-Kind",materialKind);
        if(key!=null)b.header("Idempotency-Key",key);if(contentType!=null)b.header("Content-Type",contentType);
        var sent=b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=client.send(sent,HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),name);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),name);
        var json=mapper.readTree(response.body());assertTrue(json.path("code").isIntegralNumber(),name);assertEquals(expected==200?0:expected==400?40001:expected*100,json.path("code").asInt(),name);
        EVIDENCE.add(Map.of("name",name,"operation",operation,"kind",kind,"role",role,"method",sent.method(),"path",path,"status",response.statusCode(),"passed",true));return json;
    }
    JsonNode json(String name,String op,String kind,String role,String method,String path,String body,String key,int expected)throws Exception{
        return request(name,op,kind,role,method,path,body==null?null:body.getBytes(StandardCharsets.UTF_8),body==null?null:"application/json",key,expected);
    }

    static final String UP="/api/input-materials/{purpose}/{vehicleId}/upload", ACCESS="/api/input-materials/{purpose}/{vehicleId}/{id}/access";
    String materialKind="audio";
    JsonNode material(String name,String kind,String role,String path,byte[] bytes,String type,String key,int expected)throws Exception{
        materialKind=type;
        String boundary="autocare-material-journey";
        byte[] body=concat(concat(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"native-temp.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII),bytes),("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return request(name,UP,kind,role,"POST",path,body,"multipart/form-data; boundary="+boundary,key,expected);
    }
    JsonNode access(String name,String kind,String role,String path,int expected)throws Exception{return json(name,ACCESS,kind,role,"GET",path,null,null,expected);}
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    URI download(JsonNode response,byte[] bytes,String type)throws Exception{
        var data=response.path("data");assertEquals(Set.of("url","expires_at"),fields(data));
        URI uri=URI.create(data.path("url").asText());assertEquals("127.0.0.1",uri.getHost());assertEquals(publicStorage.listener.getLocalPort(),uri.getPort());
        assertTrue(uri.getRawQuery().contains("X-Amz-Expires=60"));
        long ttl=Duration.between(Instant.now(),Instant.parse(data.path("expires_at").asText())).getSeconds();assertTrue(ttl>=50&&ttl<=60);
        var result=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,result.statusCode());assertArrayEquals(bytes,result.body());assertEquals(type,result.headers().firstValue("Content-Type").orElse(""));
        assertTrue(result.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));assertEquals("no-store",result.headers().firstValue("Cache-Control").orElse(""));return uri;
    }
    Set<String> fields(JsonNode n){var names=new HashSet<String>();n.fieldNames().forEachRemaining(names::add);return names;}
    JsonNode bench(String name,String kind,String role,String query,int expected)throws Exception{
        String op="/api/"+(role.equals("technician")||role.equals("peer")?"tech":"merchant")+"/workbench";
        return benchAt(name,kind,role,op,query,expected);
    }
    JsonNode benchAt(String name,String kind,String role,String op,String query,int expected)throws Exception{
        var result=json(name,op,kind,role,"GET",op+query,null,null,expected);
        if(expected==200){var d=result.path("data");assertEquals(8,d.path("counts").size());assertEquals(20,d.path("tasks").path("page_size").asInt());
            for(var item:d.path("tasks").path("items"))assertEquals(Set.of("order_id","order_status","bucket","project_name","starts_at"),fields(item));
            for(String privateText:List.of("PRIVATE_","owner_id","vehicle_id","phone","plate_no","vin"))assertFalse(result.toString().contains(privateText));}
        return result;
    }
    void bucket(String bucket)throws Exception{
        for(String role:List.of("merchant","technician")){
            var d=bench("bucket_"+bucket+"_"+role,"boundary",role,"?bucket="+bucket,200).path("data");
            assertEquals(1,d.path("tasks").path("total").asInt());assertEquals(1,d.path("counts").path(bucket).asInt());
            assertEquals(bucket,d.path("tasks").path("items").get(0).path("bucket").asText());
            long sum=0;for(var n:d.path("counts"))sum+=n.asLong();assertEquals(1,sum);
        }
    }
    @Test void realMaterialIsolationAndCurrentWorkbenches()throws Exception{
        application=app;assertEquals(131,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-material-owner',1),(2,'synthetic-material-other',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage,plate_no,vin) VALUES(1,1,100,'PRIVATE_PLATE','PRIVATE_VIN'),(2,2,200,'PRIVATE_OTHER','PRIVATE_OTHER_VIN'),(3,1,300,'PRIVATE_THIRD','PRIVATE_THIRD_VIN')");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic shop','test',1),(2,2,'synthetic other shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-other','ACTIVE'),(3,1,'TECHNICIAN','synthetic-tech','ACTIVE'),(4,1,'TECHNICIAN','synthetic-peer','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'upload-journey','synthetic-tech',3),(2,'upload-journey','synthetic-peer',4)");
        token("owner",1,"user","OWNER",0,null);token("other",2,"user","OWNER",0,null);token("merchant",1,"staff_account","MERCHANT",1,null);token("other_merchant",2,"staff_account","MERCHANT",2,null);token("technician",3,"staff_account","TECHNICIAN",1,1L);token("peer",4,"staff_account","TECHNICIAN",1,2L);
        String ai="/api/input-materials/AI/1/upload",ar="/api/input-materials/ARCHIVE/1/upload",key=UUID.randomUUID().toString();
        var audio=material("audio_ai","normal","owner",ai,CLEAN,"audio",key,200);assertEquals(1,audio.path("data").path("material_id").asInt());assertEquals(1008,audio.path("data").path("duration_ms").asInt());
        assertEquals(audio,material("audio_replay","boundary","owner",ai,CLEAN,"audio",key,200));
        material("changed_content","boundary","owner",ai,InputMaterialValidatorTest.audio(30),"audio",key,409);
        material("invalid_bytes","error","owner",ai,new byte[]{1},"audio",UUID.randomUUID().toString(),422);
        byte[] pdf=InputMaterialValidatorTest.pdf(2);
        var doc=material("pdf_archive","normal","owner",ar,pdf,"pdf",key,200);assertEquals(2,doc.path("data").path("material_id").asInt());assertEquals(2,doc.path("data").path("pages").asInt());
        material("audio_archive","normal","other","/api/input-materials/ARCHIVE/2/upload",CLEAN,"audio",key,200);
        material("pdf_ai","normal","other","/api/input-materials/AI/2/upload",pdf,"pdf",key,200);
        material("same_key_other_vehicle","boundary","owner","/api/input-materials/AI/3/upload",CLEAN,"audio",key,200);
        material("unknown_kind","error","owner",ai,CLEAN,"image",UUID.randomUUID().toString(),400);
        material("upload_query","error","owner",ai+"?owner_id=2",CLEAN,"audio",UUID.randomUUID().toString(),400);
        var infected=material("infected_bytes","error","owner",ai,INFECTED,"audio",UUID.randomUUID().toString(),422);
        assertEquals("上传文件未通过病毒检查",infected.path("message").asText());
        material("upload_anonymous","unauthorized","anonymous",ai,CLEAN,"audio",key,401);
        material("upload_merchant","unauthorized","merchant",ai,CLEAN,"audio",key,403);
        material("upload_foreign_vehicle","unauthorized","owner","/api/input-materials/AI/2/upload",CLEAN,"audio",key,404);
        material("upload_unknown_purpose","error","owner","/api/input-materials/UNKNOWN/1/upload",CLEAN,"audio",key,400);
        check("five_isolated_materials",jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE scan_status='CLEAN'",Integer.class)==5);
        check("materials_never_become_images",jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class)==0);
        check("five_successful_uploads",jdbc.queryForObject("SELECT COUNT(*) FROM upload_request WHERE state='SUCCEEDED'",Integer.class)==5
            &&jdbc.queryForObject("SELECT COUNT(*) FROM upload_request WHERE state='REJECTED' AND error_status=422",Integer.class)==1);
        String a="/api/input-materials/AI/1/1/access";
        URI lease=download(access("access_audio","normal","owner",a,200),CLEAN,"audio/mpeg");
        download(access("access_pdf","normal","owner","/api/input-materials/ARCHIVE/1/2/access",200),pdf,"application/pdf");
        download(access("access_other_audio","normal","other","/api/input-materials/ARCHIVE/2/3/access",200),CLEAN,"audio/mpeg");
        download(access("access_other_pdf","normal","other","/api/input-materials/AI/2/4/access",200),pdf,"application/pdf");
        check("four_type_purpose_downloads",true);
        access("access_missing","boundary","owner","/api/input-materials/AI/1/999999/access",404);
        access("access_query","error","owner",a+"?owner_id=2",400);access("access_duplicate_query","error","owner",a+"?page=1&page=1",400);
        access("access_zero_id","error","owner","/api/input-materials/AI/1/0/access",400);access("access_unsafe_id","error","owner","/api/input-materials/AI/1/9007199254740992/access",400);
        access("access_wrong_purpose","unauthorized","owner","/api/input-materials/ARCHIVE/1/1/access",404);
        access("access_other_owned_vehicle","unauthorized","owner","/api/input-materials/AI/3/1/access",404);
        access("access_foreign_owner","unauthorized","other",a,404);access("access_anonymous","unauthorized","anonymous",a,401);access("access_merchant","unauthorized","merchant",a,403);access("access_technician","unauthorized","technician",a,403);
        check("scope_and_query_enforced",true);
        URI unsigned=URI.create(lease.toString().split("\\?")[0]);assertEquals(403,client.send(HttpRequest.newBuilder(unsigned).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        URI tampered=URI.create(lease+"&response-content-type=text/plain");assertEquals(403,client.send(HttpRequest.newBuilder(tampered).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());check("private_bucket_and_signature_enforced",true);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE input_material SET scan_status='INFECTED' WHERE id=1"));
        download(access("access_scan_constraint","boundary","owner",a,200),CLEAN,"audio/mpeg");
        jdbc.update("UPDATE input_material SET is_deleted=1 WHERE id=1");access("access_deleted","unauthorized","owner",a,404);jdbc.update("UPDATE input_material SET is_deleted=0 WHERE id=1");
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=1");access("access_transferred","unauthorized","owner",a,404);jdbc.update("UPDATE vehicle SET user_id=1 WHERE id=1");check("material_current_facts_rechecked",true);
        // SQL state fixtures exercise read classification, not dispatch/payment HTTP writes.
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,assigned_at,project_snapshot) VALUES(1,'synthetic-material-task',1,1,1,10,10,'IN_SERVICE',UTC_TIMESTAMP(),?)","{\"project_name\":\"synthetic inspection\",\"phone\":\"PRIVATE_CONTACT\",\"vin\":\"PRIVATE_VIN\"}");
        jdbc.update("INSERT INTO technician_assignment(order_id,merchant_id,technician_id,assigned_by,status,accepted_at) VALUES(1,1,3,1,'ACCEPTED',UTC_TIMESTAMP())");
        for(String role:List.of("merchant","technician")){
            assertEquals(1,bench("bench_"+role,"normal",role,"",200).path("data").path("tasks").path("total").asInt());
            assertEquals(0,bench("empty_page_"+role,"boundary",role,"?page=2",200).path("data").path("tasks").path("items").size());
            for(String q:List.of("owner_id=2","bucket=BAD","bucket=SERVICE&bucket=SERVICE","page=0","page_size=100","page=1&page=1"))bench("bad_"+role+"_"+q,"error",role,"?"+q,400);
            String op="/api/"+(role.equals("merchant")?"merchant":"tech")+"/workbench";
            benchAt("anonymous_"+role,"unauthorized","anonymous",op,"",401);benchAt("wrong_role_"+role,"unauthorized","owner",op,"",403);
        }
        assertEquals(0,bench("other_shop","unauthorized","other_merchant","",200).path("data").path("tasks").path("total").asInt());
        assertEquals(0,bench("unassigned_peer","unauthorized","peer","",200).path("data").path("tasks").path("total").asInt());check("workbench_actor_isolation",true);
        bucket("SERVICE");jdbc.update("UPDATE `order` SET status='PAID' WHERE id=1");bucket("RECEIVE");
        jdbc.update("UPDATE `order` SET status='RECEIVED' WHERE id=1");bucket("CONFIRM");
        jdbc.update("INSERT INTO pickup_check(order_id,merchant_id,owner_confirm,confirm_at) VALUES(1,1,1,UTC_TIMESTAMP())");jdbc.update("UPDATE `order` SET check_in_completed_at=UTC_TIMESTAMP(),owner_confirmed_at=UTC_TIMESTAMP() WHERE id=1");
        jdbc.update("UPDATE technician_assignment SET status='ASSIGNED',accepted_at=NULL WHERE order_id=1");bucket("WAIT_ACCEPT");
        jdbc.update("UPDATE `order` SET status='PENDING_VERIFY' WHERE id=1");bucket("REVIEW_SOURCE");
        jdbc.update("UPDATE technician_assignment SET status='ACCEPTED',accepted_at=UTC_TIMESTAMP() WHERE order_id=1");jdbc.update("UPDATE `order` SET service_report_ready_at=UTC_TIMESTAMP() WHERE id=1");bucket("VERIFY");
        jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");bucket("DISPUTE");
        jdbc.update("DELETE FROM technician_assignment WHERE order_id=1");jdbc.update("UPDATE `order` SET status='RECEIVED',assigned_at=NULL WHERE id=1");
        var assign=bench("bucket_ASSIGN_merchant","boundary","merchant","?bucket=ASSIGN",200).path("data");assertEquals(1,assign.path("counts").path("ASSIGN").asInt());assertEquals(1,assign.path("tasks").path("total").asInt());
        assertEquals(0,bench("no_assignment_tech","boundary","technician","",200).path("data").path("tasks").path("total").asInt());check("eight_mutually_exclusive_buckets",true);
        jdbc.update("UPDATE `order` SET status='IN_SERVICE',assigned_at=UTC_TIMESTAMP() WHERE id=1");jdbc.update("INSERT INTO technician_assignment(order_id,merchant_id,technician_id,assigned_by,status,accepted_at) VALUES(1,1,4,1,'ACCEPTED',UTC_TIMESTAMP())");
        assertEquals(0,bench("former_tech","unauthorized","technician","",200).path("data").path("tasks").path("total").asInt());assertEquals(1,bench("new_tech","normal","peer","",200).path("data").path("tasks").path("total").asInt());
        jdbc.update("UPDATE `order` SET status='CLOSED' WHERE id=1");assertEquals(0,bench("closed_merchant","boundary","merchant","",200).path("data").path("tasks").path("total").asInt());assertEquals(0,bench("closed_tech","boundary","peer","",200).path("data").path("tasks").path("total").asInt());check("assignment_and_closed_tasks_rechecked",true);
        for(int i=2;i<=22;i++)jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(?,?,1,1,1,10,10,'PAID')",i,"synthetic-page-"+i);
        var page1=bench("page_one","boundary","merchant","?page=1&page_size=20",200).path("data").path("tasks");var page2=bench("page_two","boundary","merchant","?page=2&page_size=20",200).path("data").path("tasks");
        assertEquals(21,page1.path("total").asInt());assertEquals(20,page1.path("items").size());assertEquals(1,page2.path("items").size());var ids=new HashSet<Long>();for(var n:page1.path("items"))assertTrue(ids.add(n.path("order_id").asLong()));for(var n:page2.path("items"))assertTrue(ids.add(n.path("order_id").asLong()));check("pagination_has_no_duplicate_or_missing_tasks",ids.size()==21);
        check("workbench_minimal_private_fields",true);
        jdbc.update("UPDATE staff_wechat_identity SET unbound_at=UTC_TIMESTAMP() WHERE id=2");bench("unbound_tech","unauthorized","peer","",401);
        jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=1");bench("disabled_merchant","unauthorized","merchant","",401);check("current_staff_and_binding_required",true);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='user' AND subject_id=1");access("revoked_owner","unauthorized","owner",a,401);check("current_owner_session_required",true);
        check("no_payment_archive_or_rewards",jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class)==0&&jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class)==0&&jdbc.queryForObject("SELECT COUNT(*) FROM point_task_event",Integer.class)==0);
        if(System.getenv("AUTOCARE_NATIVE_MATERIAL_RUN_ID")!=null){
            jdbc.update("INSERT INTO user(id,openid,status) VALUES(991,'synthetic-native-material',1)");
            jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(991,991,100)");
            byte[] fixture=switch(System.getenv().getOrDefault("AUTOCARE_NATIVE_UPLOAD_KIND","pdf")){case "image"->NativeMaterialAcceptance.image();case "audio"->NativeMaterialAcceptance.audio();case "pdf"->InputMaterialValidatorTest.pdf(1);default->throw new IllegalArgumentException("Invalid native upload mode");};
            NativeMaterialAcceptance.run(jdbc,mapper,port,token("native_owner",991,"user","OWNER",0,null),fixture);
        }
        if(RealMileageAcceptance.enabled()){
            assertNull(System.getenv("AUTOCARE_NATIVE_MATERIAL_RUN_ID"));
            jdbc.update("INSERT INTO user(id,openid,status) VALUES(991,'synthetic-real-mileage',1)");
            jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(991,991,100)");
            RealMileageAcceptance.run(jdbc,mapper,port,token("real_mileage",991,"user","OWNER",0,null),tokens.get("other"));
            if(RealMileageAcceptance.config.path("native").asBoolean()){
                jdbc.update("INSERT INTO user(id,openid,status) VALUES(992,'synthetic-native-mileage',1)");
                jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(992,992,100)");
                var nativeProof=NativeMileageAcceptance.run(jdbc,mapper,port,token("native_mileage",992,"user","OWNER",0,null),Base64.getDecoder().decode(RealMileageAcceptance.config.path("image").asText()));
                RealMileageAcceptance.proof.put("native_database",nativeProof);
            }
        }
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){
            try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}
        }
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_material_workbench_acceptance.py"));
        files.sort(Comparator.comparing(p->root.relativize(p).toString().replace('\\','/')));
        var digest=MessageDigest.getInstance("SHA-256");
        for(var file:files){digest.update(root.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(file));}
        return HexFormat.of().formatHex(digest.digest());
    }
    @AfterAll static void onNormalClose()throws Exception{
        if(application==null){cleanup();return;}
        application.addApplicationListener(event->{
            if(event instanceof org.springframework.context.event.ContextClosedEvent closed && closed.getApplicationContext()==application){
                try{cleanup();}catch(Exception|AssertionError failure){completed=false;}
            }
        });
    }
    static void cleanup()throws Exception{
        if(publicStorage!=null)publicStorage.close();
        RealMileageAcceptance.close();
        var resources=new LinkedHashMap<String,GenericContainer<?>>();
        resources.put("initializer",initializer);resources.put("clamav",CLAM);resources.put("minio",MINIO);resources.put("mysql",MYSQL);
        for(var entry:resources.entrySet()){
            if(entry.getValue()==null){CLEANUP.put(entry.getKey(),true);continue;}
            var container=entry.getValue();String id=RESOURCE_IDS.getOrDefault(entry.getKey(),container.getContainerId());
            try{container.stop();if(id!=null)assertThrows(NotFoundException.class,()->DockerClientFactory.instance().client().inspectContainerCmd(id).exec());CLEANUP.put(entry.getKey(),true);}
            catch(Exception|AssertionError e){CLEANUP.put(entry.getKey(),false);}
        }
        String id=NETWORK.getId();try{NETWORK.close();assertThrows(NotFoundException.class,()->DockerClientFactory.instance().client().inspectNetworkCmd().withNetworkId(id).exec());CLEANUP.put("network",true);}catch(Exception|AssertionError e){CLEANUP.put("network",false);}
        assertTrue(CLEANUP.values().stream().allMatch(Boolean::booleanValue));
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","material-workbench-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"checks",CHECKS,"cleanup",CLEANUP,"scope","synthetic identities and task state fixtures; structural audio; real HTTP/MySQL/MinIO/ClamAV with synthetic scan signature; no supplier or device acceptance"));
    }
}
