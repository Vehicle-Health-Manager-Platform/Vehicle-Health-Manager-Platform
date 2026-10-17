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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class CustomerRelationsHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean CustomerRelations relations;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op"),shop=t.equals("merchant")||t.equals("staff");var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":shop?"staff_account":"user").claim("role",op?"OPERATOR":shop?t.equals("staff")?"STAFF":"MERCHANT":"OWNER").claim("app_id",op?"operator-account":shop?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(shop)b.claim("merchant_id",1L);return b.build();});}

 @Test void rolesAndStrictSelectorsAreRejectedBeforeSource()throws Exception{mvc.perform(get("/api/merchant/customer-relations")).andExpect(status().isUnauthorized());for(String t:List.of("owner","op","staff"))mvc.perform(get("/api/merchant/customer-relations").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());for(String q:List.of("merchant_id=2","user_id=99","page=1&page=2","page_size=21","page=0"))mvc.perform(get("/api/merchant/customer-relations?"+q).header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/customer-relations/0").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());verifyNoInteractions(relations);}
 @Test void onlyReadEnvelopesAndNoStoreAreExposed()throws Exception{when(relations.list(any(),eq(1))).thenReturn(Map.of("observed_at","synthetic"));when(relations.detail(any(),eq(1L),eq(1))).thenReturn(Map.of("observed_at","synthetic"));for(String p:List.of("/api/merchant/customer-relations","/api/merchant/customer-relations/1"))mvc.perform(get(p).header("Authorization","Bearer merchant")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.observed_at").value("synthetic"));}
}
