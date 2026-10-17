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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc class ExperienceCurationHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean ExperienceCurations curations;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":t.equals("owner")?"user":"staff_account").claim("role",t.equals("op")?"OPERATOR":t.equals("owner")?"OWNER":"MERCHANT").claim("app_id","operator-account").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"experience_id\":\"01234567-89ab-4cde-8fab-0123456789ab\",\"source_hash\":\""+"a".repeat(64)+"\",\"priority\":1,\"source_note\":\"synthetic source\",\"scope_confirmed\":true,\"valid_from\":\"2027-01-01T00:00:00Z\",\"valid_to\":\"2027-02-01T00:00:00Z\"}";}
 @Test void rolesAndExactSelectorsRejectBeforeSource()throws Exception{mvc.perform(get("/api/community/hot?vehicle_id=1")).andExpect(status().isUnauthorized());mvc.perform(get("/api/community/hot?vehicle_id=1").header("Authorization","Bearer op")).andExpect(status().isForbidden());mvc.perform(get("/api/admin/community/curations").header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("","vehicle_id=0","vehicle_id=1&vehicle_id=2","vehicle_id=1&model_id=1","vehicle_id=1&page=1"))mvc.perform(get("/api/community/hot?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/community/curations?user_id=1").header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(curations);}
 @Test void strictConfirmedBodyAndPermanentKeyAreRequired()throws Exception{for(String b:List.of("{}",body()+"{}",body().replace("\"priority\":1","\"priority\":1.0"),body().replace("\"scope_confirmed\":true","\"scope_confirmed\":false"),body().replace("\"priority\":1","\"priority\":1,\"priority\":2"))){mvc.perform(post("/api/admin/community/curations").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());}mvc.perform(post("/api/admin/community/curations").header("Authorization","Bearer op").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(curations);}
 @Test void noStoreSingleEnvelopeAndFailuresDoNotLeakEvidence()throws Exception{when(curations.curated(any(),eq(1L))).thenReturn(Map.of("state","UNKNOWN"));mvc.perform(get("/api/community/hot?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.state").value("UNKNOWN"));when(curations.list(any(),eq(1))).thenReturn(Map.of("items",List.of()));mvc.perform(get("/api/admin/community/curations").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));when(curations.curated(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));mvc.perform(get("/api/community/hot?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));}
}
