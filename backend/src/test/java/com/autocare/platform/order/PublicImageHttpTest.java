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
class PublicImageHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PublicImages images;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id","operator-account").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void publicImageReadsRequireOwnerIdentityAndExactVehicleSelector()throws Exception{String id=UUID.randomUUID().toString();mvc.perform(get("/api/community/images/"+id+"?vehicle_id=1")).andExpect(status().isUnauthorized());mvc.perform(get("/api/community/images/"+id+"?vehicle_id=1").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String q:List.of("","vehicle_id=0","vehicle_id=1&vehicle_id=2","vehicle_id=1&owner_id=2","vehicle_id=1&format=url"))mvc.perform(get("/api/community/images/"+id+"?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(images);}
 @Test void previewAndSubmissionRejectOriginalFileReuseAndFalseConsent()throws Exception{String b="{\"experience_id\":\""+UUID.randomUUID()+"\",\"archive_id\":1,\"source_file_id\":2,\"copy_file_id\":2}";mvc.perform(post("/api/owner/community/images/preview").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());String consent=b.replace("\"copy_file_id\":2","\"copy_file_id\":3").replace("}",",\"preview_hash\":\""+"a".repeat(64)+"\",\"consent_version\":\"R8C_IMAGE_V1\",\"consent\":false}");mvc.perform(post("/api/owner/community/images").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(consent)).andExpect(status().isBadRequest());verifyNoInteractions(images);}
 @Test void currentBytesAreNoStoreAndBase64UsesSameAuthorization()throws Exception{byte[] bytes={1,2,3};when(images.reviewPreview(any(),eq(4L))).thenReturn(bytes);mvc.perform(get("/api/admin/community/images/4/preview").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));mvc.perform(get("/api/admin/community/images/4/preview?format=base64").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(jsonPath("$.data.png_base64").value("AQID")).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/admin/community/images/4/preview").header("Authorization","Bearer owner")).andExpect(status().isForbidden());verify(images,times(2)).reviewPreview(any(),eq(4L));}
 @Test void dataErrorsNeverLeakPrivateObjects()throws Exception{when(images.own(any(),eq(1))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL and object key"));mvc.perform(get("/api/owner/community/images").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("object key"))));}
}
