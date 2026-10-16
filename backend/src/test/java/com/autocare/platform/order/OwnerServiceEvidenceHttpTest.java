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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc
class OwnerServiceEvidenceHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean OwnerServiceEvidence evidence;@MockitoBean PrivateFileAccessService files;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("99").claim("jti","session").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":"MERCHANT").claim("app_id","test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
    @Test void ownerOnlyStrictQueryAndReadOnlyMethods()throws Exception{
        mvc.perform(get("/api/order/1/work")).andExpect(status().isUnauthorized());mvc.perform(get("/api/order/1/work").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());
        for(String query:List.of("owner_id=99","file_id=1","role=owner","page=1","x=1&x=2"))mvc.perform(get("/api/order/1/work?"+query).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        for(String path:List.of("/api/order/0/work","/api/order/1/work/files/0/access","/api/order/9007199254740992/work"))mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/order/1/work").header("Authorization","Bearer owner")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(evidence,files);
    }
    @Test void readsNoStoreAndSigningRechecksAfterExternalIo()throws Exception{
        when(evidence.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));var actor=new FileMetadataRepository.Actor("staff_account",12);
        when(evidence.fileOwner(any(),eq(1L),eq(102L))).thenReturn(actor);when(files.sign(actor,102)).thenReturn(new PrivateFileAccessService.SignedAccess("https://test.invalid/private",Instant.now().plusSeconds(120)));
        mvc.perform(get("/api/order/1/work").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/order/1/work/files/102/access").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(evidence,times(2)).fileOwner(any(),eq(1L),eq(102L));
    }
    @Test void revocationDuringSigningDoesNotReturnOldUrl()throws Exception{
        var actor=new FileMetadataRepository.Actor("staff_account",12);when(evidence.fileOwner(any(),eq(1L),eq(102L))).thenReturn(actor).thenThrow(ReservationStore.missing());
        when(files.sign(actor,102)).thenReturn(new PrivateFileAccessService.SignedAccess("https://test.invalid/private",Instant.now().plusSeconds(120)));
        mvc.perform(get("/api/order/1/work/files/102/access").header("Authorization","Bearer owner")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data").doesNotExist());
    }
}
