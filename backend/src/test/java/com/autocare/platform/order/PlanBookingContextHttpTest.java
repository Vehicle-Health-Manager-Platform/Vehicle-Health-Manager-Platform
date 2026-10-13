package com.autocare.platform.order;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class PlanBookingContextHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PlanBookingContext service;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(call->{String t=call.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":"MERCHANT").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"merchant_project_id\":1,\"standard_project_id\":1,\"vehicle_id\":1,\"date\":\"2026-10-13\",\"page\":1,\"selected_slot_id\":null}";}
 @Test void ownerOnlyAndStrictBodyQueryBeforeService()throws Exception{mvc.perform(post("/api/order/plan-booking-context").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());mvc.perform(post("/api/order/plan-booking-context").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());for(String b:List.of("null",body()+"{}",body().replace("\"page\":1","\"page\":1,\"page\":2"),body().replace("\"page\":1","\"page\":1000001"),body().replace("\"vehicle_id\":1","\"vehicle_id\":0"),body().replace("null","-1"),body().replace("2026-10-13","bad")))mvc.perform(post("/api/order/plan-booking-context").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());mvc.perform(post("/api/order/plan-booking-context?user_id=1").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(service);}
 @Test void singleEnvelopeNoStoreWithoutWriteKey()throws Exception{when(service.read(any(),any())).thenReturn(Map.of("slots",List.of()));mvc.perform(post("/api/order/plan-booking-context").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.slots").isArray());}
 @Test void safeDatabaseFailure()throws Exception{when(service.read(any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));mvc.perform(post("/api/order/plan-booking-context").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("方案预约暂不可用，请稍后重试"));}
}
