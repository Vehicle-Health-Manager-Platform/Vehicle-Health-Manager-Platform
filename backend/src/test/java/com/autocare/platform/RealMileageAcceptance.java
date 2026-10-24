package com.autocare.platform;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local acceptance only. Real provider bytes; no provider/result substitutes. */
final class RealMileageAcceptance {
    static final String ID=System.getenv("AUTOCARE_REAL_MILEAGE_RUN_ID");
    static final ObjectMapper JSON=new ObjectMapper();
    static RealMaterialWorkbenchJourneyTest.LoopbackStorage forwarder;
    static JsonNode config;
    static Map<String,Object> proof;
    static final List<Map<String,Object>> cases=new ArrayList<>();
    static boolean enabled(){return ID!=null;}
    static Path work(){assertTrue(ID!=null&&ID.matches("[0-9a-f]{32}"));return Path.of("..",".cache","real-mileage-"+ID);}
    static void configure(DynamicPropertyRegistry r)throws Exception{
        if(!enabled())return;
        assertNull(System.getenv("AUTOCARE_NATIVE_MATERIAL_RUN_ID"));
        config=JSON.readTree(work().resolve("config.private.json").toFile());
        int port=config.path("port").asInt();assertTrue(port>0&&port<65536);
        String token=config.path("token").asText();assertTrue(token.matches("[a-f0-9]{64}"));
        forwarder=new RealMaterialWorkbenchJourneyTest.LoopbackStorage("host.docker.internal",port);
        r.add("MILEAGE_OCR_ENABLED",()->true);r.add("MILEAGE_OCR_URL",()->forwarder.endpoint()+"/recognize");
        r.add("MILEAGE_OCR_SERVICE_TOKEN",()->token);r.add("MILEAGE_OCR_ALLOW_PRIVATE_HTTP",()->true);
    }
    static JsonNode send(int port,String name,String token,String method,String path,byte[] body,String type,String key,int status)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(type!=null)b.header("Content-Type",type);if(key!=null)b.header("Idempotency-Key",key);
        var response=HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),name);assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),name);
        cases.add(Map.of("name",name,"method",method,"path",path,"status",response.statusCode(),"passed",true));
        return JSON.readTree(response.body());
    }
    static JsonNode recognize(int port,String name,String token,String path,Map<String,Object> body,int status)throws Exception{
        return send(port,name,token,"POST",path,JSON.writeValueAsBytes(body),"application/json",null,status);
    }
    static void run(JdbcTemplate db,ObjectMapper mapper,int port,String token,String other)throws Exception{
        byte[] image=Base64.getDecoder().decode(config.path("image").asText());assertTrue(image.length>0&&image.length<4194304);
        String boundary="real-mileage-"+ID;
        var output=new java.io.ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic-mileage.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(image);output.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var uploaded=send(port,"upload",token,"POST","/api/file/upload",output.toByteArray(),"multipart/form-data; boundary="+boundary,UUID.randomUUID().toString(),200);
        long file=uploaded.path("data").path("file_id").asLong();assertTrue(file>0);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM file_object WHERE id=? AND owner_id=991 AND content_type='image/png' AND scan_status='CLEAN' AND is_deleted=0 AND size_bytes=?",Integer.class,file,image.length));
        String route="/api/vehicle/mileage-recognition";
        var body=new LinkedHashMap<String,Object>();body.put("vehicle_id",991);body.put("file_id",file);body.put("consent",true);body.put("consent_version","MILEAGE_OCR_V1");
        recognize(port,"anonymous",null,route,body,401);
        recognize(port,"foreign_owner",other,route,body,404);
        body.put("consent",false);recognize(port,"consent_required",token,route,body,400);body.put("consent",true);
        recognize(port,"query_rejected",token,route+"?owner_id=991",body,400);
        body.put("file_id",999999);recognize(port,"missing_file",token,route,body,404);body.put("file_id",file);
        db.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=?",file);
        recognize(port,"unsafe_file",token,route,body,404);db.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=?",file);
        var data=recognize(port,"real_candidate",token,route,body,200).path("data");
        assertEquals(file,data.path("file_id").asLong());assertEquals(991,data.path("vehicle_id").asLong());
        assertEquals("CANDIDATE",data.path("review_status").asText());assertTrue(data.path("requires_confirmation").asBoolean());
        var recognition=data.path("recognition");assertEquals("archive",recognition.path("scene").asText());assertEquals(1,recognition.path("version").asInt());
        assertEquals("CANDIDATE",recognition.path("status").asText());assertEquals(1,recognition.path("fields").size());
        var field=recognition.path("fields").get(0);assertEquals("mileage",field.path("key").asText());assertEquals("123456",field.path("value").asText());assertTrue(field.path("confidence").asDouble()>=0.9);
        assertEquals(Set.of("vehicle_id","file_id","review_status","requires_confirmation","recognition"),mapper.convertValue(data,Map.class).keySet());
        db.update("UPDATE file_object SET is_deleted=1 WHERE id=?",file);recognize(port,"deleted_file",token,route,body,404);db.update("UPDATE file_object SET is_deleted=0 WHERE id=?",file);
        db.update("UPDATE vehicle SET user_id=2 WHERE id=991");recognize(port,"transferred_vehicle",token,route,body,404);db.update("UPDATE vehicle SET user_id=991 WHERE id=991");
        var access=send(port,"signed_access",token,"GET","/api/file/"+file+"/access",null,null,null,200);
        var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(access.path("data").path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,downloaded.statusCode());assertArrayEquals(image,downloaded.body());
        db.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='user' AND subject_id=991");recognize(port,"revoked_session",token,route,body,401);
        assertEquals(100,db.queryForObject("SELECT current_mileage FROM vehicle WHERE id=991",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=991",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_id=991 AND is_deleted=0",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991",Integer.class));
        proof=new LinkedHashMap<>(Map.of("passed",true,"cases",cases,"signed_download_matches",true,"one_clean_owned_png",true,"no_archive_created",true,"vehicle_mileage_unchanged",true,"no_input_material_created",true,"requires_confirmation",true));
    }
    static void close()throws Exception{
        if(forwarder!=null){forwarder.close();if(proof!=null){proof.put("forwarder_closed",true);JSON.writerWithDefaultPrettyPrinter().writeValue(work().resolve("gateway.json").toFile(),proof);}}
    }
}
