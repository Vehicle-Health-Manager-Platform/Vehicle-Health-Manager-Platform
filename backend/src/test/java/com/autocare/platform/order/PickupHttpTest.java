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
 @Test void onlyOwnerCanDecideAndRequiresValidKey()throws Exception{
  String body="{\"order_id\":1,\"decision\":\"CONFIRM\"}";
  mvc.perform(post("/api/check/pickup/confirm").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
  for(String role:List.of("merchant","technician"))mvc.perform(post("/api/check/pickup/confirm").header("Authorization","Bearer "+role).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/check/pickup/confirm").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
  verifyNoInteractions(pickup);
 }
 @Test void ownerDecisionRejectsUnknownAndMalformedFields()throws Exception{
  for(String body:List.of("{}","{\"order_id\":1,\"decision\":false}","{\"order_id\":1,\"decision\":\"CONFIRM\",\"status\":\"RECEIVED\"}","{\"order_id\":1,\"decision\":\"DISPUTE\",\"reason\":null}"))mvc.perform(post("/api/check/pickup/confirm").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
  verifyNoInteractions(pickup);
 }
 @Test void ownerDecisionReturnsReceiptAndPreservesConflictCode()throws Exception{
  String key=UUID.randomUUID().toString(),body="{\"order_id\":1,\"decision\":\"CONFIRM\"}";
  when(pickup.decide(any(),eq(key),eq(1L),eq("CONFIRM"),isNull())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("owner_confirm",1))));
  mvc.perform(post("/api/check/pickup/confirm").header("Authorization","Bearer owner").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.owner_confirm").value(1));
  when(pickup.decide(any(),eq(key),eq(1L),eq("CONFIRM"),isNull())).thenThrow(new FulfillmentConflict(40905,"接车单状态已变化"));
  mvc.perform(post("/api/check/pickup/confirm").header("Authorization","Bearer owner").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40905));
 }
}
