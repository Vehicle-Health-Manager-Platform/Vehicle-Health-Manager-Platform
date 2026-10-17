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
class LicensedKnowledgeHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean LicensedKnowledge knowledge;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id","operator-account").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void searchRejectsAnonymousWrongRoleAndExtraSelectors()throws Exception{mvc.perform(get("/api/knowledge/search?vehicle_id=1&q=x")).andExpect(status().isUnauthorized());mvc.perform(get("/api/knowledge/search?vehicle_id=1&q=x").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String q:List.of("q=x","vehicle_id=0&q=x","vehicle_id=1&q=x&q=y","vehicle_id=1&q=x&license_reference=private","vehicle_id=1&q=x&cursor=0"))mvc.perform(get("/api/knowledge/search?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(knowledge);}
 @Test void writesRequireExactPolicyBodyKeyAndOperatorScope()throws Exception{mvc.perform(post("/api/admin/knowledge/documents").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());mvc.perform(post("/api/admin/knowledge/documents").header("Authorization","Bearer op").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());for(String b:List.of("{}","{\"expected_revision\":1,\"status\":\"PUBLISHED\",\"body\":\"extra\"}","{\"expected_revision\":1,\"status\":\"PUBLISHED\",\"status\":\"STOPPED\"}"))mvc.perform(post("/api/admin/knowledge/documents/1/status").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());verifyNoInteractions(knowledge);}
 @Test void currentModelsDoNotRequireCatalogPermissionAndResponsesAreNoStore()throws Exception{when(knowledge.models(any(),eq(1))).thenReturn(Map.of("items",List.of(),"total",0,"page",1,"page_size",20));mvc.perform(get("/api/admin/knowledge/models").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(knowledge).models(any(),eq(1));}
 @Test void aiAndDatabaseErrorsHaveSafeFallbackMessages()throws Exception{when(knowledge.answer(any(),any())).thenThrow(new com.autocare.platform.ai.AiUpstreamException(com.autocare.platform.ai.AiUpstreamException.Reason.UPSTREAM_FAILURE,"private provider details"));mvc.perform(post("/api/knowledge/answer").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"vehicle_id\":1,\"question\":\"资料问题\"}")).andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private provider"))));when(knowledge.admin(any(),eq(1))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private license SQL"));mvc.perform(get("/api/admin/knowledge/documents").header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private license"))));}
}
