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
class DisputeHttpTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean OrderDisputes disputes;
 @BeforeEach void tokens(){
  when(decoder.decode(anyString())).thenAnswer(c->{
   String token=c.getArgument(0);
   return Jwt.withTokenValue(token).header("alg","HS256").subject(token.equals("owner")?"7":"1")
    .claim("subject_type",token.equals("owner")?"user":"staff_account")
    .claim("role",token.equals("owner")?"OWNER":token.equals("technician")?"TECHNICIAN":"MERCHANT")
    .claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","test")
    .expiresAt(Instant.now().plusSeconds(600)).build();});
 }
 private String key(){return UUID.randomUUID().toString();}
 @Test void merchantHandlingRequiresMerchantRoleAndKey()throws Exception{
  String body="{\"note\":\"已核对接车照片，同意补拍\"}";
  mvc.perform(post("/api/merchant/orders/1/dispute/handle").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
  for(String role:List.of("owner","technician"))mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer "+role).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
  verifyNoInteractions(disputes);
 }
 @Test void merchantHandlingRejectsUnknownAndMalformedBodies()throws Exception{
  for(String body:List.of("{}","{\"note\":\"\"}","{\"note\":7}","{\"note\":\"a\",\"decision\":\"ACCEPT\"}","{\"note\":\"a\",\"order_id\":1}"))
   mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
  verifyNoInteractions(disputes);
 }
 @Test void reviewRequiresOwnerRoleAndKey()throws Exception{
  String body="{\"order_id\":1,\"decision\":\"ACCEPT\"}";
  mvc.perform(post("/api/check/pickup/dispute/review").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
  for(String role:List.of("merchant","technician"))mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer "+role).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
  verifyNoInteractions(disputes);
 }
 @Test void reviewRejectsUnknownAndMalformedFields()throws Exception{
  for(String body:List.of("{}","{\"order_id\":1}","{\"decision\":\"ACCEPT\"}","{\"order_id\":1,\"decision\":true}","{\"order_id\":1,\"decision\":\"ACCEPT\",\"note\":null}","{\"order_id\":1,\"decision\":\"ACCEPT\",\"status\":\"RECEIVED\"}","{\"order_id\":1.5,\"decision\":\"ACCEPT\"}","{\"order_id\":0,\"decision\":\"ACCEPT\"}"))
   mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
  verifyNoInteractions(disputes);
 }
 @Test void bothWritesReturnReceiptsAndPreserveBusinessCodes()throws Exception{
  String merchantKey=key(),ownerKey=key();
  when(disputes.handle(any(),eq(merchantKey),eq(1L),eq("已核对照片"))).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("dispute_status","OPEN","record_count",1))));
  mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key",merchantKey).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对照片\"}"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.dispute_status").value("OPEN"));
  when(disputes.review(any(),eq(ownerKey),eq(1L),eq("ACCEPT"),isNull())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("dispute_status","RESOLVED","order_status","RECEIVED","owner_confirm",3))));
  mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer owner").header("Idempotency-Key",ownerKey).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"decision\":\"ACCEPT\"}"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.owner_confirm").value(3));
  when(disputes.review(any(),eq(ownerKey),eq(1L),eq("ACCEPT"),isNull())).thenThrow(new FulfillmentConflict(OrderDisputes.HANDLING_REQUIRED,"商家尚未提交处理记录，暂不能复核"));
  mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer owner").header("Idempotency-Key",ownerKey).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"decision\":\"ACCEPT\"}"))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(43008));
 }
 @Test void databaseFailureIsReportedAsRetryableUnavailable()throws Exception{
  when(disputes.handle(any(),anyString(),eq(1L),anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));
  mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对照片\"}"))
   .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("争议处理服务暂不可用，请使用原幂等键重试"));
 }
}
