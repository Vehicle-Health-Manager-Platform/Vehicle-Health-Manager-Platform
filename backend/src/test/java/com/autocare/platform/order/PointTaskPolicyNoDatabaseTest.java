package com.autocare.platform.order;
import java.time.Instant;
import java.util.UUID;
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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointTaskPolicyNoDatabaseTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void legitimateReadsAndWritesAreUnavailableWithoutDatabase()throws Exception{mvc.perform(get("/api/point/tasks").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get(PointTaskPolicies.PATH).header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable());mvc.perform(post(PointTaskPolicies.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"task_type\":\"CHECK_IN\",\"points\":7,\"source_note\":\"合成规则\",\"valid_from\":\"2026-10-16T00:00:00Z\",\"valid_to\":\"2026-11-16T00:00:00Z\"}")).andExpect(status().isServiceUnavailable());}
 @Test void malformedInputIsRejectedBeforeLookingUpDatabase()throws Exception{mvc.perform(get(PointTaskPolicies.PATH+"?owner_id=2").header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(post(PointTaskPolicies.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(put(PointTaskPolicies.PATH+"/1/status").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"status\":\"ACTIVE\",\"expected_revision\":1}")).andExpect(status().isBadRequest());}
}
