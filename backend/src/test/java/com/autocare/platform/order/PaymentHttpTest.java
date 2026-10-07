package com.autocare.platform.order;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","PAYMENT_LOCAL_TEST_ENABLED=true","PAYMENT_LOCAL_TEST_SECRET="+LocalTestPaymentChannelTest.KEY})
@AutoConfigureMockMvc @ActiveProfiles("local-payment-test")
class PaymentHttpTest {
    @Autowired MockMvc mvc;@MockitoBean PaymentService payments;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean owner=t.equals("owner");return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",owner?"user":"staff_account").claim("role",owner?"OWNER":"MERCHANT").claim("jti","test").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();});}
    @Test void ownerBoundaryAndStrictBody()throws Exception{mvc.perform(get("/api/payments/1")).andExpect(status().isUnauthorized());mvc.perform(get("/api/payments/1").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());mvc.perform(post("/api/payments/create").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"channel\":\"LOCAL_TEST\",\"amount\":\"0.01\"}")).andExpect(status().isBadRequest());verifyNoInteractions(payments);}
    @Test void realChannelNotConfigured()throws Exception{mvc.perform(post("/api/payments/create").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"channel\":\"WECHAT\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("正式微信支付尚未配置，当前不可支付"));verifyNoInteractions(payments);}
    @Test void signedCallbackHasIndependentAckAndRetryFailure()throws Exception{
        String time=""+Instant.now().getEpochSecond(),nonce=UUID.randomUUID().toString();byte[] raw=LocalTestPaymentChannelTest.body("SUCCEEDED").getBytes(java.nio.charset.StandardCharsets.UTF_8);String signature=LocalTestPaymentChannelTest.sign(raw,time,nonce);
        mvc.perform(post("/api/payments/callback/LOCAL_TEST").contentType(MediaType.APPLICATION_JSON).header("X-Test-Timestamp",time).header("X-Test-Nonce",nonce).header("X-Test-Signature",signature).content(raw)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS")).andExpect(header().string("Cache-Control","no-store"));verify(payments).notify(any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL")).when(payments).notify(any());mvc.perform(post("/api/payments/callback/LOCAL_TEST").contentType(MediaType.APPLICATION_JSON).header("X-Test-Timestamp",time).header("X-Test-Nonce",nonce).header("X-Test-Signature",signature).content(raw)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("支付服务暂不可用"));
    }
    @Test void unsignedAndOtherCallbackPathsRejected()throws Exception{mvc.perform(post("/api/payments/callback/LOCAL_TEST").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());mvc.perform(post("/api/payments/callback/WECHAT").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());verifyNoInteractions(payments);}
}
