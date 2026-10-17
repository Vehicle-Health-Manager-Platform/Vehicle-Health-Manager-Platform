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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PricePolicyHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean PricePolicies policies;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op"),shop=t.equals("merchant")||t.equals("staff");var builder=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":shop?"staff_account":"user").claim("role",op?"OPERATOR":shop?(t.equals("staff")?"STAFF":"MERCHANT"):"OWNER").claim("app_id",op?"operator-account":shop?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(shop)builder.claim("merchant_id",1L);return builder.build();});}
 String body(){return "{\"quote_id\":1,\"source_hash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"lower_price\":\"10.00\",\"upper_price\":\"20.00\",\"price_unit\":\"once\",\"applicability_note\":\"synthetic scope\",\"source_note\":\"synthetic source\",\"scope_confirmed\":true,\"valid_from\":\"2026-11-01T00:00:00Z\",\"valid_to\":\"2026-12-01T00:00:00Z\"}";}
 @Test void readScopesAndParametersAreStrict()throws Exception{mvc.perform(get("/api/admin/price-monitor/quotes")).andExpect(status().isUnauthorized());for(String t:List.of("merchant","staff","owner"))mvc.perform(get(PricePolicies.PATH).header("Authorization","Bearer "+t)).andExpect(status().isForbidden());for(String q:List.of("merchant_id=1","page=1&page=2","page_size=21"))mvc.perform(get(PricePolicies.PATH+"?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(get(PricePolicies.PATH+"/0").header("Authorization","Bearer op")).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
 @Test void creationHasSingleNoStoreEnvelope()throws Exception{when(policies.create(any(),anyString(),any())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("code",0,"data",Map.of("id",1))));mvc.perform(post(PricePolicies.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());}
 @Test void invalidMoneyConfirmationDuplicateAndKeysNeverWrite()throws Exception{mvc.perform(post(PricePolicies.PATH).header("Authorization","Bearer op").contentType("application/json").content(body())).andExpect(status().isBadRequest());for(String b:List.of(body().replace("10.00","0.00"),body().replace("10.00","21.00"),body().replace("true","false"),body().replace("\"quote_id\":1,","\"quote_id\":1,\"quote_id\":2,"),body()+"{}"))mvc.perform(post(PricePolicies.PATH).header("Authorization","Bearer op").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(b)).andExpect(status().isBadRequest());verifyNoInteractions(policies);}
}
