package com.autocare.platform.gateway.identity;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc
class OperatorAuthHttpTest {
    @Autowired MockMvc mvc;@MockitoBean OperatorIdentity service;@MockitoBean AuthRateLimiter limits;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").expiresAt(Instant.now().plusSeconds(900)).claim("subject_type",t.equals("operator")?"operator_account":"user").claim("role",t.equals("operator")?"OPERATOR":"OWNER").claim("app_id",OperatorActor.APP).build();});}
    String body(){return "{\"account\":\"synthetic-operator\",\"password\":\"synthetic-password\",\"sms_code\":\"123456\"}";}
    @Test void strictAnonymousLoginUsesServerAuthenticationAndNoStore()throws Exception{
        when(service.login(anyString(),anyString(),anyString())).thenReturn(Map.of("access_token","synthetic"));
        mvc.perform(post("/api/auth/operator/login").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).login("synthetic-operator","synthetic-password","123456");verify(limits).check("operator-ip","127.0.0.1",30,900);
    }
    @Test void malformedUnknownDuplicateTrailingAndLongPasswordRejectBeforeServices()throws Exception{
        for(String b:List.of("{}","null",body()+"{}",body().replace("\"sms_code\":\"123456\"","\"sms_code\":123456"),body().replace("synthetic-password","🙂".repeat(19)),body().replace("\"sms_code\":\"123456\"","\"sms_code\":\"123456\",\"can_review\":true"),body().replace("\"sms_code\":\"123456\"","\"sms_code\":\"123456\",\"sms_code\":\"000000\"")))
            mvc.perform(post("/api/auth/operator/login").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/operator/login?role=OPERATOR").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(service,limits);
    }
    @Test void codeRateLimitsAndProviderCanFailClosed()throws Exception{
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"短信尚未配置")).when(service).code(anyString(),anyString(),isNull());
        mvc.perform(post("/api/auth/operator/code").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"synthetic-operator\",\"password\":\"synthetic-password\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
        verify(limits).check("operator-sms-minute","synthetic-operator",1,60);verify(limits).check("operator-sms-day","synthetic-operator",5,86400);
    }
    @Test void logoutRequiresOperatorAndEmptyBody()throws Exception{
        mvc.perform(post("/api/auth/operator/logout").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/operator/logout").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/operator/logout").header("Authorization","Bearer operator").contentType(MediaType.APPLICATION_JSON).content("{\"subject_id\":2}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/operator/logout").header("Authorization","Bearer operator").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(service).logout(any(OperatorActor.class));
    }
}
