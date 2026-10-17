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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PriceComparisonHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PriceComparisons comparisons;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op"),shop=t.equals("merchant")||t.equals("staff");var builder=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":shop?"staff_account":"user").claim("role",op?"OPERATOR":shop?(t.equals("staff")?"STAFF":"MERCHANT"):"OWNER").claim("app_id",op?"operator-account":shop?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(shop)builder.claim("merchant_id",1L);return builder.build();});}
 @Test void strictSelectorsAndRolesFailBeforeSource()throws Exception{mvc.perform(get("/api/admin/price-monitor/comparisons")).andExpect(status().isUnauthorized());for(String t:List.of("merchant","staff","owner"))mvc.perform(get("/api/admin/price-monitor/comparisons").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());for(String q:List.of("merchant_id=1","quote_id=1","page=1&page=2","page_size=21"))mvc.perform(get("/api/admin/price-monitor/comparisons?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/price-monitor/comparisons/1?page=1").header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(comparisons);}
 @Test void noStoreReadEnvelopeAndPositiveIds()throws Exception{when(comparisons.list(any(),eq(1))).thenReturn(Map.of("observed_at","synthetic"));when(comparisons.detail(any(),eq(1L))).thenReturn(Map.of("observed_at","synthetic"));for(String p:List.of("/api/admin/price-monitor/comparisons","/api/admin/price-monitor/comparisons/1"))mvc.perform(get(p).header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.observed_at").value("synthetic"));mvc.perform(get("/api/admin/price-monitor/comparisons/0").header("Authorization","Bearer op")).andExpect(status().isBadRequest());}
}
