package com.autocare.platform.order;

import com.autocare.platform.file.SignedObjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real TCP HTTP, JWT verification and MySQL; storage/OCR are explicit test doubles. */
@Import(PickupMileageHttpJourneyTest.Config.class)
class PickupMileageHttpJourneyTest extends IsolatedCoreJourneysTest {
    static final java.util.concurrent.atomic.AtomicInteger MODEL_CALLS=new java.util.concurrent.atomic.AtomicInteger();
    @TestConfiguration
    static class Config {
        @Bean @Primary MileageRecognition pickupTestRecognition(ReservationStore db,ObjectMapper mapper)throws Exception{
            var store=mock(SignedObjectStore.class);
            when(store.isPrivate()).thenReturn(true);
            when(store.stat(anyString())).thenReturn(new SignedObjectStore.StoredObject("image/png",100));
            when(store.read(anyString(),eq(4194304))).thenReturn(new byte[100]);
            var answer=mapper.readTree("{\"version\":1,\"scene\":\"mileage\",\"status\":\"CANDIDATE\",\"candidates\":[{\"mileage_km\":12345,\"confidence\":0.95}],\"requires_confirmation\":true,\"image_recognition_performed\":true}");
            return new MileageRecognition(db,store,bytes->{MODEL_CALLS.incrementAndGet();return answer;},true);
        }
    }
    @Test void pickupCandidateUsesCurrentShopSessionAndNeverWritesPickupOrMileage()throws Exception{
        long vehicle=vehicle("粤D45678");
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(505,'synthetic-pickup-http',1,?,1,10,10,'PAID')",vehicle);
        file(50,"staff_account",1);
        String fresh=token(1,"staff_account","MERCHANT","merchant-account",1L,null);
        String path="/api/check/pickup/mileage-recognition";
        var body=Map.of("order_id",505,"file_id",50,"consent",true,"consent_version","PICKUP_MILEAGE_OCR_V1");
        request("POST",path,null,null,body,401);
        request("POST",path,owner,null,body,403);
        request("POST",path,otherMerchant,null,body,404);
        request("POST",path,fresh,null,Map.of("order_id",505,"file_id",50,"consent",false,"consent_version","PICKUP_MILEAGE_OCR_V1"),400);
        var result=request("POST",path,fresh,null,body,200).path("data");
        assertEquals(505,result.path("order_id").asLong());assertTrue(result.path("requires_confirmation").asBoolean());
        assertEquals("12345",result.path("recognition").path("fields").get(0).path("value").asText());
        assertEquals(100,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=?",Integer.class,vehicle));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pickup_check WHERE order_id=505",Integer.class));state(505,"PAID");
        jdbc.update("UPDATE `order` SET status='RECEIVED' WHERE id=505");request("POST",path,fresh,null,body,409);
        jdbc.update("UPDATE `order` SET status='PAID' WHERE id=505");
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",issuedSessions.get(fresh));
        request("POST",path,fresh,null,body,401);
    }

    @Test void mileage_four_kind_http_evidence()throws Exception{
        long v=vehicle("粤D67891");file(51,"user",1);String actor=token(1,"user","OWNER","isolated-journeys",null,null);
        String path="/api/vehicle/mileage-recognition";var e=new RecognitionHttpEvidence(this,path);
        var body=Map.of("vehicle_id",v,"file_id",51,"consent",true,"consent_version","MILEAGE_OCR_V1");
        int vehicles=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class),archives=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class),calls=MODEL_CALLS.get();
        var data=e.send("candidate","normal","owner",path,actor,body,200).path("data");
        assertEquals(v,data.path("vehicle_id").asLong());assertEquals(51,data.path("file_id").asInt());assertTrue(data.path("requires_confirmation").asBoolean());assertEquals("12345",data.path("recognition").path("fields").get(0).path("value").asText());
        assertEquals(data,e.send("repeat","boundary","owner",path,actor,body,200).path("data"));
        e.send("no_consent","error","owner",path,actor,Map.of("vehicle_id",v,"file_id",51,"consent",false,"consent_version","MILEAGE_OCR_V1"),400);
        e.send("query","error","owner",path+"?unexpected=1",actor,body,400);
        e.send("anonymous","unauthorized","anonymous",path,null,body,401);
        e.send("wrong_role","unauthorized","merchant",path,merchant,body,403);
        e.send("foreign_owner","unauthorized","foreign",path,foreign,body,404);
        e.send("foreign_file","unauthorized","owner",path,actor,Map.of("vehicle_id",v,"file_id",20,"consent",true,"consent_version","MILEAGE_OCR_V1"),404);
        assertEquals(calls+2,MODEL_CALLS.get());assertEquals(100,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=?",Integer.class,v));
        assertEquals(vehicles,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));assertEquals(archives,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
        e.finish("mileage-http-evidence.json",Map.of("no_vehicle_write",true,"no_archive_write",true,"mileage_unchanged",true,"candidate_requires_confirmation",true,"rejected_requests_no_model_calls",true),Map.of("vehicle_id",v,"file_id",51L));
    }
    @Test void pickup_four_kind_http_evidence()throws Exception{
        long v=vehicle("粤D67892");file(52,"staff_account",1);
        jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(506,'synthetic-pickup-evidence',1,?,1,10,10,'PAID')",v);
        String actor=token(1,"staff_account","MERCHANT","merchant-account",1L,null),path="/api/check/pickup/mileage-recognition";var e=new RecognitionHttpEvidence(this,path);
        var body=Map.of("order_id",506,"file_id",52,"consent",true,"consent_version","PICKUP_MILEAGE_OCR_V1");
        int vehicles=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class),archives=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class),calls=MODEL_CALLS.get();
        var data=e.send("candidate","normal","merchant",path,actor,body,200).path("data");
        assertEquals(506,data.path("order_id").asInt());assertEquals(52,data.path("file_id").asInt());assertTrue(data.path("requires_confirmation").asBoolean());assertEquals("12345",data.path("recognition").path("fields").get(0).path("value").asText());
        assertEquals(data,e.send("repeat","boundary","merchant",path,actor,body,200).path("data"));
        e.send("no_consent","error","merchant",path,actor,Map.of("order_id",506,"file_id",52,"consent",false,"consent_version","PICKUP_MILEAGE_OCR_V1"),400);
        e.send("query","error","merchant",path+"?unexpected=1",actor,body,400);
        e.send("anonymous","unauthorized","anonymous",path,null,body,401);
        e.send("wrong_role","unauthorized","owner",path,owner,body,403);
        e.send("foreign_shop","unauthorized","other_merchant",path,otherMerchant,body,404);
        e.send("foreign_file","unauthorized","merchant",path,actor,Map.of("order_id",506,"file_id",20,"consent",true,"consent_version","PICKUP_MILEAGE_OCR_V1"),404);
        jdbc.update("UPDATE `order` SET status='RECEIVED' WHERE id=506");
        try{e.send("order_state","boundary","merchant",path,actor,body,409);}finally{jdbc.update("UPDATE `order` SET status='PAID' WHERE id=506");}
        assertEquals(calls+2,MODEL_CALLS.get());state(506,"PAID");assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM pickup_check WHERE order_id=506",Integer.class));
        assertEquals(100,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=?",Integer.class,v));assertEquals(vehicles,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));assertEquals(archives,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
        e.finish("pickup-http-evidence.json",Map.of("no_vehicle_write",true,"no_archive_write",true,"mileage_unchanged",true,"candidate_requires_confirmation",true,"rejected_requests_no_model_calls",true,"no_pickup_write",true,"order_state_preserved",true),Map.of("vehicle_id",v,"file_id",52L,"order_id",506L));
    }
}
