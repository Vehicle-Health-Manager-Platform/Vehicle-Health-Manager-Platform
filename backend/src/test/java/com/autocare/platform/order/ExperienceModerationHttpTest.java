package com.autocare.platform.order;

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
class ExperienceModerationHttpTest {
    @Autowired MockMvc mvc;@MockitoBean ExperienceModeration service;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").expiresAt(Instant.now().plusSeconds(900)).claim("subject_type",t.equals("operator")?"operator_account":t.equals("owner")?"user":"staff_account").claim("role",t.equals("operator")?"OPERATOR":t.equals("owner")?"OWNER":"MERCHANT").claim("app_id","operator-account").build();});}
    String body(){return "{\"revision\":1,\"decision\":\"APPROVE\",\"reason_code\":null}";}
    String key(){return UUID.randomUUID().toString();}
    @Test void roleIsolationAndAnonymousReject()throws Exception{
        mvc.perform(get("/api/admin/experience-cards")).andExpect(status().isUnauthorized());
        for(String t:List.of("owner","merchant"))mvc.perform(get("/api/admin/experience-cards").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
        mvc.perform(get("/api/community/experiences?vehicle_id=1").header("Authorization","Bearer operator")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictDecisionAndRevisionNoIdentityOverride()throws Exception{
        for(String b:List.of("{}",body()+"{}",body().replace("\"revision\":1","\"revision\":0"),body().replace("\"revision\":1","\"revision\":1.0"),body().replace("\"revision\":1","\"revision\":1,\"revision\":2"),body().replace("null","\"VIN\""),body().replace("APPROVE","UNKNOWN"),body().replace("APPROVE","REJECT")))
            mvc.perform(post("/api/admin/experience-cards/1/moderate").header("Authorization","Bearer operator").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/experience-cards/1/moderate?user_id=2").header("Authorization","Bearer operator").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void strictVehicleCursorAndPagination()throws Exception{
        for(String q:List.of("", "vehicle_id=0", "vehicle_id=1&cursor=0", "vehicle_id=1&cursor=9007199254740992", "vehicle_id=1&vehicle_id=2", "vehicle_id=1&user_id=2"))mvc.perform(get("/api/community/experiences?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/experience-cards?page_size=51").header("Authorization","Bearer operator")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void noStoreReadsSafeConflictAndDependencyErrors()throws Exception{
        when(service.pending(any(),eq(1),eq(20))).thenReturn(Map.of("items",List.of(),"total",0));when(service.experiences(any(),eq(1L),isNull())).thenReturn(Map.of("items",List.of()));
        mvc.perform(get("/api/admin/experience-cards").header("Authorization","Bearer operator")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/community/experiences?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(service.moderate(any(),eq(1L),anyString(),any())).thenThrow(new FulfillmentConflict(45003,"审核版本变化"));mvc.perform(post("/api/admin/experience-cards/1/moderate").header("Authorization","Bearer operator").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(45003));
        when(service.pending(any(),eq(1),eq(20))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));mvc.perform(get("/api/admin/experience-cards").header("Authorization","Bearer operator")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
