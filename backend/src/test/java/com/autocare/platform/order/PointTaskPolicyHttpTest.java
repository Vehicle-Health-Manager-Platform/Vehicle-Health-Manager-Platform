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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointTaskPolicyHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PointTaskPolicies policies;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"task_type\":\"CHECK_IN\",\"points\":7,\"source_note\":\"合成测试规则\",\"valid_from\":\"2026-10-16T00:00:00Z\",\"valid_to\":\"2026-11-16T00:00:00Z\"}";}
 @Test void rolesQueriesAndIdsAreStrict()throws Exception{mvc.perform(get("/api/point/tasks")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/point/task-policies").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/point/tasks").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String query:List.of("owner_id=2","page=0","page=1&page=2","page_size=50"))mvc.perform(get(PointTaskPolicies.PATH+"?"+query).header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
 @Test void readsAreNoStoreAndNeverInventEarningAvailability()throws Exception{when(policies.current(any())).thenReturn(Map.of("tasks",List.of(),"earning_available",false,"timezone","Asia/Shanghai"));when(policies.list(any(),eq(1))).thenReturn(Map.of("items",List.of(),"total",0,"page",1,"page_size",20));mvc.perform(get("/api/point/tasks").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.earning_available").value(false));mvc.perform(get(PointTaskPolicies.PATH).header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(jsonPath("$.data.page_size").value(20));}
 @Test void writesRejectUntrustedRulesAndRequireOriginalKey()throws Exception{mvc.perform(post(PointTaskPolicies.PATH).header("Authorization","Bearer op").contentType("application/json").content(body())).andExpect(status().isBadRequest());for(String b:List.of(body().replace("7,","0,"),body().replace("7,","1.5,"),body().replace("CHECK_IN","UNKNOWN"),body().replace("7,","7,\"can_points\":true,"),body()+"{}"))mvc.perform(post(PointTaskPolicies.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());mvc.perform(put(PointTaskPolicies.PATH+"/0/status").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_revision\":1,\"status\":\"PUBLISHED\"}")).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
}
