package com.autocare.platform.order;
import com.autocare.platform.file.*;
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
class ServiceWorkspaceHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean ServiceWorkbench bench;@MockitoBean OrderArchiveGrants grants;@MockitoBean PrivateFileAccessService files;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);var b=Jwt.withTokenValue(t).header("alg","HS256").subject("99").claim("jti","session").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":t.equals("merchant")?"MERCHANT":"TECHNICIAN").claim("app_id",t.equals("merchant")?"merchant-account":"test-app").claim("merchant_id",1).expiresAt(Instant.now().plusSeconds(600));if(t.equals("tech"))b.claim("binding_id",12);return b.build();});}
 @Test void roleBoundariesUnknownQueriesAndReadOnlyMethodsRejectBeforeService()throws Exception{
  mvc.perform(get("/api/merchant/workbench")).andExpect(status().isUnauthorized());mvc.perform(get("/api/merchant/workbench").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/order/1/archive-grants").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());
  for(String query:List.of("user_id=1","bucket=BAD","bucket=SERVICE&bucket=SERVICE","page=0","page_size=100"))mvc.perform(get("/api/tech/workbench?"+query).header("Authorization","Bearer tech")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/merchant/orders/0/service-context").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());mvc.perform(post("/api/tech/workbench").header("Authorization","Bearer tech")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(bench,grants,files);
 }
 @Test void grantsRequireUuidStrictShapeAndNoCallerRecipients()throws Exception{
  String path="/api/order/1/archive-grants/901";for(String body:List.of("{}","{\"status\":\"ACTIVE\",\"expected_revision\":0}","{\"status\":\"REVOKED\",\"expected_revision\":0,\"staff_id\":1}","{\"status\":\"REVOKED\",\"expected_revision\":0,\"status\":\"ACTIVE\"}"))mvc.perform(put(path).header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
  mvc.perform(put(path).header("Authorization","Bearer owner").contentType("application/json").content("{\"status\":\"REVOKED\",\"expected_revision\":0}")).andExpect(status().isBadRequest());verifyNoInteractions(grants);
 }
 @Test void acceptedReadsAndWritesAreNoStoreAndDoNotBroadenIdentity()throws Exception{
  when(bench.read(any(),any(),anyInt())).thenReturn(Map.of("test",true));when(grants.context(any(),eq(1L),eq(1))).thenReturn(Map.of("order_id",1));when(grants.candidates(any(),eq(1L),eq(1))).thenReturn(Map.of("order_id",1));when(grants.set(any(),eq(1L),eq(901L),anyString(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"code\":0,\"data\":{\"order_id\":1}}"));
  for(String role:List.of("merchant","tech")){mvc.perform(get("/api/"+role+"/workbench?bucket=SERVICE").header("Authorization","Bearer "+role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/"+role+"/orders/1/service-context").header("Authorization","Bearer "+role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));}
  mvc.perform(get("/api/order/1/archive-grants").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(put("/api/order/1/archive-grants/901").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"status\":\"REVOKED\",\"expected_revision\":1}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
 }
 @Test void signingRechecksConsentAndScopeAfterStorageIo()throws Exception{
  var actor=new FileMetadataRepository.Actor("user",99);when(grants.fileOwner(any(),eq(1L),eq(901L),eq(601L))).thenReturn(actor).thenThrow(ReservationStore.missing());when(files.sign(actor,601)).thenReturn(new PrivateFileAccessService.SignedAccess("https://test.invalid/private",Instant.now().plusSeconds(120)));
  mvc.perform(get("/api/merchant/orders/1/service-context/archives/901/files/601/access").header("Authorization","Bearer merchant")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));verify(grants,times(2)).fileOwner(any(),eq(1L),eq(901L),eq(601L));
 }
 @Test void ownerCanPreviewBeforeConsentButPreviewNeverCallsGrantWrite()throws Exception{
  var actor=new FileMetadataRepository.Actor("user",99);when(grants.ownerPreview(any(),eq(1L),eq(901L),eq(601L))).thenReturn(actor);when(files.sign(actor,601)).thenReturn(new PrivateFileAccessService.SignedAccess("https://test.invalid/private",Instant.now().plusSeconds(120)));
  mvc.perform(get("/api/order/1/archive-grants/901/files/601/preview").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(grants,times(2)).ownerPreview(any(),eq(1L),eq(901L),eq(601L));verify(grants,never()).set(any(),anyLong(),anyLong(),anyString(),any());
 }
}
