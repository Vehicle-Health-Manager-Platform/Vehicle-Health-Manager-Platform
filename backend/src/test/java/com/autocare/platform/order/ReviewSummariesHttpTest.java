package com.autocare.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class ReviewSummariesHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean ReviewSummaries service;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String t=call.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",t.equals("owner")?"user":"staff_account").claim("role",t.equals("owner")?"OWNER":"MERCHANT").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String key(){return UUID.randomUUID().toString();}
    String body(){return "{\"consented\":true,\"expected_version\":0,\"expected_text_version\":2,\"consent_version\":\"R4B2_SUMMARY_V1\"}";}
    @Test void ownerScopeAndAnonymousOrMerchantCannotUseSummaries()throws Exception{
        mvc.perform(post("/api/service/merchant/1/review-summary").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());mvc.perform(post("/api/service/merchant/1/review-summary").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());mvc.perform(get("/api/order/1/review-summary-consent").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictBodiesVersionsPurposeQueryAndKeyRejectBeforeService()throws Exception{
        for(String b:List.of("null","[]","{}",body()+"{}",body().replace("\"consented\":true","\"consented\":true,\"consented\":false"),body().replace("\"expected_version\":0","\"expected_version\":-1"),body().replace("R4B2_SUMMARY_V1","R4B2_TEXT_V1"),body().replace("\"expected_text_version\":2","\"expected_text_version\":2.0")))mvc.perform(put("/api/order/1/review-summary-consent").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        for(String b:List.of("null","[]","{}{}","{\"text\":\"private\"}"))mvc.perform(post("/api/service/merchant/1/review-summary").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/service/merchant/1/review-summary?source_id=1").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());mvc.perform(put("/api/order/1/review-summary-consent").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void readonlySummaryAndGrantHaveNoStoreSingleEnvelope()throws Exception{
        when(service.summarize(any(),eq(1L))).thenReturn(Map.of("merchant_id",1,"status","EMPTY"));mvc.perform(post("/api/service/merchant/1/review-summary").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.status").value("EMPTY"));
        when(service.update(any(),eq(1L),anyString(),any())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1))));mvc.perform(put("/api/order/1/review-summary-consent").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(jsonPath("$.data.order_id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());
    }
    @Test void databaseFailuresAreSanitizedAndNoStore()throws Exception{
        when(service.detail(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));mvc.perform(get("/api/order/1/review-summary-consent").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("评价总结暂不可用，请稍后重试"));
    }
}
