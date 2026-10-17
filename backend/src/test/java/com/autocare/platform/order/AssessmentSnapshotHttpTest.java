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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class AssessmentSnapshotHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean AssessmentSnapshots snapshots;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op");return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":"user").claim("role",op?"OPERATOR":"OWNER").claim("app_id",op?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"merchant_id\":1,\"period_month\":\"2026-10\",\"expected_revision\":0}";}
 @Test void rolesSelectorsAndExactBodiesAreCheckedBeforeService()throws Exception{mvc.perform(get(AssessmentSnapshots.PATH)).andExpect(status().isUnauthorized());mvc.perform(get(AssessmentSnapshots.PATH).header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("merchant_id=2","page=0","page=1&page=2","page_size=100"))mvc.perform(get(AssessmentSnapshots.PATH+"?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());for(String b:List.of(body().replace("2026-10","2026-13"),body().replace(":0",":-1"),body().replace("\"merchant_id\":1,","\"merchant_id\":1,\"score\":100,"),body()+"{}"))mvc.perform(post(AssessmentSnapshots.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());mvc.perform(get(AssessmentSnapshots.PATH+"/1?merchant_id=2").header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(snapshots);}
 @Test void listAndFactPagesAreNoStoreAndCreateHasOneReceiptEnvelope()throws Exception{var page=Map.<String,Object>of("items",List.of(),"total",0,"page",1,"page_size",20);when(snapshots.list(any(),eq(1))).thenReturn(page);when(snapshots.facts(any(),eq(2L),eq(1))).thenReturn(page);for(String path:List.of(AssessmentSnapshots.PATH,AssessmentSnapshots.PATH+"/2/facts"))mvc.perform(get(path).header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.total").value(0));when(snapshots.create(any(),anyString(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"code\":0,\"data\":{\"id\":1,\"summary\":{\"state\":\"UNKNOWN\",\"total_score\":null}}}"));mvc.perform(post(AssessmentSnapshots.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());}
}
