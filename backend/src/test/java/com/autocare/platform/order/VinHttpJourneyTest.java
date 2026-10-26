package com.autocare.platform.order;

import com.autocare.platform.vehicle.vin.VinDecoder;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP + production controller/service/JWT + MySQL, explicit synthetic provider only. */
@Import(VinHttpJourneyTest.Config.class)
@TestPropertySource(properties={"VIN_DECODE_ENABLED=true","VIN_DECODER_PROVIDER=NHTSA_VPIC"})
class VinHttpJourneyTest extends IsolatedCoreJourneysTest {
    @TestConfiguration static class Config {
        @Bean @Primary VinDecoder syntheticVinDecoder(){return (vin,year,consent)->JdbcVinRecognitionTest.ANSWER;}
    }
    @Test void owner_candidate_then_manual_choice_creates_vehicle_and_preserves_existing_authorization()throws Exception{
        String path="/api/vehicle/vin-decode";
        var body=Map.of("vin","LTEST223456789012","consent",true,"consent_version","VIN_VPIC_V1");
        request("POST",path,null,null,body,401);request("POST",path,merchant,null,body,403);request("POST",path,technician,null,body,403);
        request("POST",path,owner,null,Map.of("vin",JdbcVinRecognitionTest.VIN,"consent",false,"consent_version","VIN_VPIC_V1"),400);
        request("POST",path+"?vin=forbidden",owner,null,body,400);
        var duplicate=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+owner).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JdbcVinRecognitionTest.RAW.replace("}",",\"consent\":true}"))).build();
        assertEquals(400,http.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode());
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class);
        var result=request("POST",path,owner,null,body,200).path("data");
        assertEquals("CANDIDATE",result.path("status").asText());assertTrue(result.path("requires_confirmation").asBoolean());assertFalse(result.has("model_id"));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));
        var saved=request("POST","/api/vehicle/add",owner,UUID.randomUUID().toString(),Map.of("add_type",4,"model_id",1,"current_mileage",42,"plate_no","粤V12345","vin",result.path("vin").asText()),200).path("data");
        long id=saved.path("vehicle_id").asLong();assertTrue(id>0);assertEquals(42,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("SELECT model_id FROM vehicle WHERE id=?",Integer.class,id));
        String temporary=token(2,"user","OWNER","isolated-journeys",null,null);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",issuedSessions.get(temporary));request("POST",path,temporary,null,body,401);
    }
}
