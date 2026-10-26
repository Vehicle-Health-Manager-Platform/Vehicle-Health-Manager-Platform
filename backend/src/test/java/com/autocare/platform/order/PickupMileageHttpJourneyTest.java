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
    @TestConfiguration
    static class Config {
        @Bean @Primary MileageRecognition pickupTestRecognition(ReservationStore db,ObjectMapper mapper)throws Exception{
            var store=mock(SignedObjectStore.class);
            when(store.isPrivate()).thenReturn(true);
            when(store.stat(anyString())).thenReturn(new SignedObjectStore.StoredObject("image/png",100));
            when(store.read(anyString(),eq(4194304))).thenReturn(new byte[100]);
            var answer=mapper.readTree("{\"version\":1,\"scene\":\"mileage\",\"status\":\"CANDIDATE\",\"candidates\":[{\"mileage_km\":12345,\"confidence\":0.95}],\"requires_confirmation\":true,\"image_recognition_performed\":true}");
            return new MileageRecognition(db,store,bytes->answer,true);
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
}
