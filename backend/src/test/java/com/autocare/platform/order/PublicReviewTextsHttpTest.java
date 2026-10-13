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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class PublicReviewTextsHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean PublicReviewTexts service;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String t=call.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",t.equals("owner")?"user":t.equals("operator")?"operator_account":"staff_account").claim("role",t.equals("owner")?"OWNER":t.equals("operator")?"OPERATOR":"MERCHANT").claim("app_id",t.equals("operator")?"operator-account":"test-app").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String key(){return UUID.randomUUID().toString();}
    String body(){return "{\"action\":\"SUBMIT\",\"expected_version\":0,\"public_text\":\"独立文字\",\"consent_version\":\"R4B2_TEXT_V1\"}";}
    @Test void ownerOperatorAndMerchantCannotCrossScopes()throws Exception{
        mvc.perform(get("/api/order/1/public-review-text")).andExpect(status().isUnauthorized());
        for(String token:List.of("operator","merchant"))mvc.perform(get("/api/order/1/public-review-text").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        for(String token:List.of("owner","merchant"))mvc.perform(get("/api/admin/review-texts").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void strictJsonAndCanonicalConsentRejectBeforeService()throws Exception{
        for(String b:List.of("null","[]","{}",body()+"{}",body().replace("\"action\":\"SUBMIT\"","\"action\":\"SUBMIT\",\"action\":\"WITHDRAW\""),body().replace("独立文字","联系13800000000"),body().replace("\"expected_version\":0","\"expected_version\":-1"),body().replace("R4B2_TEXT_V1","bad"),body().replace("独立文字","a".repeat(301)))){
            mvc.perform(put("/api/order/1/public-review-text").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().is(b.contains("13800000000")?422:400));
        }
        mvc.perform(put("/api/order/1/public-review-text").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void pagePathAndModerationReasonsAreStrict()throws Exception{
        for(String path:List.of("/api/order/0/public-review-text","/api/order/1/public-review-text?user_id=2","/api/service/merchant/1/review-texts?page=1&page=2","/api/service/merchant/1/review-texts?page_size=51"))mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        for(String body:List.of("{\"expected_version\":1,\"decision\":\"APPROVE\",\"reason_code\":\"PRIVACY\"}","{\"expected_version\":1,\"decision\":\"REJECT\",\"reason_code\":null}","{\"expected_version\":1,\"decision\":\"REJECT\",\"reason_code\":\"free text\"}"))mvc.perform(post("/api/admin/review-texts/1/moderate").header("Authorization","Bearer operator").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void previewIsReadOnlyNoStoreAndWritesHaveSingleEnvelope()throws Exception{
        when(service.preview(any(),eq(1L),any())).thenReturn(Map.of("public_text","[已隐藏]","redaction_version","TEXT_REDACTION_V1"));
        mvc.perform(post("/api/order/1/public-review-text/preview").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"public_text\":\"13800000000\"}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.public_text").value("[已隐藏]"));
        when(service.update(any(),eq(1L),anyString(),any())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1))));mvc.perform(put("/api/order/1/public-review-text").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(jsonPath("$.data.order_id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());
    }
    @Test void safeDatabaseErrorAndTextValidationUnicodeBoundaries()throws Exception{
        when(service.detail(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private body SQL"));mvc.perform(get("/api/order/1/public-review-text").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("公开文本服务暂不可用，请稍后重试"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->PublicReviewTextInput.redact(mapper.valueToTree("a\u200Bb")));assertEquals("🙂".repeat(300),PublicReviewTextInput.redact(mapper.valueToTree("🙂".repeat(300))));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->PublicReviewTextInput.redact(mapper.valueToTree("\uD800")));
    }
}
