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

/**
 * Operator access to merchant onboarding qualification attachments over real HTTP, object storage
 * and database. Identity and application rows are synthetic fixtures; uploading, application
 * submission, signing and every signed download are real calls.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "JWT_SECRET=synthetic-onboarding-files-secret-at-least-32","WECHAT_APP_ID=onboarding-files",
    "UPLOAD_STORAGE_ENABLED=true","UPLOAD_SCAN_ENABLED=false","UPLOAD_ALLOW_INSECURE=true",
    "UPLOAD_SIGNED_URL_SECONDS=5","UPLOAD_CLEANUP_INTERVAL_MS=600000","MERCHANT_ONBOARDING_ENABLED=true"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealMerchantOnboardingFileAccessJourneyTest {
    static final String RUN=UUID.randomUUID().toString(), ROOT_PASSWORD=UUID.randomUUID().toString();
    static final String USER="test-onboarding-files-user", SECRET=UUID.randomUUID().toString(), BUCKET="onboarding-files";
    static final String APP="onboarding-files";
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
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withDatabaseName("onboarding_files")
        .withCommand("--log-bin-trust-function-creators=1").withLabel("autocare.onboarding.files.run",RUN);
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(image("autocare-upload-minio-ci","minio",false))
        .withEnv("MINIO_ROOT_USER","test-onboarding-files-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
        .withNetwork(NETWORK).withNetworkAliases("onboarding-files-minio").withExposedPorts(9000).withCommand("server","/data")
        .withLabel("autocare.onboarding.files.run",RUN).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> CLAM=new GenericContainer<>("clamav/clamav:1.4.3_base")
        .withCopyToContainer(Transferable.of(signature()),"/test-db/test.hdb")
        .withCopyToContainer(Transferable.of("Foreground yes\nUser root\nDatabaseDirectory /test-db\nTCPSocket 3310\nTCPAddr 0.0.0.0\nStreamMaxLength 10M\nMaxFileSize 12M\nMaxScanSize 12M\nAlertExceedsMax yes\nPidFile /tmp/clamd.pid\n"),"/test-clamd.conf")
        .withCreateContainerCmdModifier(c->c.withEntrypoint("clamd")).withCommand("--config-file=/test-clamd.conf")
        .withExposedPorts(3310).withLabel("autocare.onboarding.files.run",RUN).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
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
        static void daemon(Runnable action){Thread thread=new Thread(action,"onboarding-files-loopback-forwarder");thread.setDaemon(true);thread.start();}
        void pipe(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(IOException ignored){}finally{for(Socket socket:List.of(from,to)){try{socket.close();}catch(IOException ignored){}sockets.remove(socket);}}}
        String endpoint(){return "http://127.0.0.1:"+listener.getLocalPort();}
        @Override public void close()throws IOException{listener.close();for(Socket socket:sockets)socket.close();assertTrue(listener.isClosed());}
    }
    static boolean prepared,completed,leaseBounded;
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
                .withLabel("autocare.onboarding.files.run",RUN).withEnv("MINIO_ENDPOINT","http://onboarding-files-minio:9000")
                .withEnv("MINIO_ROOT_USER","test-onboarding-files-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
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
        String session=UUID.randomUUID().toString(),appId=switch(role){case "MERCHANT","STAFF"->"merchant-account";case "OPERATOR"->"operator-account";default->APP;};
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,type,id,role,appId,merchant==0?null:merchant,binding,UUID.randomUUID().toString());
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("subject_type",type).claim("role",role).claim("app_id",appId).claim("jti",session);
        if(merchant>0)claims.claim("merchant_id",merchant);if(binding!=null)claims.claim("binding_id",binding);
        String value=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();tokens.put(label,value);return value;
    }
    /** kind==null marks setup traffic; only the scoped attachment operation becomes evidence rows. */
    JsonNode call(String name,String operation,String kind,String role,String method,String path,byte[] body,String contentType,String key,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(!role.equals("anonymous"))b.header("Authorization","Bearer "+tokens.get(role));
        if(key!=null)b.header("Idempotency-Key",key);if(contentType!=null)b.header("Content-Type",contentType);
        var sent=b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=client.send(sent,HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),name);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),name);
        var json=mapper.readTree(response.body());assertTrue(json.path("code").isIntegralNumber(),name);assertEquals(expected==200?0:expected==400?40001:expected*100,json.path("code").asInt(),name);
        if(kind!=null)EVIDENCE.add(Map.of("name",name,"operation",operation,"kind",kind,"role",role,"method",sent.method(),"path",path,"status",response.statusCode(),"passed",true));
        return json;
    }
    JsonNode json(String name,String op,String kind,String role,String method,String path,String body,int expected)throws Exception{
        return call(name,op,kind,role,method,path,body==null?null:body.getBytes(StandardCharsets.UTF_8),body==null?null:"application/json",body==null?null:UUID.randomUUID().toString(),expected);
    }
    JsonNode setup(String name,String role,String method,String path,String body)throws Exception{
        return call(name,null,null,role,method,path,body==null?null:body.getBytes(StandardCharsets.UTF_8),body==null?null:"application/json",UUID.randomUUID().toString(),200);
    }
    JsonNode upload(String name,String role)throws Exception{
        String boundary="autocare-onboarding-files";
        byte[] body=concat(concat(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"qualification.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII),CLEAN),("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return call(name,null,null,role,"POST","/api/file/upload",body,"multipart/form-data; boundary="+boundary,UUID.randomUUID().toString(),200);
    }
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    long qualification(String role)throws Exception{return upload("upload",role).path("data").path("file_id").asLong();}
    static String application(String name,List<Long> files){
        return "{\"merchant_name\":\""+name+"\",\"category\":\"MAINTENANCE\",\"region_code\":\"310000\",\"address\":\"合成入驻地址\",\"contact_phone\":\"13800000001\",\"qualification_file_ids\":"+files+"}";
    }
    URI signedDownload(JsonNode response)throws Exception{
        var data=response.path("data");var names=new HashSet<String>();data.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("url","expires_at"),names);
        URI uri=URI.create(data.path("url").asText());assertEquals("127.0.0.1",uri.getHost());assertEquals(publicStorage.listener.getLocalPort(),uri.getPort());
        assertTrue(uri.getRawQuery().contains("X-Amz-Expires=5"));
        long lease=Duration.between(Instant.now(),Instant.parse(data.path("expires_at").asText())).getSeconds();
        assertTrue(lease>=0&&lease<=10,"lease was "+lease);leaseBounded=true;
        var result=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,result.statusCode());assertArrayEquals(CLEAN,result.body());assertEquals("image/png",result.headers().firstValue("Content-Type").orElse(""));
        return uri;
    }
    @Test void realMerchantOnboardingQualificationAttachmentPermissionsAndSignatures()throws Exception{
        application=app;assertEquals(134,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-onboarding-applicant',1),(2,'synthetic-onboarding-peer',1)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,1,'synthetic onboarded shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-onboarded-shop','ACTIVE')");
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,status,can_onboard,can_review) VALUES(1,'synthetic-onboarding-reviewer','not-a-login-password','13900000031','ACTIVE',1,0),(2,'synthetic-onboarding-readonly','not-a-login-password','13900000032','ACTIVE',0,1)");
        token("owner",1,"user","OWNER",0,null);token("peer",2,"user","OWNER",0,null);
        token("merchant",1,"staff_account","MERCHANT",1,null);
        token("operator",1,"operator_account","OPERATOR",0,null);token("review_operator",2,"operator_account","OPERATOR",0,null);
        long first=qualification("owner"),second=qualification("owner"),third=qualification("peer");
        assertEquals(List.of(1L,2L,3L),List.of(first,second,third));
        setup("first_application","owner","POST","/api/merchant-applications",application("合成入驻门店",List.of(first,second)));
        setup("second_application","peer","POST","/api/merchant-applications",application("合成同行门店",List.of(third)));
        check("three_clean_uploads",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE scan_status='CLEAN' AND is_deleted=0",Integer.class)==3
            &&jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class)==3);
        check("files_owned_by_applicants_only",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_type='user' AND owner_id IN (1,2)",Integer.class)==3
            &&jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_type<>'user' OR owner_id NOT IN (1,2)",Integer.class)==0);
        check("applications_exact_two",jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application",Integer.class)==2
            &&jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application WHERE status='PENDING_REVIEW' AND revision=1",Integer.class)==2);
        check("qualification_snapshots_exact","1:2,2:1".equals(jdbc.queryForObject("SELECT GROUP_CONCAT(CONCAT(id,':',n) ORDER BY id) FROM (SELECT a.id,JSON_LENGTH(a.qualification_files) n FROM merchant_application a) c",String.class))
            &&"[1,2]".equals(jdbc.queryForObject("SELECT REPLACE(CAST(qualification_files AS CHAR),' ','') FROM merchant_application WHERE id=1",String.class))
            &&"[3]".equals(jdbc.queryForObject("SELECT REPLACE(CAST(qualification_files AS CHAR),' ','') FROM merchant_application WHERE id=2",String.class)));
        check("one_application_per_applicant",jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT applicant_user_id FROM merchant_application GROUP BY applicant_user_id HAVING COUNT(*)>1) x",Integer.class)==0);
        check("no_merchant_activation",jdbc.queryForObject("SELECT COUNT(*) FROM merchant",Integer.class)==1
            &&jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application WHERE merchant_id IS NOT NULL OR reviewed_at IS NOT NULL",Integer.class)==0);
        check("cross_application_files_never_linked",jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application WHERE id=1 AND JSON_CONTAINS(qualification_files,'3')=1",Integer.class)==0
            &&jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application WHERE id=2 AND (JSON_CONTAINS(qualification_files,'1')=1 OR JSON_CONTAINS(qualification_files,'2')=1)",Integer.class)==0);
        String access="/api/admin/merchant-applications/{id}/files/{file}/access";
        URI firstLease=signedDownload(json("onboarding_qualification_first",access,"normal","operator","GET","/api/admin/merchant-applications/1/files/1/access",null,200));
        signedDownload(json("onboarding_qualification_second",access,"normal","operator","GET","/api/admin/merchant-applications/1/files/2/access",null,200));
        signedDownload(json("onboarding_other_application",access,"normal","operator","GET","/api/admin/merchant-applications/2/files/3/access",null,200));
        json("onboarding_cross_application_file",access,"boundary","operator","GET","/api/admin/merchant-applications/1/files/3/access",null,404);
        json("onboarding_missing_application",access,"boundary","operator","GET","/api/admin/merchant-applications/999999/files/1/access",null,404);
        json("onboarding_query",access,"error","operator","GET","/api/admin/merchant-applications/1/files/1/access?page=1",null,400);
        json("onboarding_zero_application",access,"error","operator","GET","/api/admin/merchant-applications/0/files/1/access",null,400);
        json("onboarding_zero_file",access,"error","operator","GET","/api/admin/merchant-applications/1/files/0/access",null,400);
        json("onboarding_anonymous",access,"unauthorized","anonymous","GET","/api/admin/merchant-applications/1/files/1/access",null,401);
        json("onboarding_review_permission_only",access,"unauthorized","review_operator","GET","/api/admin/merchant-applications/1/files/1/access",null,403);
        json("onboarding_owner_denied",access,"unauthorized","owner","GET","/api/admin/merchant-applications/1/files/1/access",null,403);
        json("onboarding_merchant_denied",access,"unauthorized","merchant","GET","/api/admin/merchant-applications/1/files/1/access",null,403);
        URI unsigned=URI.create(firstLease.toString().split("\\?")[0]);
        check("unsigned_object_denied",client.send(HttpRequest.newBuilder(unsigned).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        String tampered=firstLease.toString().replace("X-Amz-Signature=","X-Amz-Signature=0");
        check("tampered_signature_denied",client.send(HttpRequest.newBuilder(URI.create(tampered)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        Thread.sleep(6100);
        check("expired_signature_denied",client.send(HttpRequest.newBuilder(firstLease).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        check("signature_lease_bounded",leaseBounded);
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){
            try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("backend/src")||p.toString().endsWith(".java")).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}
        }
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_merchant_onboarding_files_acceptance.py"));
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
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","merchant-onboarding-files-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"checks",CHECKS,"cleanup",CLEANUP,"scope","synthetic identities and merchant application fixtures; real HTTP uploads, onboarding submission and signed object downloads"));
    }
}
