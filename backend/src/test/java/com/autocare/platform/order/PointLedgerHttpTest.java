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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointLedgerHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PointLedger ledger;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"day\":\"2026-10-16\",\"policy_id\":1,\"policy_revision\":2}";}
 @Test void ownerScopeAndQueriesRejectForgedEvidence()throws Exception{mvc.perform(get("/api/point/overview")).andExpect(status().isUnauthorized());mvc.perform(get("/api/point/overview").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String q:List.of("owner_id=2","page=0","page=1&page=2","page_size=100"))mvc.perform(get("/api/point/flow?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());for(String b:List.of(body().replace("2}","2,\"eligible\":true}"),body().replace("2026-10-16","2026-02-30"),body()+"{}"))mvc.perform(post("/api/point/check-in").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());verifyNoInteractions(ledger);}
 @Test void readsAreNoStoreAndPageIsPassedToOwnService()throws Exception{when(ledger.overview(any())).thenReturn(Map.of("balance",0,"legacy_state","REVIEW_REQUIRED"));when(ledger.flow(any(),eq(2))).thenReturn(Map.of("items",List.of(),"total",0,"page",2,"page_size",20));mvc.perform(get("/api/point/overview").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.legacy_state").value("REVIEW_REQUIRED"));mvc.perform(get("/api/point/flow?page=2&page_size=20").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));}
 @Test void checkinReturnsOneReceiptEnvelopeAndRequiresKey()throws Exception{mvc.perform(post("/api/point/check-in").header("Authorization","Bearer owner").contentType("application/json").content(body())).andExpect(status().isBadRequest());var json=new com.fasterxml.jackson.databind.ObjectMapper();when(ledger.checkin(any(),anyString(),any())).thenReturn(json.readTree("{\"code\":0,\"data\":{\"event_id\":1,\"day\":\"2026-10-16\",\"status\":\"PENDING\",\"points\":7}}"));mvc.perform(post("/api/point/check-in").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.event_id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());}
}
