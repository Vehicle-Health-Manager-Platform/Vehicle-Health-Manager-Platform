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
class ServiceNotificationHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean ServiceNotifications notices;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);var b=Jwt.withTokenValue(t).header("alg","HS256").subject("99").claim("jti","session").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":t.equals("merchant")?"MERCHANT":"TECHNICIAN").claim("app_id",t.equals("merchant")?"merchant-account":"test-app").claim("merchant_id",1).expiresAt(Instant.now().plusSeconds(600));if(t.equals("tech"))b.claim("binding_id",12);return b.build();});}
 @Test void roleBoundariesAndStrictQueriesRejectBeforeService()throws Exception{
  mvc.perform(get("/api/owner/notifications")).andExpect(status().isUnauthorized());for(String role:List.of("owner","merchant","tech")){String wrong=role.equals("owner")?"merchant":"owner";mvc.perform(get("/api/"+role+"/notifications").header("Authorization","Bearer "+wrong)).andExpect(status().isForbidden());for(String q:List.of("recipient_id=99","page=0","page=1&page=1","page_size=100"))mvc.perform(get("/api/"+role+"/notifications?"+q).header("Authorization","Bearer "+role)).andExpect(status().isBadRequest());}verifyNoInteractions(notices);
 }
 @Test void readRequiresEmptyStrictBodyUuidValidIdAndNoRecipients()throws Exception{
  for(String body:List.of("{\"recipient_id\":99}","[]","null","{} {}","{\"read_at\":\"now\"}"))mvc.perform(put("/api/owner/notifications/1/read").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
  mvc.perform(put("/api/owner/notifications/1/read").header("Authorization","Bearer owner").contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(put("/api/owner/notifications/0/read").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(notices);
 }
 @Test void allThreeRolesHaveNoStoreListAndRead()throws Exception{
  when(notices.list(any(),eq(1))).thenReturn(Map.of("unread_count",0));when(notices.read(any(),eq(1L),anyString())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"code\":0,\"data\":{\"notification_id\":1}}"));
  for(String role:List.of("owner","merchant","tech")){mvc.perform(get("/api/"+role+"/notifications?page_size=20").header("Authorization","Bearer "+role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(put("/api/"+role+"/notifications/1/read").header("Authorization","Bearer "+role).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));}
 }
 @Test void lossOfScopeRemainsNoStoreAndUnknownMethodsCannotWrite()throws Exception{
  when(notices.list(any(),anyInt())).thenThrow(ReservationStore.missing());mvc.perform(get("/api/owner/notifications").header("Authorization","Bearer owner")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post("/api/owner/notifications/1/read").header("Authorization","Bearer owner")).andExpect(status().isMethodNotAllowed());verify(notices,never()).read(any(),anyLong(),anyString());
 }
}
