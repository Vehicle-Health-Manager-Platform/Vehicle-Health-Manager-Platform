package com.autocare.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc
class ExperienceCardsHttpTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder; @MockitoBean ExperienceCards service;
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String t=call.getArgument(0); return Jwt.withTokenValue(t).header("alg","HS256").subject("1")
                .claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":"MERCHANT")
                .claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();
        });
    }
    String key() { return UUID.randomUUID().toString(); }
    String body() { return "{\"agree\":true,\"consent_version\":\"experience-v1\"}"; }
    @Test void authAndRoleIsolation() throws Exception {
        mvc.perform(get("/api/experience-cards?vehicle_id=1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/experience-cards?vehicle_id=1").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());
        mvc.perform(post("/api/experience-cards/1/consent").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void strictInputsAndQueryCannotOverrideOwner() throws Exception {
        for(String q:List.of("", "vehicle_id=1&user_id=2", "vehicle_id=1&vehicle_id=2", "vehicle_id=0", "vehicle_id=9007199254740992", "vehicle_id=1&page=0", "vehicle_id=1&page_size=51", "vehicle_id=1&page=1000001", "vehicle_id=1&page=1.5"))
            mvc.perform(get("/api/experience-cards?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        for(String b:List.of("{}", "null", "[]", body()+"{}", body().replace("true","\"true\""), body().replace("true","false"), body().replace("experience-v1","old"), body().replace("true","true,\"agree\":false")))
            mvc.perform(post("/api/experience-cards/1/consent").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/experience-cards/1/withdraw").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/experience-cards/1/consent").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/experience-cards/1/consent?user_id=2").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void readsAndWritesNoStoreAndSafeErrors() throws Exception {
        when(service.list(any(),eq(1L),eq(1),eq(20))).thenReturn(Map.of("items",List.of(),"total",0));
        when(service.change(any(),eq(1L),anyString(),any(),eq(true))).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of())));
        mvc.perform(get("/api/experience-cards?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/experience-cards/1/consent").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(service.change(any(),eq(1L),anyString(),any(),eq(false))).thenThrow(new FulfillmentConflict(45002,"卡片已变更"));
        mvc.perform(post("/api/experience-cards/1/withdraw").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(45002)).andExpect(header().string("Cache-Control","no-store"));
        when(service.list(any(),eq(1L),eq(1),eq(20))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));
        mvc.perform(get("/api/experience-cards?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
