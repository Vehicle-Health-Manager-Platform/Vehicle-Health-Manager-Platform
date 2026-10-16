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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class DiagnosisHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean DiagnosisCases cases;@MockitoBean DiagnosisServicePolicies policies;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String create(){return "{\"vehicle_id\":901,\"policy_id\":1,\"policy_revision\":2,\"message\":\"本次问题\",\"consent\":true}";}
 @Test void identityQueriesAndIdsAreStrict()throws Exception{
  mvc.perform(get("/api/owner/diagnosis/context")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/diagnosis/cases").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/owner/diagnosis/cases").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String q:List.of("owner_id=2","scope=all","scope=mine&scope=queue","page_size=100","page=0"))mvc.perform(get("/api/admin/diagnosis/cases?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(get("/api/owner/diagnosis/cases/0").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(cases,policies);
 }
 @Test void createRejectsForgedConsentSourceRecipientsAndMissingKey()throws Exception{
  for(String b:List.of(create().replace("true","false"),create().replace("901","0"),create().replace("\"message\"","\"operator_id\""),create().replace("true}","true,\"eligible\":true}"),create()+"{}"))mvc.perform(post("/api/owner/diagnosis/cases").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());mvc.perform(post("/api/owner/diagnosis/cases").header("Authorization","Bearer owner").contentType("application/json").content(create())).andExpect(status().isBadRequest());verifyNoInteractions(cases);
 }
 @Test void allReadRoutesAreNoStoreAndBodyFreeQueueCanBeSelected()throws Exception{
  when(cases.context(any())).thenReturn(Map.of("test",true));when(cases.list(any(),anyBoolean(),anyInt())).thenReturn(Map.of("test",true));when(cases.detail(any(),anyLong(),anyInt())).thenReturn(Map.of("test",true));when(policies.list(any(),anyInt())).thenReturn(Map.of("test",true));when(policies.handlers(any(),anyInt())).thenReturn(Map.of("test",true));
  for(String path:List.of("context","cases","cases/1"))mvc.perform(get("/api/owner/diagnosis/"+path).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));for(String path:List.of("policies","handlers","cases?scope=queue","cases?scope=mine","cases/1"))mvc.perform(get("/api/admin/diagnosis/"+path).header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(cases).list(any(),eq(true),eq(1));
 }
 @Test void writesAreAuditedReceiptRoutesAndRejectUnknownFields()throws Exception{
  var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"code\":0,\"data\":{\"case_id\":1,\"revision\":1,\"status\":\"WAITING\"}}");when(cases.create(any(),anyString(),any())).thenReturn(result);when(cases.assign(any(),eq(1L),anyString(),any())).thenReturn(result);when(cases.write(any(),eq(1L),anyString(),anyString(),any())).thenReturn(result);
  mvc.perform(post("/api/owner/diagnosis/cases").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(create())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(put("/api/admin/diagnosis/cases/1/assignment").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_revision\":1,\"operator_id\":1001}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  for(String role:List.of("owner","admin"))mvc.perform(post("/api/"+role+"/diagnosis/cases/1/close").header("Authorization","Bearer "+(role.equals("owner")?"owner":"op")).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_revision\":1,\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
 }
 @Test void sourceLossAndDatabaseFailuresAreNoStore()throws Exception{
  when(cases.detail(any(),anyLong(),anyInt())).thenThrow(ReservationStore.missing());mvc.perform(get("/api/admin/diagnosis/cases/1").header("Authorization","Bearer op")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));when(policies.list(any(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic private detail"));mvc.perform(get("/api/admin/diagnosis/policies").header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
 }
}
