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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointExchangeCatalogHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PointExchangeCatalog catalog;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id",t.equals("op")?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();});}
 String body(){return "{\"coupon_id\":1,\"publication_revision\":2,\"pool_revision\":1,\"points_cost\":17,\"per_owner_limit\":2,\"source_note\":\"合成测试来源\",\"valid_from\":\"2026-10-16T00:00:00Z\",\"valid_to\":\"2026-11-16T00:00:00Z\"}";}
 @Test void operatorOnlyAndStrictPageQueryBeforeService()throws Exception{mvc.perform(get(PointExchangeCatalog.PATH)).andExpect(status().isUnauthorized());for(String path:List.of(PointExchangeCatalog.PATH,"/api/admin/point/exchange-sources")){mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("user_id=2","page=0","page=1&page=2","page_size=100"))mvc.perform(get(path+"?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());}verifyNoInteractions(catalog);}
 @Test void listsAreNoStoreWithExactPageScope()throws Exception{var result=Map.of("items",List.of(),"total",0,"page",2,"page_size",20);when(catalog.list(any(),eq(2))).thenReturn(result);when(catalog.sources(any(),eq(2))).thenReturn(result);for(String path:List.of(PointExchangeCatalog.PATH,"/api/admin/point/exchange-sources"))mvc.perform(get(path+"?page=2&page_size=20").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.page").value(2));}
 @Test void strictBodiesIdsAndKeysBeforeAnyDatabaseWrite()throws Exception{mvc.perform(post(PointExchangeCatalog.PATH).header("Authorization","Bearer op").contentType("application/json").content(body())).andExpect(status().isBadRequest());for(String b:List.of(body().replace("17,","0,"),body().replace("17,","1.5,"),body().replace("17,","17,\"eligible\":true,"),body()+"{}"))mvc.perform(post(PointExchangeCatalog.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());mvc.perform(put(PointExchangeCatalog.PATH+"/0/status").header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"expected_revision\":1,\"status\":\"PUBLISHED\"}")).andExpect(status().isBadRequest());verifyNoInteractions(catalog);}
 @Test void creationReturnsOriginalReceiptWithoutNestedEnvelope()throws Exception{var json=new com.fasterxml.jackson.databind.ObjectMapper();when(catalog.create(any(),anyString(),any())).thenReturn(json.readTree("{\"code\":0,\"data\":{\"id\":4,\"status\":\"DRAFT\"}}"));mvc.perform(post(PointExchangeCatalog.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.id").value(4)).andExpect(jsonPath("$.data.data").doesNotExist());}
}
