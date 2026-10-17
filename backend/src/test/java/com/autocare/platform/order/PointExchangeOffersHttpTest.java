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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointExchangeOffersHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PointExchangeOffers offers;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 @Test void onlyOwnerAndExactPaginationReachService()throws Exception{mvc.perform(get("/api/point/exchange-catalog")).andExpect(status().isUnauthorized());mvc.perform(get("/api/point/exchange-catalog").header("Authorization","Bearer op")).andExpect(status().isForbidden());for(String query:List.of("user_id=2","page=0","page=1&page=2","page_size=100"))mvc.perform(get("/api/point/exchange-catalog?"+query).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(offers);}
 @Test void scopedCatalogIsNoStoreAndIndependentOfEarning()throws Exception{when(offers.list(any(),eq(2))).thenReturn(Map.of("items",List.of(),"total",0,"page",2,"page_size",20,"balance",0,"legacy_state","NONE","exchange_available",false,"read_at","2026-10-16T00:00:00Z"));mvc.perform(get("/api/point/exchange-catalog?page=2&page_size=20").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.exchange_available").value(false)).andExpect(jsonPath("$.data.page").value(2));}
 @Test void databaseFailureIsExplicitWithoutInternalDetails()throws Exception{when(offers.list(any(),eq(1))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db"));mvc.perform(get("/api/point/exchange-catalog").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("兑换目录暂不可用，请刷新后核对"));}
}
