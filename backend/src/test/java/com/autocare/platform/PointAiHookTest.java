package com.autocare.platform;
import com.autocare.platform.ai.*;
import com.autocare.platform.order.PointLedger;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PointAiHookTest {
 final ObjectMapper json=new ObjectMapper();DeepSeekClient ai;PointLedger points;AiChatController controller;
 @BeforeEach @SuppressWarnings("unchecked") void setup(){ai=mock(DeepSeekClient.class);points=mock(PointLedger.class);controller=new AiChatController(ai,mock(ObjectProvider.class));ReflectionTestUtils.setField(controller,"points",points);when(ai.complete(anyString(),anyList(),anyInt())).thenReturn(new DeepSeekClient.Completion("合成答复","model",1,2));}
 Jwt owner(){return Jwt.withTokenValue("owner").header("alg","HS256").subject("1").claim("jti","session").claim("subject_type","user").claim("role","OWNER").expiresAt(Instant.now().plusSeconds(600)).build();}
 @Test void realSuccessPassesOnlyServerSourceFlagToLedger()throws Exception{when(ai.officialEndpoint()).thenReturn(true);controller.chat(owner(),json.readTree("{\"message\":\"测试问题\"}"));verify(points).aiSucceeded(any(VehicleOwner.class),eq("合成答复"),eq("model"),eq(true));}
 @Test void upstreamFailureCannotCaptureSuccess()throws Exception{when(ai.complete(anyString(),anyList(),anyInt())).thenThrow(new IllegalStateException("synthetic failure"));assertThrows(IllegalStateException.class,()->controller.chat(owner(),json.readTree("{\"message\":\"问题\"}")));verifyNoInteractions(points);}
 @Test void missingVehicleContextDegradesWithoutCapturingPointEvidence()throws Exception{controller.chat(owner(),json.readTree("{\"message\":\"问题\",\"vehicle_id\":1}"));verifyNoInteractions(points);}
 @Test void endpointMetadataCannotTreatSubstitutesOrMissingKeysAsOfficial(){assertTrue(new DeepSeekClient("synthetic-key","model","","",json).officialEndpoint());assertFalse(new DeepSeekClient("synthetic-key","model","http://127.0.0.1:1","",json).officialEndpoint());assertFalse(new DeepSeekClient("","model","","",json).officialEndpoint());}
}
