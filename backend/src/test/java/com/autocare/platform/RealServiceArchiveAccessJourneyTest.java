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
    "UPLOAD_SIGNED_URL_SECONDS=10","UPLOAD_CLEANUP_INTERVAL_MS=600000"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealServiceArchiveAccessJourneyTest {
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
    static final String CAND="/api/order/{id}/archive-grants",GRANT=CAND+"/{archive}";
    static final String PREVIEW=GRANT+"/files/{file}/preview",MC="/api/merchant/orders/{id}/service-context",TC="/api/tech/orders/{id}/service-context";
    JsonNode readSet(String name,String op,String role,String path,String missing)throws Exception{
        var r=json(name,op,"normal",role,"GET",path,null,null,200);
        json(name+"_missing",op,"boundary",role,"GET",missing,null,null,404);
        json(name+"_query",op,"error",role,"GET",path+"?owner_id=2",null,null,400);
        json(name+"_anonymous",op,"unauthorized","anonymous","GET",path,null,null,401);
        json(name+"_wrong_role",op,"unauthorized",role.equals("owner")?"merchant":"owner","GET",path,null,null,403);return r;
    }
    JsonNode writeSet(String name,String op,String role,String method,String path,String body,String key)throws Exception{
        var r=json(name,op,"normal",role,method,path,body,key,200);
        assertEquals(r,json(name+"_replay",op,"boundary",role,method,path,body,key,200));
        json(name+"_invalid",op,"error",role,method,path,"{}",UUID.randomUUID().toString(),400);
        json(name+"_anonymous",op,"unauthorized","anonymous",method,path,body,key,401);
        json(name+"_wrong_role",op,"unauthorized",role.equals("owner")?"merchant":"owner",method,path,body,key,403);return r;
    }
    String grantBody(long revision,String version){return mapper.createObjectNode().put("status","ACTIVE").put("expected_revision",revision).put("source_version",version).toString();}
    String currentVersion(JsonNode data){for(var item:data.path("data").path("records").path("items"))if(item.path("record").path("archive_id").asLong()==1)return item.path("source_version").asText();throw new AssertionError("missing candidate");}
    void privacy(JsonNode data){for(String key:List.of("owner_id","user_id","vehicle_id","phone","vin","plate_no","object_key","source_version","provider_name"))assertFalse(data.toString().contains("\""+key+"\""),key);assertFalse(data.toString().contains("PRIVATE_UNSELECTED"));}
    @Test void realServiceConsentContextAndSignedAttachments()throws Exception{
        application=app;assertEquals(131,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-service-owner',1),(2,'synthetic-service-other',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage,plate_no,vin) VALUES(1,1,100,'PRIVATE_PLATE','PRIVATE_VIN'),(2,2,200,'PRIVATE_OTHER','PRIVATE_OTHER_VIN')");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic shop','test',1),(2,2,'synthetic other shop','test',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-shop','ACTIVE'),(2,2,'MERCHANT','synthetic-other','ACTIVE'),(3,1,'TECHNICIAN','synthetic-tech','ACTIVE'),(4,1,'TECHNICIAN','synthetic-peer','ACTIVE')");
        jdbc.update("INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(1,'upload-journey','synthetic-tech',3),(2,'upload-journey','synthetic-peer',4)");
        token("owner",1,"user","OWNER",0,null);token("other",2,"user","OWNER",0,null);token("merchant",1,"staff_account","MERCHANT",1,null);token("other_merchant",2,"staff_account","MERCHANT",2,null);token("technician",3,"staff_account","TECHNICIAN",1,1L);token("peer",4,"staff_account","TECHNICIAN",1,2L);
        // Explicit synthetic accepted task, never represented as a real booking/payment or dispatch.
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,assigned_at,project_snapshot) VALUES(1,'synthetic-service-task',1,1,1,10,10,'IN_SERVICE',UTC_TIMESTAMP(),?)","{\"project_name\":\"synthetic inspection\",\"phone\":\"PRIVATE_CONTACT\",\"vin\":\"PRIVATE_VIN\"}");
        jdbc.update("INSERT INTO technician_assignment(order_id,merchant_id,technician_id,assigned_by,status,accepted_at) VALUES(1,1,3,1,'ACCEPTED',UTC_TIMESTAMP())");
        String uploadKey=UUID.randomUUID().toString();var f=upload("owner_upload","normal","owner","/api/file/upload",CLEAN,uploadKey,200);assertEquals(1,f.path("data").path("file_id").asInt());
        assertEquals(f,upload("owner_upload_replay","boundary","owner","/api/file/upload",CLEAN,uploadKey,200));upload("owner_upload_invalid","error","owner","/api/file/upload",new byte[]{1},UUID.randomUUID().toString(),400);upload("owner_upload_anonymous","unauthorized","anonymous","/api/file/upload",CLEAN,uploadKey,401);upload("owner_upload_wrong_role","unauthorized","merchant","/api/file/upload",CLEAN,uploadKey,403);
        String archive="{\"vehicle_id\":1,\"archive_type\":1,\"recorded_date\":\"2026-10-19\",\"mileage\":100,\"title\":\"synthetic selected archive\",\"notes\":\"selected record only\",\"file_ids\":[1]}";
        writeSet("archive_create","/api/archive/add","owner","POST","/api/archive/add",archive,UUID.randomUUID().toString());
        json("archive_unselected","/api/archive/add","boundary","owner","POST","/api/archive/add",archive.replace("selected record only","PRIVATE_UNSELECTED"),UUID.randomUUID().toString(),200);
        String c="/api/order/1/archive-grants",g=c+"/1",preview=g+"/files/1/preview",mc="/api/merchant/orders/1/service-context",tc="/api/tech/orders/1/service-context",mf=mc+"/archives/1/files/1/access",tf=tc+"/archives/1/files/1/access";
        var candidates=readSet("candidates",CAND,"owner",c,"/api/order/999999/archive-grants");assertEquals(2,candidates.path("data").path("records").path("total").asInt());String v1=currentVersion(candidates);assertTrue(v1.matches("[0-9a-f]{64}"));
        signedDownload(readSet("preview",PREVIEW,"owner",preview,g+"/files/999999/preview"));
        var merchant=readSet("merchant_context",MC,"merchant",mc,"/api/merchant/orders/999999/service-context");var tech=readSet("tech_context",TC,"technician",tc,"/api/tech/orders/999999/service-context");privacy(merchant);privacy(tech);
        check("no_default_grants",merchant.path("data").path("archives").path("total").asInt()==0&&jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant",Integer.class)==0);
        json("ungranted_merchant_file",MC+"/archives/{archive}/files/{file}/access","unauthorized","merchant","GET",mf,null,null,404);json("ungranted_tech_file",TC+"/archives/{archive}/files/{file}/access","unauthorized","technician","GET",tf,null,null,404);
        json("foreign_candidates",CAND,"unauthorized","other","GET",c,null,null,404);json("foreign_preview",PREVIEW,"unauthorized","other","GET",preview,null,null,404);json("foreign_shop_context",MC,"unauthorized","other_merchant","GET",mc,null,null,404);json("unassigned_peer_context",TC,"unauthorized","peer","GET",tc,null,null,404);
        String activeKey=UUID.randomUUID().toString(),active=grantBody(0,v1);var receipt=writeSet("grant_activate",GRANT,"owner","PUT",g,active,activeKey);
        json("grant_changed_intent",GRANT,"error","owner","PUT",g,grantBody(1,v1),activeKey,400);json("grant_foreign_owner",GRANT,"unauthorized","other","PUT",g,active,UUID.randomUUID().toString(),404);json("grant_stale_revision",GRANT,"boundary","owner","PUT",g,active,UUID.randomUUID().toString(),409);json("grant_write_query",GRANT,"error","owner","PUT",g+"?owner_id=2",active,UUID.randomUUID().toString(),400);
        signedDownload(readSet("merchant_file",MC+"/archives/{archive}/files/{file}/access","merchant",mf,mc+"/archives/1/files/999999/access"));signedDownload(readSet("tech_file",TC+"/archives/{archive}/files/{file}/access","technician",tf,tc+"/archives/1/files/999999/access"));
        var shared=json("shared_context",MC,"normal","merchant","GET",mc,null,null,200);privacy(shared);check("only_selected_archive_shared",shared.path("data").path("archives").path("total").asInt()==1&&shared.path("data").path("archives").path("items").get(0).path("archive_id").asInt()==1);
        json("unselected_archive_file",MC+"/archives/{archive}/files/{file}/access","unauthorized","merchant","GET",mc+"/archives/2/files/1/access",null,null,404);
        String revoke="{\"status\":\"REVOKED\",\"expected_revision\":1}",revokeKey=UUID.randomUUID().toString();
        jdbc.execute("CREATE TRIGGER fail_service_consent BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'");
        try{json("grant_audit_failure",GRANT,"error","owner","PUT",g,revoke,revokeKey,503);check("audit_failure_rolls_back_grant",jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant WHERE status='ACTIVE' AND revision=1",Integer.class)==1);}finally{jdbc.execute("DROP TRIGGER fail_service_consent");}
        writeSet("grant_revoke",GRANT,"owner","PUT",g,revoke,revokeKey);
        assertEquals(receipt,json("old_receipt_after_revoke",GRANT,"boundary","owner","PUT",g,active,activeKey,200));
        json("revoked_merchant_file",MC+"/archives/{archive}/files/{file}/access","unauthorized","merchant","GET",mf,null,null,404);json("revoked_tech_file",TC+"/archives/{archive}/files/{file}/access","unauthorized","technician","GET",tf,null,null,404);
        check("old_receipt_never_revives_grant",jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant WHERE status='REVOKED' AND revision=2",Integer.class)==1);
        writeSet("grant_reactivate",GRANT,"owner","PUT",g,grantBody(2,v1),UUID.randomUUID().toString());
        String structured="{\"expected_revision\":0,\"fields\":{\"service_items\":[\"synthetic updated service\"],\"provider_name\":\"PRIVATE_PROVIDER\"}}";
        writeSet("structured_save","/api/archive/{id}/structured","owner","PUT","/api/archive/1/structured",structured,UUID.randomUUID().toString());
        var changed=json("source_changed_candidates",CAND,"boundary","owner","GET",c,null,null,200);String v2=currentVersion(changed);check("source_hash_changes_with_fields",!v1.equals(v2));
        var unavailable=json("source_unavailable_context",MC,"boundary","merchant","GET",mc,null,null,200);check("changed_source_hidden",unavailable.path("data").path("archives").path("items").get(0).path("record").isNull());
        json("changed_source_file",MC+"/archives/{archive}/files/{file}/access","unauthorized","merchant","GET",mf,null,null,404);json("old_source_reconfirm",GRANT,"boundary","owner","PUT",g,grantBody(3,v1),UUID.randomUUID().toString(),409);
        writeSet("grant_reconfirm",GRANT,"owner","PUT",g,grantBody(3,v2),UUID.randomUUID().toString());
        var latest=json("updated_context",TC,"normal","technician","GET",tc,null,null,200);privacy(latest);check("updated_safe_fields_visible",latest.toString().contains("synthetic updated service")&&!latest.toString().contains("PRIVATE_PROVIDER"));
        // SQL simulates independent dispatch/account/order changes, not a claim of HTTP redispatch.
        jdbc.update("UPDATE technician_assignment SET technician_id=4,status='ASSIGNED',accepted_at=NULL WHERE order_id=1");
        json("former_tech_context",TC,"unauthorized","technician","GET",tc,null,null,404);json("former_tech_file",TC+"/archives/{archive}/files/{file}/access","unauthorized","technician","GET",tf,null,null,404);json("peer_not_accepted",TC,"unauthorized","peer","GET",tc,null,null,404);
        jdbc.update("UPDATE technician_assignment SET status='ACCEPTED',accepted_at=UTC_TIMESTAMP() WHERE order_id=1");privacy(json("peer_context",TC,"normal","peer","GET",tc,null,null,200));signedDownload(json("peer_file",TC+"/archives/{archive}/files/{file}/access","normal","peer","GET",tf,null,null,200));check("current_assignment_required",true);
        jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=1");json("transferred_vehicle_context",MC,"unauthorized","merchant","GET",mc,null,null,404);jdbc.update("UPDATE vehicle SET user_id=1 WHERE id=1");check("vehicle_owner_rechecked",true);
        jdbc.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=1");json("infected_file_denied",MC+"/archives/{archive}/files/{file}/access","unauthorized","merchant","GET",mf,null,null,404);jdbc.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=1");check("scan_status_rechecked",true);
        var lease=json("lease_before_close",TC+"/archives/{archive}/files/{file}/access","boundary","peer","GET",tf,null,null,200);URI uri=URI.create(lease.path("data").path("url").asText());signedDownload(lease);
        jdbc.update("UPDATE `order` SET status='CLOSED',closed_at=UTC_TIMESTAMP() WHERE id=1");
        json("closed_merchant_context",MC,"unauthorized","merchant","GET",mc,null,null,404);json("closed_peer_context",TC,"unauthorized","peer","GET",tc,null,null,404);json("closed_peer_file",TC+"/archives/{archive}/files/{file}/access","unauthorized","peer","GET",tf,null,null,404);json("closed_old_grant_receipt",GRANT,"unauthorized","owner","PUT",g,active,activeKey,404);
        check("closed_task_denies_new_access",true);signedDownload(lease);check("issued_bearer_url_has_bounded_lease",true);
        signedDownload(json("owner_history_preview",PREVIEW,"boundary","owner","GET",preview,null,null,200));
        writeSet("grant_final_revoke",GRANT,"owner","PUT",g,"{\"status\":\"REVOKED\",\"expected_revision\":4}",UUID.randomUUID().toString());
        Thread.sleep(11100);check("old_url_expires_naturally",client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        check("one_final_revoked_grant",jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant WHERE status='REVOKED' AND revision=5 AND source_version IS NULL",Integer.class)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant",Integer.class)==1);
        check("exact_five_grant_audits",jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_ARCHIVE_GRANT'",Integer.class)==5);
        check("one_scanned_upload_two_archives",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE scan_status='CLEAN'",Integer.class)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM upload_request WHERE state='SUCCEEDED'",Integer.class)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class)==2);
        check("grant_audit_contains_no_source_text",jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='ORDER_ARCHIVE_GRANT' AND (after_state LIKE '%PRIVATE%' OR after_state LIKE '%selected record%')",Integer.class)==0);
        check("no_payment_or_rewards",jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class)==0&&jdbc.queryForObject("SELECT COUNT(*) FROM point_task_event",Integer.class)==0);
        completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){
            try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}
        }
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_service_archive_acceptance.py"));
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
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","service-archive-access-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"checks",CHECKS,"cleanup",CLEANUP,"scope","synthetic identities; real MinIO and ClamAV with synthetic signatures; no production definitions or external supplier"));
    }
}
