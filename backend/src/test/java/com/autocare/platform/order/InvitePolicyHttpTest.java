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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class InvitePolicyHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean InviteRewardPolicies policies;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void rolesAndStrictQueriesAreEnforced()throws Exception{mvc.perform(get("/api/admin/invite/reward-policies")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/invite/reward-policies").header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("user_id=1","page=1&page=2","page_size=100","page=0"))mvc.perform(get("/api/admin/invite/reward-policies?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
 @Test void ownerReadsOnlyPolicyScopeAndNoStore()throws Exception{when(policies.current(any(),eq("OWNER"))).thenReturn(new LinkedHashMap<>(Map.of("state","UNKNOWN","scope","OWNER")));mvc.perform(get("/api/invite/reward-policy?scope=OWNER").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/invite/reward-policy?scope=OWNER&scope=STAFF").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());}
 @Test void createRejectsInjectedRecipientOrDuplicateAndTrailingJson()throws Exception{String b="{\"scope\":\"STAFF\",\"source_note\":\"确认规则\",\"valid_from\":\"2026-10-15T00:00:00Z\",\"valid_to\":\"2026-10-16T00:00:00Z\",\"inviter_coupon_id\":null,\"inviter_publication_revision\":null,\"invitee_coupon_id\":1,\"invitee_publication_revision\":2}";for(String raw:List.of(b+"{}",b.replace("\"scope\":\"STAFF\"","\"scope\":\"STAFF\",\"scope\":\"OWNER\""),b.replace("\"inviter_coupon_id\":null","\"inviter_coupon_id\":1"),b.replace("\"scope\":\"STAFF\"","\"scope\":\"STAFF\",\"staff_user_id\":1")))mvc.perform(post("/api/admin/invite/reward-policies").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
 @Test void statusNeedsUuidAndCannotEnableUnknownState()throws Exception{mvc.perform(put("/api/admin/invite/reward-policies/1/status").header("Authorization","Bearer op").contentType(MediaType.APPLICATION_JSON).content("{\"expected_revision\":1,\"status\":\"PUBLISHED\"}")).andExpect(status().isBadRequest());mvc.perform(put("/api/admin/invite/reward-policies/1/status").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_revision\":1,\"status\":\"ACTIVE\"}")).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
}
