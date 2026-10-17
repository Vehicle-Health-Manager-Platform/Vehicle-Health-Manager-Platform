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
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class InvitationHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean Invitations invitations;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").expiresAt(Instant.now().plusSeconds(600));if(t.equals("owner"))b.claim("subject_type","user").claim("role","OWNER").claim("app_id","test-app");else b.claim("subject_type","staff_account").claim("role",t.equals("tech")?"TECHNICIAN":"STAFF").claim("app_id","merchant-account").claim("merchant_id",1L);return b.build();});}
 String key(){return UUID.randomUUID().toString();}
 @Test void readsRequireRoleAndNoStore()throws Exception{mvc.perform(get("/api/invite/progress")).andExpect(status().isUnauthorized());mvc.perform(get("/api/invite/progress").header("Authorization","Bearer staff")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/invite/progress").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/invite/progress").header("Authorization","Bearer tech")).andExpect(status().isForbidden());when(invitations.progress(any(com.autocare.platform.vehicle.VehicleOwner.class))).thenReturn(Invitations.cycle(0));mvc.perform(get("/api/invite/progress").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));}
 @Test void readsRejectIdentityOverrideDuplicateAndUnboundedPagination()throws Exception{for(String q:List.of("user_id=2","page=1&page=2","page=0","page=1000001","page_size=100","page=1e2"))mvc.perform(get("/api/invite/records?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());mvc.perform(get("/api/invite/qrcode?page=1").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(invitations);}
 @Test void codeCreationRejectsEligibilityFieldsAndMalformedJson()throws Exception{for(String raw:List.of("null","[]","{}{}","{\"user_id\":2}","{\"new_user\":true}","{\"verified\":true}","{\"code\":\"a\",\"code\":\"b\"}"))mvc.perform(post("/api/invite/code").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());mvc.perform(post("/api/invite/code").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(invitations);}
 @Test void staffCanReadOnlyOwnScopeNoClientStaffId()throws Exception{when(invitations.records(any(com.autocare.platform.service.MerchantActor.class),eq(1))).thenReturn(Map.of("items",List.of(),"total",0,"page",1,"page_size",20));mvc.perform(get("/api/merchant/invite/records?page=1&page_size=20").header("Authorization","Bearer staff")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/merchant/invite/records?staff_id=2").header("Authorization","Bearer staff")).andExpect(status().isBadRequest());}
 @Test void strictLoginParserRejectsClientProofAndTechnicianInvitation(){for(String raw:List.of("{\"code\":\"x\",\"role\":\"owner\",\"verified\":true}","{\"code\":\"x\",\"role\":\"owner\",\"invite_code\":null}","{\"code\":\"x\",\"role\":\"owner\",\"invite_code\":\"short\"}","{\"code\":\"x\",\"role\":\"owner\",\"role\":\"owner\"}","{\"code\":\"x\",\"role\":\"technician\",\"invite_code\":\""+"a".repeat(32)+"\"}"))Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InvitationInput.login(raw,false));Assertions.assertEquals("a".repeat(32),InvitationInput.code(InvitationInput.login("{\"role\":\"owner\",\"invite_code\":\""+"a".repeat(32)+"\"}",true).get("invite_code")));}
}
