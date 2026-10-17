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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class InviteDiagnosisHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean InviteDiagnosisPolicies diagnosis;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void rolesAndNoClientEntitlementInputs()throws Exception{mvc.perform(get("/api/invite/diagnosis-eligibility")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/invite/diagnosis-policies").header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("user_id=2","eligible=true","month=2026-10","page=1"))mvc.perform(get("/api/invite/diagnosis-eligibility?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(diagnosis);}
 @Test void currentIsNoStoreReadOnlyAndPolicyWriteRequiresUuid()throws Exception{when(diagnosis.current(any())).thenReturn(Map.of("state","UNKNOWN"));mvc.perform(get("/api/invite/diagnosis-eligibility").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post("/api/admin/invite/diagnosis-policies").header("Authorization","Bearer op").contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(post("/api/invite/diagnosis-eligibility").header("Authorization","Bearer owner").contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());}
}
