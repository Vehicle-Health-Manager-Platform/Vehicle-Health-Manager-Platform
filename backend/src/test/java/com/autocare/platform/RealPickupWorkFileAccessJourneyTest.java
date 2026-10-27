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
 * Pickup and construction attachment access over real HTTP, object storage and database.
 * Identity, order and payment rows are synthetic fixtures; uploading, pickup submission, dispatch
 * acceptance, protection, reporting, signing and every signed download are real calls.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "JWT_SECRET=synthetic-pickup-work-files-secret-at-least-32-chars","WECHAT_APP_ID=pickup-work-files",
    "UPLOAD_STORAGE_ENABLED=true","UPLOAD_SCAN_ENABLED=false","UPLOAD_ALLOW_INSECURE=true",
    "UPLOAD_SIGNED_URL_SECONDS=5","UPLOAD_CLEANUP_INTERVAL_MS=600000"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealPickupWorkFileAccessJourneyTest {
    static final String RUN=UUID.randomUUID().toString(), ROOT_PASSWORD=UUID.randomUUID().toString();
    static final String USER="test-pickup-files-user", SECRET=UUID.randomUUID().toString(), BUCKET="pickup-work-files";
    static final String APP="pickup-work-files";
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
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withDatabaseName("pickup_work_files")
        .withCommand("--log-bin-trust-function-creators=1").withLabel("autocare.pickup.files.run",RUN);
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(image("autocare-upload-minio-ci","minio",false))
        .withEnv("MINIO_ROOT_USER","test-pickup-files-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
        .withNetwork(NETWORK).withNetworkAliases("pickup-files-minio").withExposedPorts(9000).withCommand("server","/data")
        .withLabel("autocare.pickup.files.run",RUN).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> CLAM=new GenericContainer<>("clamav/clamav:1.4.3_base")
        .withCopyToContainer(Transferable.of(signature()),"/test-db/test.hdb")
        .withCopyToContainer(Transferable.of("Foreground yes\nUser root\nDatabaseDirectory /test-db\nTCPSocket 3310\nTCPAddr 0.0.0.0\nStreamMaxLength 10M\nMaxFileSize 12M\nMaxScanSize 12M\nAlertExceedsMax yes\nPidFile /tmp/clamd.pid\n"),"/test-clamd.conf")
        .withCreateContainerCmdModifier(c->c.withEntrypoint("clamd")).withCommand("--config-file=/test-clamd.conf")
        .withExposedPorts(3310).withLabel("autocare.pickup.files.run",RUN).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
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
        static void daemon(Runnable action){Thread thread=new Thread(action,"pickup-files-loopback-forwarder");thread.setDaemon(true);thread.start();}
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
                .withLabel("autocare.pickup.files.run",RUN).withEnv("MINIO_ENDPOINT","http://pickup-files-minio:9000")
                .withEnv("MINIO_ROOT_USER","test-pickup-files-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
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
        String session=UUID.randomUUID().toString(),appId=("MERCHANT".equals(role)||"STAFF".equals(role))?"merchant-account":APP;
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,type,id,role,appId,merchant==0?null:merchant,binding,UUID.randomUUID().toString());
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("subject_type",type).claim("role",role).claim("app_id",appId).claim("jti",session);
        if(merchant>0)claims.claim("merchant_id",merchant);if(binding!=null)claims.claim("binding_id",binding);
        String value=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();tokens.put(label,value);return value;
    }
    /** kind==null marks setup traffic; only scoped attachment operations become evidence rows. */
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
    JsonNode json(String name,String op,String kind,String role,String method,String path,String body,String key,int expected)throws Exception{
        return call(name,op,kind,role,method,path,body==null?null:body.getBytes(StandardCharsets.UTF_8),body==null?null:"application/json",key,expected);
    }
    JsonNode setup(String name,String role,String method,String path,String body)throws Exception{
        return json(name,null,null,role,method,path,body,UUID.randomUUID().toString(),200);
    }
    JsonNode upload(String name,String role,String path)throws Exception{
        String boundary="autocare-pickup-work-files";
        byte[] body=concat(concat(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evidence.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII),CLEAN),("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return call(name,null,null,role,"POST",path,body,"multipart/form-data; boundary="+boundary,UUID.randomUUID().toString(),200);
    }
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    long shopUpload(String role,String route)throws Exception{return upload("upload",role,route).path("data").path("file_id").asLong();}
    String pickupBody(long order,String code,long baseFile){
        String[] slots={"FRONT","REAR","LEFT","RIGHT","ROOF","DASHBOARD","INTERIOR"};
        var b=new StringBuilder("{\"order_id\":"+order+",\"appointment_code\":\""+code+"\",\"photos\":{");
        for(int i=0;i<slots.length;i++)b.append(i==0?"":",").append('"').append(slots[i]).append("\":").append(baseFile+i);
        return b.append("},\"mileage\":120,\"fuel_level\":\"HALF\",\"damage_status\":\"NONE\",\"damages\":[]}").toString();
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
    @Test void realPickupAndWorkAttachmentPermissionsSignaturesAndOrderIsolation()throws Exception{
        application=app;assertEquals(132,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-pickup-files-owner',1),(2,'synthetic-pickup-files-other',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage,plate_no) VALUES(1,1,100,'SYN-PICKUP-0001'),(2,2,200,'SYN-OTHER-0002')");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic pickup shop','test',1),(2,2,'synthetic other shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-pickup-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-pickup-other','ACTIVE'),(3,1,'TECHNICIAN','synthetic-pickup-tech','ACTIVE'),(4,1,'TECHNICIAN','synthetic-pickup-peer','ACTIVE'),(5,1,'STAFF','synthetic-pickup-staff','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,?,'synthetic-tech',3),(2,?,'synthetic-peer',4)",APP,APP);
        token("owner",1,"user","OWNER",0,null);token("other",2,"user","OWNER",0,null);
        token("merchant",1,"staff_account","MERCHANT",1,null);token("other_merchant",2,"staff_account","MERCHANT",2,null);
        token("staff",5,"staff_account","STAFF",1,null);
        token("technician",3,"staff_account","TECHNICIAN",1,1L);token("peer",4,"staff_account","TECHNICIAN",1,2L);
        String project="{\"project_name\":\"synthetic inspection\"}";
        String appointment="{\"starts_at\":\""+Instant.now()+"\",\"ends_at\":\""+Instant.now().plusSeconds(3600)+"\"}";
        for(long id=1;id<=2;id++)jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,verify_code,project_snapshot,appointment_snapshot) VALUES(?,?,1,1,1,10,10,'PAID',?,?,?)",
            id,"synthetic-pickup-work-"+id,id==1?"012345":"654321",project,appointment);
        String merchantUpload="/api/merchant/files/upload",techUpload="/api/tech/files/upload";
        for(int i=0;i<7;i++)shopUpload("merchant",merchantUpload);
        setup("pickup_submit","merchant","POST","/api/check/pickup/submit",pickupBody(1,"012345",1));
        setup("pickup_confirm","owner","POST","/api/check/pickup/confirm","{\"order_id\":1,\"decision\":\"CONFIRM\"}");
        setup("assign","merchant","POST","/api/merchant/orders/1/assign","{\"technician_id\":3}");
        setup("accept","technician","POST","/api/tech/orders/1/accept","{}");
        assertEquals(8,shopUpload("merchant",merchantUpload));
        setup("protection_submit","merchant","POST","/api/check/protection/upload","{\"order_id\":1,\"items\":[\"SEAT_COVER\",\"STEERING_COVER\"],\"photo_file_id\":8}");
        assertEquals(9,shopUpload("technician",techUpload));
        assertEquals(10,shopUpload("technician",techUpload));
        assertEquals(11,shopUpload("technician",techUpload));
        assertEquals(12,shopUpload("technician",techUpload));
        setup("report_submit","technician","POST","/api/tech/report/submit","{\"order_id\":1,\"process_photos\":[9],\"fault_part_photos\":[10],\"finish_photos\":[11],\"no_fault_parts\":false,\"repair_plan\":\"合成检查与清洁\",\"fault_analysis\":\"未发现需更换故障件\",\"parts_used\":[],\"no_parts\":true,\"work_hours\":1}");
        setup("sign","technician","POST","/api/tech/sign","{\"order_id\":1,\"signature_file_id\":12}");
        // The per-owner upload quota is 10 per minute, so the second pickup is taken by another
        // employee of the same shop; the photos still have to belong to whoever submits.
        for(int i=0;i<7;i++)shopUpload("staff",merchantUpload);
        setup("second_pickup_submit","staff","POST","/api/check/pickup/submit",pickupBody(2,"654321",13));
        check("nineteen_clean_uploads",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE scan_status='CLEAN' AND is_deleted=0",Integer.class)==19
            &&jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class)==19);
        check("pickup_and_work_links_exact",jdbc.queryForObject("SELECT COUNT(*) FROM pickup_check_file",Integer.class)==14
            &&jdbc.queryForObject("SELECT COUNT(DISTINCT file_id) FROM pickup_check_file",Integer.class)==14
            &&"FAULT:1,FINISH:1,PROCESS:1,PROTECTION:1,SIGNATURE:1".equals(jdbc.queryForObject("SELECT GROUP_CONCAT(CONCAT(kind,':',n) ORDER BY kind) FROM (SELECT kind,COUNT(*) n FROM service_evidence_file GROUP BY kind) k",String.class)));
        check("files_owned_by_this_shop_only",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_type='staff_account' AND owner_id IN (1,3,5)",Integer.class)==19
            &&jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_id NOT IN (1,3,5) OR owner_type<>'staff_account'",Integer.class)==0);
        String pickup="/api/check/pickup/{order}/files/{file}/access";
        URI pickupLease=signedDownload(json("pickup_owner_photo",pickup,"normal","owner","GET","/api/check/pickup/1/files/1/access",null,null,200));
        signedDownload(json("pickup_merchant_photo",pickup,"normal","merchant","GET","/api/check/pickup/1/files/2/access",null,null,200));
        signedDownload(json("pickup_own_second_order",pickup,"normal","owner","GET","/api/check/pickup/2/files/13/access",null,null,200));
        json("pickup_cross_order",pickup,"boundary","owner","GET","/api/check/pickup/2/files/1/access",null,null,404);
        json("pickup_unattached_work_file",pickup,"boundary","owner","GET","/api/check/pickup/1/files/8/access",null,null,404);
        json("pickup_query",pickup,"error","owner","GET","/api/check/pickup/1/files/1/access?owner_id=1",null,null,400);
        json("pickup_zero_file",pickup,"error","owner","GET","/api/check/pickup/1/files/0/access",null,null,400);
        json("pickup_anonymous",pickup,"unauthorized","anonymous","GET","/api/check/pickup/1/files/1/access",null,null,401);
        json("pickup_wrong_role",pickup,"unauthorized","technician","GET","/api/check/pickup/1/files/1/access",null,null,403);
        json("pickup_other_owner",pickup,"unauthorized","other","GET","/api/check/pickup/1/files/1/access",null,null,404);
        json("pickup_other_shop",pickup,"unauthorized","other_merchant","GET","/api/check/pickup/1/files/1/access",null,null,404);
        String merchant="/api/merchant/orders/{id}/work/files/{file}/access";
        signedDownload(json("merchant_work_protection",merchant,"normal","merchant","GET","/api/merchant/orders/1/work/files/8/access",null,null,200));
        signedDownload(json("merchant_work_process",merchant,"normal","merchant","GET","/api/merchant/orders/1/work/files/9/access",null,null,200));
        json("merchant_work_pickup_file",merchant,"boundary","merchant","GET","/api/merchant/orders/1/work/files/1/access",null,null,404);
        json("merchant_work_missing_order",merchant,"boundary","merchant","GET","/api/merchant/orders/999999/work/files/8/access",null,null,404);
        json("merchant_work_query",merchant,"error","merchant","GET","/api/merchant/orders/1/work/files/8/access?page=1",null,null,400);
        json("merchant_work_zero_order",merchant,"error","merchant","GET","/api/merchant/orders/0/work/files/8/access",null,null,400);
        json("merchant_work_anonymous",merchant,"unauthorized","anonymous","GET","/api/merchant/orders/1/work/files/8/access",null,null,401);
        json("merchant_work_wrong_role",merchant,"unauthorized","owner","GET","/api/merchant/orders/1/work/files/8/access",null,null,403);
        json("merchant_work_other_shop",merchant,"unauthorized","other_merchant","GET","/api/merchant/orders/1/work/files/8/access",null,null,404);
        String tech="/api/tech/orders/{id}/work/files/{file}/access";
        signedDownload(json("tech_work_protection",tech,"normal","technician","GET","/api/tech/orders/1/work/files/8/access",null,null,200));
        signedDownload(json("tech_work_finish",tech,"normal","technician","GET","/api/tech/orders/1/work/files/11/access",null,null,200));
        json("tech_work_pickup_file",tech,"boundary","technician","GET","/api/tech/orders/1/work/files/1/access",null,null,404);
        json("tech_work_missing_order",tech,"boundary","technician","GET","/api/tech/orders/999999/work/files/8/access",null,null,404);
        json("tech_work_query",tech,"error","technician","GET","/api/tech/orders/1/work/files/8/access?page=1",null,null,400);
        json("tech_work_zero_order",tech,"error","technician","GET","/api/tech/orders/0/work/files/8/access",null,null,400);
        json("tech_work_anonymous",tech,"unauthorized","anonymous","GET","/api/tech/orders/1/work/files/8/access",null,null,401);
        json("tech_work_wrong_role",tech,"unauthorized","owner","GET","/api/tech/orders/1/work/files/8/access",null,null,403);
        json("tech_work_peer",tech,"unauthorized","peer","GET","/api/tech/orders/1/work/files/8/access",null,null,404);
        String owner="/api/order/{id}/work/files/{file}/access";
        signedDownload(json("owner_work_protection",owner,"normal","owner","GET","/api/order/1/work/files/8/access",null,null,200));
        signedDownload(json("owner_work_process",owner,"normal","owner","GET","/api/order/1/work/files/9/access",null,null,200));
        signedDownload(json("owner_work_signature",owner,"normal","owner","GET","/api/order/1/work/files/12/access",null,null,200));
        json("owner_work_cross_order",owner,"boundary","owner","GET","/api/order/1/work/files/13/access",null,null,404);
        json("owner_work_missing_order",owner,"boundary","owner","GET","/api/order/999999/work/files/8/access",null,null,404);
        json("owner_work_query",owner,"error","owner","GET","/api/order/1/work/files/8/access?owner_id=1",null,null,400);
        json("owner_work_zero_file",owner,"error","owner","GET","/api/order/1/work/files/0/access",null,null,400);
        json("owner_work_anonymous",owner,"unauthorized","anonymous","GET","/api/order/1/work/files/8/access",null,null,401);
        json("owner_work_wrong_role",owner,"unauthorized","merchant","GET","/api/order/1/work/files/8/access",null,null,403);
        json("owner_work_other_owner",owner,"unauthorized","other","GET","/api/order/1/work/files/8/access",null,null,404);
        URI unsigned=URI.create(pickupLease.toString().split("\\?")[0]);
        check("unsigned_object_denied",client.send(HttpRequest.newBuilder(unsigned).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        String tampered=pickupLease.toString().replace("X-Amz-Signature=","X-Amz-Signature=0");
        check("tampered_signature_denied",client.send(HttpRequest.newBuilder(URI.create(tampered)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        Thread.sleep(6100);
        check("expired_signature_denied",client.send(HttpRequest.newBuilder(pickupLease).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        check("signature_lease_bounded",leaseBounded);
        check("two_orders_only",jdbc.queryForObject("SELECT COUNT(*) FROM `order`",Integer.class)==2
            &&jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE user_id=2 OR merchant_id=2",Integer.class)==0);
        check("orders_keep_expected_states","PENDING_VERIFY".equals(jdbc.queryForObject("SELECT status FROM `order` WHERE id=1",String.class))
            &&"RECEIVED".equals(jdbc.queryForObject("SELECT status FROM `order` WHERE id=2",String.class)));
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){
            try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("backend/src")||p.toString().endsWith(".java")).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}
        }
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_pickup_work_files_acceptance.py"));
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
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","pickup-work-files-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"checks",CHECKS,"cleanup",CLEANUP,"scope","synthetic identities, order and payment rows; real HTTP uploads, pickup submission, dispatch acceptance, protection, report signing and signed object downloads"));
    }
}
