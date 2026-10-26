package com.autocare.platform.order;

import com.autocare.platform.file.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP/JWT/MySQL and production upload logic; scanner/storage/OCR are explicit synthetic doubles. */
@Import(LicenseHttpJourneyTest.Config.class)
class LicenseHttpJourneyTest extends IsolatedCoreJourneysTest {
    static final AtomicInteger MODEL_CALLS=new AtomicInteger();
    @Autowired @Qualifier("licenseRecognition") LicenseRecognition defaultDisabled;
    @Autowired AuthTokens authTokens;
    @TestConfiguration static class Config {
        @Bean @Primary SignedObjectStore syntheticLicenseStore(){return new SignedObjectStore(){
            final Map<String,byte[]> bytes=new ConcurrentHashMap<>();final Map<String,String> types=new ConcurrentHashMap<>();
            public boolean isPrivate(){return true;}
            public void put(String key,String type,byte[] data){types.put(key,type);bytes.put(key,data.clone());}
            public void delete(String key){types.remove(key);bytes.remove(key);}
            public StoredObject stat(String key){return new StoredObject(types.get(key),bytes.get(key).length);}
            public byte[] read(String key,int maximum){byte[] value=bytes.get(key);if(value.length>maximum)throw new IllegalArgumentException();return value.clone();}
            public String signGet(String key,int seconds){return "https://synthetic.invalid/private";}
        };}
        @Bean @Primary VirusScanner syntheticLicenseScanner(){return bytes->VirusScanner.Result.CLEAN;}
        @Bean @Primary LicenseRecognition syntheticLicenseRecognition(ReservationStore db,SignedObjectStore store,ObjectMapper mapper){return new LicenseRecognition(db,store,bytes->{MODEL_CALLS.incrementAndGet();return LicenseTestData.answer(mapper);},true);}
    }
    JsonNode upload(byte[] image,String actor,String requestKey,int expected)throws Exception{
        String boundary="synthetic-license-boundary";
        var output=new ByteArrayOutputStream();output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(image);output.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/file/upload")).header("Content-Type","multipart/form-data; boundary="+boundary).header("Idempotency-Key",requestKey);
        if(actor!=null)builder.header("Authorization","Bearer "+actor);
        var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));return mapper.readTree(response.body());
    }
    @Test void new_owner_upload_recognize_confirm_and_manual_save_preserves_all_authorization()throws Exception{
        MODEL_CALLS.set(0);String path="/api/vehicle/license-recognition";
        var body=new HashMap<String,Object>(Map.of("file_id",1,"purpose","NEW_VEHICLE","consent",true,"consent_version","NEW_VEHICLE_LICENSE_OCR_V1"));
        request("POST",path,null,null,body,401);request("POST",path,merchant,null,body,403);request("POST",path,technician,null,body,403);request("POST",path,operator,null,body,403);
        var binding=authTokens.binding("synthetic-license-binding","isolated-journeys");request("POST",path,binding,null,body,403);
        // Real bytes and multipart transport. Image parsing/inference itself is a declared provider double here.
        byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        upload(image,merchant,key(),403);String uploadKey=key();var uploaded=upload(image,owner,uploadKey,200);assertEquals(uploaded,upload(image,owner,uploadKey,200));long fileId=uploaded.path("data").path("file_id").asLong();assertTrue(fileId>0);body.put("file_id",fileId);
        request("POST",path,foreign,null,body,404);var wrong=new HashMap<>(body);wrong.put("consent",false);request("POST",path,owner,null,wrong,400);
        request("POST",path+"?file_id="+fileId,owner,null,body,400);
        var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+owner).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body).replace("}",",\"consent\":true}"))).build();assertEquals(400,http.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(0,MODEL_CALLS.get());
        var current=new VehicleOwner(1,issuedSessions.get(owner),Instant.now().plusSeconds(600));var disabled=assertThrows(ResponseStatusException.class,()->defaultDisabled.recognize(current,LicenseRecognition.input(mapper.valueToTree(body).toString())));assertEquals(503,disabled.getStatusCode().value());assertEquals(0,MODEL_CALLS.get());
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class);int archives=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class);
        var result=request("POST",path,owner,null,body,200).path("data");assertEquals(1,MODEL_CALLS.get());assertEquals("CANDIDATE",result.path("review_status").asText());assertTrue(result.path("requires_confirmation").asBoolean());assertFalse(result.has("model_id"));assertFalse(result.toString().contains("synthetic.png"));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));assertEquals(archives,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
        String plate=result.path("fields").get(0).path("candidates").get(0).path("value").asText(),vin=result.path("fields").get(1).path("candidates").get(0).path("value").asText();
        var saved=request("POST","/api/vehicle/add",owner,key(),Map.of("add_type",4,"model_id",1,"current_mileage",42,"plate_no",plate,"vin",vin),200).path("data");long vehicle=saved.path("vehicle_id").asLong();assertTrue(vehicle>0);assertEquals(42,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=?",Integer.class,vehicle));assertEquals(1,jdbc.queryForObject("SELECT model_id FROM vehicle WHERE id=?",Integer.class,vehicle));
        var fresh=token(1,"user","OWNER","isolated-journeys",null,null);jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",issuedSessions.get(fresh));request("POST",path,fresh,null,body,401);
    }
}
