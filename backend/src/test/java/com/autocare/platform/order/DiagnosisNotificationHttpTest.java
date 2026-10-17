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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class DiagnosisNotificationHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean DiagnosisNotifications notices;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("owner")?"user":"operator_account").claim("role",t.equals("owner")?"OWNER":"OPERATOR").claim("app_id",t.equals("owner")?"test-app":"operator-account").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void rolesAndQueriesCannotChooseRecipientsOrCrossScopes()throws Exception{
  mvc.perform(get("/api/owner/diagnosis/notifications")).andExpect(status().isUnauthorized());for(String role:List.of("owner","admin")){String token=role.equals("owner")?"owner":"op",wrong=role.equals("owner")?"op":"owner";mvc.perform(get("/api/"+role+"/diagnosis/notifications").header("Authorization","Bearer "+wrong)).andExpect(status().isForbidden());for(String q:List.of("recipient_id=1","case_id=1","scope=queue","page=0","page=1&page=2","page_size=100"))mvc.perform(get("/api/"+role+"/diagnosis/notifications?"+q).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());}verifyNoInteractions(notices);
 }
 @Test void readRejectsForgedBodyInvalidIdAndMissingUuid()throws Exception{
  for(String body:List.of("[]","null","{} {}","{\"recipient_id\":1}","{\"target\":\"CASE\"}","{\"read_at\":\"now\"}"))mvc.perform(put("/api/owner/diagnosis/notifications/1/read").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isBadRequest());mvc.perform(put("/api/admin/diagnosis/notifications/0/read").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(put("/api/admin/diagnosis/notifications/1/read").header("Authorization","Bearer op").contentType("application/json").content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(notices);
 }
 @Test void bothListsAndReceiptsAreNoStore()throws Exception{
  when(notices.list(any(),anyInt())).thenReturn(Map.of("test",true));when(notices.read(any(),anyLong(),anyString())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"code\":0,\"data\":{}}"));for(String role:List.of("owner","admin")){String token=role.equals("owner")?"owner":"op";mvc.perform(get("/api/"+role+"/diagnosis/notifications?page=2&page_size=20").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(put("/api/"+role+"/diagnosis/notifications/1/read").header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));}verify(notices,times(2)).list(any(),eq(2));
 }
 @Test void lostScopeAndDatabaseErrorsAreNoStoreAndDoNotExposeSql()throws Exception{
  when(notices.list(any(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-sql"));mvc.perform(get("/api/admin/diagnosis/notifications").header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-private-sql"))));when(notices.read(any(),anyLong(),anyString())).thenThrow(ReservationStore.missing());mvc.perform(put("/api/owner/diagnosis/notifications/1/read").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
 }
}
