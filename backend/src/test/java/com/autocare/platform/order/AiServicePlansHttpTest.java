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
class AiServicePlansHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean AiServicePlans service;
    @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(call->{String t=call.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":"MERCHANT").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String body(){return "{\"question\":\"异响\",\"requested_project_ids\":[],\"consent_to_provider\":true}";}
    @Test void rolesAndAnonymousDenied()throws Exception{mvc.perform(post("/api/ai/service-plan").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());mvc.perform(post("/api/ai/service-plan").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void strictConsentQuestionIdsAndQueryReject()throws Exception{for(String b:List.of("null","[]",body()+"{}",body().replace("\"question\":\"异响\"","\"question\":\"异响\",\"question\":\"other\""),body().replace("true","false"),body().replace("[]","[1,1]"),body().replace("[]","[0]"),body().replace("[]","[1,2,3,4]"),body().replace("异响","a".repeat(301))))mvc.perform(post("/api/ai/service-plan").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());mvc.perform(get("/api/ai/service-plan/catalog?user_id=1").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void readOnlySingleEnvelopeNoStoreAndSafeDatabaseError()throws Exception{when(service.generate(any(),any())).thenReturn(Map.of("status","MANUAL"));mvc.perform(post("/api/ai/service-plan").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.status").value("MANUAL"));when(service.catalog(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));mvc.perform(get("/api/ai/service-plan/catalog").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("结构化项目建议暂不可用，请稍后重试"));}
}
