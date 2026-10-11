package com.autocare.platform.service;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="JWT_SECRET=synthetic-secret-at-least-32-bytes") @AutoConfigureMockMvc
class CatalogGovernanceHttpTest {
    @Autowired MockMvc mvc;@MockitoBean CatalogGovernance service;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String token=c.getArgument(0);return Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("jti","test-session").expiresAt(Instant.now().plusSeconds(900)).claim("subject_type",token.equals("operator")?"operator_account":"user").claim("role",token.equals("operator")?"OPERATOR":"OWNER").claim("app_id",token.equals("operator")?"operator-account":"test-app").build();});}
    String body(){return "{\"name\":\"测试品牌\",\"sort_order\":0,\"source_note\":\"授权测试\"}";}
    @Test void anonymousAndWrongIdentityDenied()throws Exception{mvc.perform(get("/api/admin/catalog/brands")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/catalog/brands").header("Authorization","Bearer owner")).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void strictQueryAndBodyRejectedBeforeService()throws Exception{for(String body:List.of(body()+"{}",body().replace("0","\"0\""),body().replace("\"name\":","\"name\":\"duplicate\",\"name\":"),"{}"))mvc.perform(post("/api/admin/catalog/brands").header("Authorization","Bearer operator").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/catalog/brands").header("Authorization","Bearer operator").param("page","1","2")).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void writesKeepSingleEnvelope()throws Exception{var json=new com.fasterxml.jackson.databind.ObjectMapper();when(service.save(any(),eq("brands"),eq(0L),anyString(),any())).thenReturn(json.readTree("{\"code\":0,\"data\":{\"id\":9},\"request_id\":\"test\"}"));mvc.perform(post("/api/admin/catalog/brands").header("Authorization","Bearer operator").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.id").value(9)).andExpect(jsonPath("$.data.data").doesNotExist());}
}
