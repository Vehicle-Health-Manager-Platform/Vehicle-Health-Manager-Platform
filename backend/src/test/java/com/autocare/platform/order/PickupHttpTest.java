package com.autocare.platform.order;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters") @AutoConfigureMockMvc
class PickupHttpTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean PickupInspection pickup;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String token=c.getArgument(0);return Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("subject_type",token.equals("owner")?"user":"staff_account").claim("role",token.equals("owner")?"OWNER":token.equals("technician")?"TECHNICIAN":"MERCHANT").claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","test").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void onlyMerchantCanSubmitAndContextRequiresValidRole()throws Exception{
  mvc.perform(post("/api/check/pickup/submit").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
  for(String role:List.of("owner","technician"))mvc.perform(post("/api/check/pickup/submit").header("Authorization","Bearer "+role).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/check/pickup/context?order_id=1").header("Authorization","Bearer technician")).andExpect(status().isForbidden());verifyNoInteractions(pickup);
 }
 @Test void strictBodyRejectsMissingFieldsAndUnknownFields()throws Exception{
  for(String body:List.of("{}","{\"order_id\":1,\"target_status\":\"RECEIVED\"}"))mvc.perform(post("/api/check/pickup/submit").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());verifyNoInteractions(pickup);
 }
 @Test void sheetCanBeReadByOwnerAndMerchantAndHidesDatabaseDetails()throws Exception{
  when(pickup.detail(any(),eq(1L))).thenReturn(Map.of("pickup_check_id",2));
  for(String role:List.of("owner","merchant"))mvc.perform(get("/api/check/pickup/1").header("Authorization","Bearer "+role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  when(pickup.detail(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));
  mvc.perform(get("/api/check/pickup/1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("接车服务暂不可用，请使用原幂等键重试"));
 }
}
