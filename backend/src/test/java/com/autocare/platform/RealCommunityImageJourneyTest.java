package com.autocare.platform;

import com.autocare.platform.file.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
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
import java.awt.image.BufferedImage;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "JWT_SECRET=synthetic-community-images-secret-at-least-32-chars","WECHAT_APP_ID=community-images",
    "UPLOAD_STORAGE_ENABLED=true","UPLOAD_SCAN_ENABLED=false","UPLOAD_ALLOW_INSECURE=true",
    "PUBLIC_IMAGES_ENABLED=true","EXPERIENCE_PUBLICATION_ENABLED=true","EXPERIENCE_CARD_ENABLED=true","SERVICE_ARCHIVE_ENABLED=true","SERVICE_ARCHIVE_INTERVAL_MS=250","UPLOAD_CLEANUP_INTERVAL_MS=600000"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealCommunityImageJourneyTest {
    static final String RUN=UUID.randomUUID().toString(), ROOT_PASSWORD=UUID.randomUUID().toString();
    static final String USER="test-community-images-user", SECRET=UUID.randomUUID().toString(), BUCKET="community-images";
    static final String APP="community-images";
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
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withDatabaseName("community_images")
        .withCommand("--log-bin-trust-function-creators=1").withLabel("autocare.community.images.run",RUN);
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(image("autocare-upload-minio-ci","minio",false))
        .withEnv("MINIO_ROOT_USER","test-community-images-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
        .withNetwork(NETWORK).withNetworkAliases("community-images-minio").withExposedPorts(9000).withCommand("server","/data")
        .withLabel("autocare.community.images.run",RUN).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> CLAM=new GenericContainer<>("clamav/clamav:1.4.3_base")
        .withCopyToContainer(Transferable.of(signature()),"/test-db/test.hdb")
        .withCopyToContainer(Transferable.of("Foreground yes\nUser root\nDatabaseDirectory /test-db\nTCPSocket 3310\nTCPAddr 0.0.0.0\nStreamMaxLength 10M\nMaxFileSize 12M\nMaxScanSize 12M\nAlertExceedsMax yes\nPidFile /tmp/clamd.pid\n"),"/test-clamd.conf")
        .withCreateContainerCmdModifier(c->c.withEntrypoint("clamd")).withCommand("--config-file=/test-clamd.conf")
        .withExposedPorts(3310).withLabel("autocare.community.images.run",RUN).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static GenericContainer<?> initializer;
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
                .withLabel("autocare.community.images.run",RUN).withEnv("MINIO_ENDPOINT","http://community-images-minio:9000")
                .withEnv("MINIO_ROOT_USER","test-community-images-root").withEnv("MINIO_ROOT_PASSWORD",ROOT_PASSWORD)
                .withEnv("UPLOAD_MINIO_BUCKET",BUCKET).withEnv("UPLOAD_MINIO_ACCESS_KEY",USER).withEnv("UPLOAD_MINIO_SECRET_KEY",SECRET)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
            initializer.start();
            RESOURCE_IDS.put("initializer",initializer.getContainerId());RESOURCE_IDS.put("minio",MINIO.getContainerId());RESOURCE_IDS.put("clamav",CLAM.getContainerId());RESOURCE_IDS.put("mysql",MYSQL.getContainerId());
            var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            try(var connection=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){
                for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            }
            prepared=true;

        }
        r.add("MYSQL_HOST",MYSQL::getHost);r.add("MYSQL_PORT",MYSQL::getFirstMappedPort);r.add("MYSQL_DATABASE",MYSQL::getDatabaseName);
        r.add("MYSQL_USER",MYSQL::getUsername);r.add("MYSQL_PASSWORD",MYSQL::getPassword);
        r.add("MINIO_ENDPOINT",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));
        r.add("MINIO_PUBLIC_ENDPOINT",()->"http://127.0.0.1:1");
        r.add("UPLOAD_MINIO_ACCESS_KEY",()->USER);r.add("UPLOAD_MINIO_SECRET_KEY",()->SECRET);r.add("UPLOAD_MINIO_BUCKET",()->BUCKET);r.add("UPLOAD_MINIO_REGION",()->"us-east-1");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired JwtEncoder encoder;@Autowired ConfigurableApplicationContext app;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<String,String> tokens=new HashMap<>();
    static ConfigurableApplicationContext application;
    static final Map<String,Object> BINDINGS=new LinkedHashMap<>();
    static final String META="SYNTHETIC_PRIVATE_METADATA";
    byte[] rendered;
    static byte[] picture(int color,boolean metadata)throws Exception{
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);for(int x=0;x<3;x++)for(int y=0;y<2;y++)image.setRGB(x,y,color);
        var writer=ImageIO.getImageWritersByFormatName("png").next();var out=new ByteArrayOutputStream();
        try(var stream=ImageIO.createImageOutputStream(out)){writer.setOutput(stream);var info=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),writer.getDefaultWriteParam());
            if(metadata){var tree=new IIOMetadataNode("javax_imageio_png_1.0");var text=new IIOMetadataNode("tEXt");var entry=new IIOMetadataNode("tEXtEntry");entry.setAttribute("keyword","Comment");entry.setAttribute("value",META);text.appendChild(entry);tree.appendChild(text);info.mergeTree("javax_imageio_png_1.0",tree);}
            writer.write(null,new IIOImage(image,null,info),writer.getDefaultWriteParam());
        }finally{writer.dispose();image.flush();}return out.toByteArray();
    }
    void check(String name,boolean value){assertTrue(value,name);CHECKS.put(name,true);}
    String key(){return UUID.randomUUID().toString();}
    void record(String name,String op,String kind,String role,String method,String path,int status,JsonNode body){
        if(kind==null)return;var row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("operation",op);row.put("kind",kind);row.put("role",role);row.put("method",method);row.put("path",path);row.put("status",status);row.put("passed",true);row.put("body",body==null?null:mapper.convertValue(body,Object.class));EVIDENCE.add(row);
    }
    JsonNode json(String name,String op,String kind,String role,String method,String path,JsonNode body,String requestKey,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));if(!role.equals("anonymous"))b.header("Authorization","Bearer "+Objects.requireNonNull(tokens.get(role)));
        if(requestKey!=null)b.header("Idempotency-Key",requestKey);if(body!=null)b.header("Content-Type","application/json");
        var sent=b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build();var r=client.send(sent,HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,r.statusCode(),name+": "+r.body());assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"),name);
        var data=mapper.readTree(r.body());assertEquals(expected==200?0:expected==400?40001:expected*100,data.path("code").asInt(-1),name);record(name,op,kind,role,sent.method(),path,r.statusCode(),body);return data;
    }
    JsonNode body(String text)throws Exception{return mapper.readTree(text);}
    JsonNode setup(String role,String method,String path,String text)throws Exception{return json("setup",null,null,role,method,path,text==null?null:body(text),key(),200);}
    long upload(byte[] bytes)throws Exception{
        String boundary="community-image-fixture";byte[] multipart=concat(concat(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII),bytes),("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/file/upload")).header("Authorization","Bearer "+tokens.get("owner")).header("Idempotency-Key",key()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,r.statusCode(),r.body());return mapper.readTree(r.body()).path("data").path("file_id").asLong();
    }
    void png(String name,String op,String role,String path)throws Exception{
        var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+tokens.get(role)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,r.statusCode(),name);assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertEquals("image/png",r.headers().firstValue("Content-Type").orElse(""));assertEquals("nosniff",r.headers().firstValue("X-Content-Type-Options").orElse(""));assertArrayEquals(rendered,r.body());record(name,op,"normal",role,"GET",path,r.statusCode(),null);
    }
    JsonNode read(String name,String op,String role,String path,String boundary,String invalid)throws Exception{
        var r=json(name,op,"normal",role,"GET",path,null,null,200);
        json(name+"_boundary",op,"boundary",role,"GET",boundary,null,null,boundary.contains("999999")?404:200);
        json(name+"_invalid",op,"error",role,"GET",invalid,null,null,400);
        json(name+"_anonymous",op,"unauthorized","anonymous","GET",path,null,null,401);
        json(name+"_wrong_role",op,"unauthorized",role.equals("reviewer")?"owner":"merchant","GET",path,null,null,403);return r;
    }
    JsonNode write(String name,String op,String role,String path,JsonNode input,String requestKey)throws Exception{
        var r=json(name,op,"normal",role,"POST",path,input,requestKey,200);assertEquals(r,json(name+"_replay",op,"boundary",role,"POST",path,input,requestKey,200));
        json(name+"_invalid",op,"error",role,"POST",path,body("{}"),key(),400);json(name+"_anonymous",op,"unauthorized","anonymous","POST",path,input,key(),401);json(name+"_wrong_role",op,"unauthorized",role.equals("owner")?"merchant":"owner","POST",path,input,key(),403);return r;
    }
    void publicBytes(JsonNode r){assertArrayEquals(rendered,Base64.getDecoder().decode(r.path("data").path("png_base64").asText()));}
    void awaitCard()throws Exception{long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(jdbc.queryForObject("SELECT COUNT(*) FROM experience_card",Integer.class)!=1){if(System.nanoTime()>end)fail("parent card not generated");Thread.sleep(100);}}
    void sourceFixture(){
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,service_report_ready_at) VALUES(1,'synthetic-community-order',1,1,1,10,10,'COMPLETED','2026-10-09 02:00:00')");
        jdbc.update("INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(1,1,'WECHAT','synthetic-not-a-real-payment','CNY',10,'SUCCEEDED','2026-10-09 02:00:00','synthetic-not-a-supplier-transaction')");
        jdbc.update("INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('WECHAT','synthetic-community-event',1,?,'PAID')","a".repeat(64));
        jdbc.update("INSERT INTO order_redemption(id,order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES(1,1,1,1,1,0,'2026-10-09 02:00:00')");
        jdbc.update("INSERT INTO technician_report(id,order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status,signed_at) VALUES(1,1,3,'[101]','[]','[102]','SYNTHETIC_PRIVATE_REPORT','synthetic','[]',40,1,'2026-10-09 01:00:00')");
        jdbc.update("INSERT INTO service_report_submission(order_id,report_id,no_parts,no_fault_parts,submitted_at) VALUES(1,1,1,1,'2026-10-09 00:50:00')");
        for(int file=101;file<=103;file++){jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(?,'staff_account',3,?,'image/png',100,'CLEAN')",file,"synthetic-parent-"+file);jdbc.update("INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES(1,1,?,?)",file,file==101?"PROCESS":file==102?"FINISH":"SIGNATURE");}
        jdbc.update("INSERT INTO order_review(id,order_id,user_id,redemption_id,rating,content,test_mode,submitted_at) VALUES(1,1,1,1,5,'SYNTHETIC_PRIVATE_REVIEW',0,'2026-10-09 02:10:00')");jdbc.update("INSERT INTO service_archive_job(id,order_id,review_id,payload) VALUES(1,1,1,NULL)");
    }
    @Test void realUploadConsentModerationAndWithdrawal()throws Exception{
        application=app;
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-image-owner',1),(2,'synthetic-image-viewer',1),(3,'synthetic-image-other',1)");
        jdbc.update("INSERT INTO brand(id,name,status) VALUES(1,'synthetic',1)");jdbc.update("INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'synthetic',1)");jdbc.update("INSERT INTO model(id,series_id,year,config_name,status) VALUES(1,1,'2026','A',1),(2,1,'2026','B',1)");
        jdbc.update("INSERT INTO vehicle(id,user_id,model_id,current_mileage) VALUES(1,1,1,100),(2,2,1,100),(3,3,2,100),(4,2,2,100)");
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'synthetic','test',1)");jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-image-shop','ACTIVE'),(3,1,'TECHNICIAN','synthetic-image-tech','ACTIVE')");
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,status,can_review) VALUES(1,'synthetic-reviewer','unused','13900000041','ACTIVE',1),(2,'synthetic-plain','unused','13900000042','ACTIVE',0)");
        token("owner",1,"user","OWNER",0,null);token("viewer",2,"user","OWNER",0,null);token("foreign",3,"user","OWNER",0,null);token("merchant",1,"staff_account","MERCHANT",1,null);token("reviewer",1,"operator_account","OPERATOR",0,null);token("plain",2,"operator_account","OPERATOR",0,null);
        byte[] original=picture(0xff112233,false),copy=picture(0xff77aa44,true);assertTrue(new String(copy,StandardCharsets.ISO_8859_1).contains(META));assertEquals(1,upload(original));assertEquals(2,upload(copy));
        check("two_real_scanned_uploads",jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE id IN (1,2) AND owner_type='user' AND owner_id=1 AND scan_status='CLEAN'",Integer.class)==2);
        sourceFixture();awaitCard();setup("owner","POST","/api/experience-cards/1/consent","{\"agree\":true,\"consent_version\":\"experience-v1\"}");setup("reviewer","POST","/api/admin/experience-cards/1/moderate","{\"revision\":1,\"decision\":\"APPROVE\",\"reason_code\":null}");
        String experience=setup("viewer","GET","/api/community/experiences?vehicle_id=2",null).path("data").path("items").get(0).path("experience_id").asText();BINDINGS.put("experience_id",experience);
        long archive=setup("owner","POST","/api/archive/add","{\"vehicle_id\":1,\"archive_type\":2,\"recorded_date\":\"2026-10-09\",\"title\":\"synthetic image source\",\"notes\":\"\",\"file_ids\":[1],\"input_type\":3}").path("data").path("archive_id").asLong();assertTrue(archive>0);BINDINGS.put("archive_id",archive);
        String sources="/api/owner/community/image-sources",own="/api/owner/community/images",pending="/api/admin/community/images",publicList="/api/community/images";
        String sq=sources+"?experience_id="+experience;
        var selected=read("sources",sources,"owner",sq,sq+"&page=1000000",sq+"&user_id=2");assertEquals(1,selected.path("data").path("total").asInt());assertEquals(archive,selected.path("data").path("items").get(0).path("archive_id").asLong());
        json("sources_foreign",sources,"unauthorized","foreign","GET",sq,null,null,404);
        var selection=mapper.createObjectNode().put("experience_id",experience).put("archive_id",archive).put("source_file_id",1).put("copy_file_id",2);
        var preview=json("preview",own+"/preview","normal","owner","POST",own+"/preview",selection,null,200).path("data");rendered=Base64.getDecoder().decode(preview.path("png_base64").asText());BINDINGS.put("preview_hash",preview.path("preview_hash").asText());
        check("metadata_removed_pixels_preserved",!Arrays.equals(original,rendered)&&!new String(rendered,StandardCharsets.ISO_8859_1).contains(META)&&ImageIO.read(new ByteArrayInputStream(rendered)).getRGB(1,1)==0xff77aa44&&preview.path("width").asInt()==3&&preview.path("height").asInt()==2);
        var second=json("preview_repeat",own+"/preview","boundary","owner","POST",own+"/preview",selection,null,200);assertEquals(preview,second.path("data"));
        json("preview_original_as_copy",own+"/preview","error","owner","POST",own+"/preview",selection.deepCopy().put("copy_file_id",1),null,400);
        json("preview_anonymous",own+"/preview","unauthorized","anonymous","POST",own+"/preview",selection,null,401);json("preview_wrong_role",own+"/preview","unauthorized","merchant","POST",own+"/preview",selection,null,403);json("preview_foreign",own+"/preview","unauthorized","foreign","POST",own+"/preview",selection,null,404);
        var consent=selection.deepCopy().put("preview_hash",preview.path("preview_hash").asText()).put("consent_version","R8C_IMAGE_V1").put("consent",true);String submitKey=key();var receipt=write("submit",own,"owner",own,consent,submitKey);assertEquals(1,receipt.path("data").path("application_id").asLong());
        json("submit_no_consent",own,"error","owner","POST",own,consent.deepCopy().put("consent",false),key(),400);json("submit_changed_hash",own,"error","owner","POST",own,consent.deepCopy().put("preview_hash","0".repeat(64)),key(),409);
        var history=read("own",own,"owner",own,own+"?page=1000000",own+"?page=0");assertEquals(1,history.path("data").path("total").asInt());
        String op=own+"/{id}/preview";publicBytes(read("owner_preview",op,"owner",own+"/1/preview?format=base64",own+"/999999/preview?format=base64",own+"/1/preview?format=bad"));png("owner_png",op,"owner",own+"/1/preview");json("owner_preview_foreign",op,"unauthorized","foreign","GET",own+"/1/preview?format=base64",null,null,404);
        assertEquals(1,read("pending",pending,"reviewer",pending,pending+"?page=1000000",pending+"?page=0").path("data").path("total").asInt());json("pending_no_permission",pending,"unauthorized","plain","GET",pending,null,null,403);
        op=pending+"/{id}/preview";publicBytes(read("review_preview",op,"reviewer",pending+"/1/preview?format=base64",pending+"/999999/preview?format=base64",pending+"/1/preview?format=bad"));png("review_png",op,"reviewer",pending+"/1/preview");json("review_preview_no_permission",op,"unauthorized","plain","GET",pending+"/1/preview?format=base64",null,null,403);
        String list=publicList+"?vehicle_id=2&experience_id="+experience;assertEquals(0,json("unapproved_hidden",publicList,"boundary","viewer","GET",list,null,null,200).path("data").path("items").size());
        op=pending+"/{id}/moderate";String moderate=pending+"/1/moderate",approveKey=key();var approval=body("{\"expected_revision\":1,\"decision\":\"APPROVE\",\"reason_code\":null}");
        jdbc.execute("CREATE TRIGGER fail_public_image_audit BEFORE INSERT ON audit_log FOR EACH ROW BEGIN IF NEW.action='PUBLIC_IMAGE_MODERATE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic image audit failure'; END IF; END");
        try{json("approval_audit_failure",op,"error","reviewer","POST",moderate,approval,approveKey,503);check("approval_failure_rolls_back",jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE id=1 AND status='PENDING' AND revision=1 AND public_id IS NULL",Integer.class)==1&&jdbc.queryForObject("SELECT COUNT(*) FROM public_image_event",Integer.class)==1);}finally{jdbc.execute("DROP TRIGGER fail_public_image_audit");}
        write("approve",op,"reviewer",moderate,approval,approveKey);json("approve_no_permission",op,"unauthorized","plain","POST",moderate,approval,key(),403);
        String publicId=json("approved_binding",own,"normal","owner","GET",own,null,null,200).path("data").path("items").get(0).path("public_id").asText();BINDINGS.put("public_id",publicId);
        var listing=read("public_list",publicList,"viewer",list,publicList+"?vehicle_id=999999&experience_id="+experience,list+"&user_id=2");var item=listing.path("data").path("items").get(0);var fields=new HashSet<String>();item.fieldNames().forEachRemaining(fields::add);check("public_projection_minimal",fields.equals(Set.of("public_id","width","height","published_at"))&&item.path("public_id").asText().equals(publicId));
        op=publicList+"/{publicId}";String bytes=publicList+"/"+publicId+"?vehicle_id=2";publicBytes(read("public_bytes",op,"viewer",bytes+"&format=base64",publicList+"/00000000-0000-4000-8000-000000999999?vehicle_id=2&format=base64",bytes+"&format=bad"));png("public_png",op,"viewer",bytes);
        json("public_wrong_model",op,"unauthorized","viewer","GET",publicList+"/"+publicId+"?vehicle_id=4&format=base64",null,null,404);json("public_foreign_vehicle",op,"unauthorized","owner","GET",bytes+"&format=base64",null,null,404);
        var revoke=body("{\"expected_revision\":2}");String withdrawOp=own+"/{id}/withdraw";write("withdraw",withdrawOp,"owner",own+"/1/withdraw",revoke,key());json("old_public_hidden",op,"boundary","viewer","GET",bytes+"&format=base64",null,null,404);
        json("old_approval_conflict",pending+"/{id}/moderate","boundary","reviewer","POST",moderate,approval,approveKey,409);
        assertEquals(receipt,json("old_submission_receipt",own,"boundary","owner","POST",own,consent,submitKey,200));
        json("submit_second",own,"normal","owner","POST",own,consent,key(),200);json("reject_second",pending+"/{id}/moderate","normal","reviewer","POST",pending+"/2/moderate",body("{\"expected_revision\":1,\"decision\":\"REJECT\",\"reason_code\":\"PRIVACY\"}"),key(),200);json("withdraw_second",withdrawOp,"normal","owner","POST",own+"/2/withdraw",revoke,key(),200);
        json("submit_third",own,"normal","owner","POST",own,consent,key(),200);json("approve_third",pending+"/{id}/moderate","normal","reviewer","POST",pending+"/3/moderate",approval,key(),200);
        String next=json("new_public_binding",own,"normal","owner","GET",own,null,null,200).path("data").path("items").get(0).path("public_id").asText();BINDINGS.put("next_public_id",next);check("new_uuid_never_revives_old",!next.equals(publicId));
        json("old_public_still_hidden",op,"boundary","viewer","GET",bytes+"&format=base64",null,null,404);publicBytes(json("new_public_visible",op,"normal","viewer","GET",publicList+"/"+next+"?vehicle_id=2&format=base64",null,null,200));
        setup("owner","POST","/api/experience-cards/1/withdraw","{}");json("parent_withdrawal_hides_image",op,"unauthorized","viewer","GET",publicList+"/"+next+"?vehicle_id=2&format=base64",null,null,404);
        json("withdraw_after_parent_loss",withdrawOp,"normal","owner","POST",own+"/3/withdraw",revoke,key(),200);publicBytes(json("owner_history_after_withdrawal",own+"/{id}/preview","boundary","owner","GET",own+"/3/preview?format=base64",null,null,200));
        String object=jdbc.queryForObject("SELECT object_key FROM file_object WHERE id=1",String.class);check("private_original_unsigned_denied",client.send(HttpRequest.newBuilder(URI.create("http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000)+"/"+BUCKET+"/"+object)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==403);
        check("three_withdrawn_applications",jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE status='WITHDRAWN' AND revision=3",Integer.class)==3);
        check("nine_events_and_audits",jdbc.queryForObject("SELECT COUNT(*) FROM public_image_event",Integer.class)==9&&jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action IN ('PUBLIC_IMAGE_SUBMIT','PUBLIC_IMAGE_MODERATE','PUBLIC_IMAGE_WITHDRAW')",Integer.class)==9);
        check("only_independent_copy_persisted",jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE source_file_id=1 AND copy_file_id=2 AND consent_version='R8C_IMAGE_V1'",Integer.class)==3);
        for(var png:jdbc.queryForList("SELECT png FROM public_image_application",byte[].class))assertArrayEquals(rendered,png);check("stored_png_bytes_match_preview",true);
        check("parent_approval_and_withdrawal_real",jdbc.queryForObject("SELECT COUNT(*) FROM experience_card WHERE status='WITHDRAWN'",Integer.class)==1);
        check("bindings_from_real_responses",BINDINGS.size()==5);completed=true;
    }
    static String sourceDigest()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
        for(String dir:List.of("backend/src","docs/sql/migrations","deploy/minio","deploy/upload-init")){try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->!dir.equals("docs/sql/migrations")||p.toString().endsWith(".sql")).forEach(files::add);}}
        files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_community_images_acceptance.py"));files.sort(Comparator.comparing(p->root.relativize(p).toString().replace('\\','/')));var digest=MessageDigest.getInstance("SHA-256");for(var file:files){digest.update(root.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(file));}return HexFormat.of().formatHex(digest.digest());
    }
    @AfterAll static void onNormalClose()throws Exception{
        if(application==null){cleanup();return;}application.addApplicationListener(event->{if(event instanceof org.springframework.context.event.ContextClosedEvent closed&&closed.getApplicationContext()==application)try{cleanup();}catch(Exception|AssertionError e){completed=false;}});
    }
    static void cleanup()throws Exception{
        var resources=new LinkedHashMap<String,GenericContainer<?>>();resources.put("initializer",initializer);resources.put("clamav",CLAM);resources.put("minio",MINIO);resources.put("mysql",MYSQL);
        for(var entry:resources.entrySet()){if(entry.getValue()==null){CLEANUP.put(entry.getKey(),true);continue;}var container=entry.getValue();String id=RESOURCE_IDS.getOrDefault(entry.getKey(),container.getContainerId());try{container.stop();if(id!=null)assertThrows(NotFoundException.class,()->DockerClientFactory.instance().client().inspectContainerCmd(id).exec());CLEANUP.put(entry.getKey(),true);}catch(Exception|AssertionError e){CLEANUP.put(entry.getKey(),false);}}
        String id=NETWORK.getId();try{NETWORK.close();assertThrows(NotFoundException.class,()->DockerClientFactory.instance().client().inspectNetworkCmd().withNetworkId(id).exec());CLEANUP.put("network",true);}catch(Exception|AssertionError e){CLEANUP.put("network",false);}assertTrue(CLEANUP.values().stream().allMatch(Boolean::booleanValue));
        if(completed)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target","community-images-evidence.json").toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",EVIDENCE,"bindings",BINDINGS,"checks",CHECKS,"cleanup",CLEANUP));
    }
    String token(String label,long id,String type,String role,long merchant,Long binding){
        String session=UUID.randomUUID().toString(),appId="OPERATOR".equals(role)?"operator-account":("MERCHANT".equals(role)||"STAFF".equals(role))?"merchant-account":APP;
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,type,id,role,appId,merchant==0?null:merchant,binding,UUID.randomUUID().toString());
        var claims=JwtClaimsSet.builder().issuer("vehicle-health-manager").subject(""+id).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
            .claim("subject_type",type).claim("role",role).claim("app_id",appId).claim("jti",session);
        if(merchant>0)claims.claim("merchant_id",merchant);if(binding!=null)claims.claim("binding_id",binding);
        String value=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();tokens.put(label,value);return value;
    }
}
