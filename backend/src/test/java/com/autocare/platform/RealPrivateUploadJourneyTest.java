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
    "UPLOAD_SIGNED_URL_SECONDS=5","UPLOAD_CLEANUP_INTERVAL_MS=600000"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealPrivateUploadJourneyTest {
    static final String RUN=UUID.randomUUID().toString(), ROOT_PASSWORD=UUID.randomUUID().toString();
    static final String USER="test-upload-user", SECRET=UUID.randomUUID().toString(), BUCKET="upload-journey";
    static final Network NETWORK=Network.newNetwork();
    static final byte[] CLEAN=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");
    static final byte[] INFECTED=concat(CLEAN,"AutocareSyntheticScannerMarker".getBytes(StandardCharsets.US_ASCII));
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
        LoopbackStorage()throws IOException {
            listener=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));
            daemon(()->{while(!listener.isClosed())try{
                Socket incoming=listener.accept();sockets.add(incoming);
                Socket outgoing=new Socket(MINIO.getHost(),MINIO.getMappedPort(9000));sockets.add(outgoing);
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
    JsonNode upload(String name,String kind,String role,String path,byte[] data,String key,int expected)throws Exception{
        String boundary="autocare-upload-journey";
        byte[] body=concat(concat(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII),data),("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return request(name,path,kind,role,"POST",path,body,"multipart/form-data; boundary="+boundary,key,expected);
    }
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    void signedDownload(JsonNode response)throws Exception{
        URI uri=URI.create(response.path("data").path("url").asText());
        assertEquals("127.0.0.1",uri.getHost());assertEquals(publicStorage.listener.getLocalPort(),uri.getPort());
        var result=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,result.statusCode());assertArrayEquals(CLEAN,result.body());
    }
    @Test void realMultipartStorageScannerSigningAndArchivePermissions()throws Exception{
        application=app;assertEquals(132,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-upload-owner',1),(2,'synthetic-upload-other',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(1,1,100),(2,2,200)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic upload shop','test',1),(2,2,'synthetic other shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-upload-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-upload-other','ACTIVE'),(3,1,'TECHNICIAN','synthetic-upload-tech','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'upload-journey','synthetic-tech',3)");
        token("owner",1,"user","OWNER",0,null);token("other",2,"user","OWNER",0,null);
        token("merchant",1,"staff_account","MERCHANT",1,null);token("other_merchant",2,"staff_account","MERCHANT",2,null);token("technician",3,"staff_account","TECHNICIAN",1,1L);
        String[] roles={"owner","merchant","technician"},routes={"/api/file","/api/merchant/files","/api/tech/files"};
        for(int i=0;i<roles.length;i++){
            String role=roles[i],path=routes[i]+"/upload",key=UUID.randomUUID().toString();
            var saved=upload(role+"_upload","normal",role,path,CLEAN,key,200);assertEquals(i+1,saved.path("data").path("file_id").asInt());
            assertEquals(saved,upload(role+"_replay","boundary",role,path,CLEAN,key,200));
            upload(role+"_changed_body","error",role,path,concat(CLEAN,new byte[]{1}),key,409);
            upload(role+"_infected","error",role,path,INFECTED,UUID.randomUUID().toString(),422);
            upload(role+"_bad_magic","error",role,path,new byte[]{1},UUID.randomUUID().toString(),400);
            upload(role+"_missing_key","boundary",role,path,CLEAN,null,400);
            upload(role+"_anonymous","unauthorized","anonymous",path,CLEAN,UUID.randomUUID().toString(),401);
            upload(role+"_wrong_role","unauthorized",i==0?"merchant":"owner",path,CLEAN,UUID.randomUUID().toString(),403);
            String access=routes[i]+"/"+(i+1)+"/access",op=routes[i]+"/{id}/access";
            signedDownload(json(role+"_access",op,"normal",role,"GET",access,null,null,200));
            json(role+"_missing_file",op,"boundary",role,"GET",routes[i]+"/999999/access",null,null,404);
            json(role+"_access_query",op,"error",role,"GET",access+"?owner_id=1",null,null,400);
            json(role+"_access_anonymous",op,"unauthorized","anonymous","GET",access,null,null,401);
            json(role+"_access_wrong_role",op,"unauthorized",i==0?"merchant":"owner","GET",access,null,null,403);
        }
        json("foreign_owner_file","/api/file/{id}/access","unauthorized","other","GET","/api/file/1/access",null,null,404);
        json("foreign_shop_file","/api/merchant/files/{id}/access","unauthorized","other_merchant","GET","/api/merchant/files/2/access",null,null,404);
        String archive="{\"vehicle_id\":1,\"archive_type\":1,\"recorded_date\":\"2026-10-19\",\"mileage\":100,\"title\":\"synthetic upload archive\",\"notes\":\"private fixture\",\"file_ids\":[1]}",key=UUID.randomUUID().toString();
        var saved=json("archive_create","/api/archive/add","normal","owner","POST","/api/archive/add",archive,key,200);
        assertEquals(1,saved.path("data").path("archive_id").asInt());
        assertEquals(saved,json("archive_replay","/api/archive/add","boundary","owner","POST","/api/archive/add",archive,key,200));
        json("archive_invalid","/api/archive/add","error","owner","POST","/api/archive/add","{}",UUID.randomUUID().toString(),400);
        json("archive_anonymous","/api/archive/add","unauthorized","anonymous","POST","/api/archive/add",archive,UUID.randomUUID().toString(),401);
        json("archive_wrong_role","/api/archive/add","unauthorized","merchant","POST","/api/archive/add",archive,UUID.randomUUID().toString(),403);
        String attachment="/api/archive/{archiveId}/files/{fileId}/access",path="/api/archive/1/files/1/access";
        signedDownload(json("attachment_access",attachment,"normal","owner","GET",path,null,null,200));
        json("attachment_not_attached",attachment,"boundary","owner","GET","/api/archive/1/files/2/access",null,null,404);
        json("attachment_query",attachment,"error","owner","GET",path+"?owner_id=1",null,null,400);
        json("attachment_anonymous",attachment,"unauthorized","anonymous","GET",path,null,null,401);
        json("attachment_wrong_role",attachment,"unauthorized","merchant","GET",path,null,null,403);
        json("attachment_foreign",attachment,"unauthorized","other","GET",path,null,null,404);
        var access=json("fresh_signature","/api/file/{id}/access","boundary","owner","GET","/api/file/1/access",null,null,200);
        URI uri=URI.create(access.path("data").path("url").asText());
        var unsigned=client.send(HttpRequest.newBuilder(URI.create(uri.getScheme()+"://"+uri.getAuthority()+uri.getPath())).GET().build(),HttpResponse.BodyHandlers.discarding());
        check("anonymous_object_denied",unsigned.statusCode()==403);
        String modified=uri.toString().replace("X-Amz-Signature=","X-Amz-Signature=0");
        check("tampered_signature_denied",client.send(HttpRequest.newBuilder(URI.create(modified)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        Thread.sleep(6100);
        check("expired_signature_denied",client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        check("only_three_clean_files",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE scan_status='CLEAN'",Integer.class)==3&&jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class)==3);
        check("three_successful_uploads",jdbc.queryForObject("SELECT COUNT(*) FROM upload_request WHERE state='SUCCEEDED'",Integer.class)==3);
        check("three_scan_rejections",jdbc.queryForObject("SELECT COUNT(*) FROM upload_request WHERE state='REJECTED' AND error_status=422",Integer.class)==3);
        check("one_owned_archive_attachment",jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive_file",Integer.class)==1);
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){
            try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("backend/src")||p.toString().endsWith(".java")).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}
        }
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_upload_acceptance.py"));
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
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","private-upload-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"checks",CHECKS,"cleanup",CLEANUP,"scope","synthetic identities; real MinIO and ClamAV with synthetic signatures; no production definitions or external supplier"));
    }
}
