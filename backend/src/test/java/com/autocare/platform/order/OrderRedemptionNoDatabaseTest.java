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
class OrderRedemptionNoDatabaseTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);
        var b=Jwt.withTokenValue(token).header("alg","HS256").subject("12").claim("subject_type",token.equals("owner")?"user":token.equals("binding")?"wechat_binding":"staff_account")
            .claim("role",token.equals("owner")?"OWNER":token.equals("merchant")?"MERCHANT":"TECHNICIAN")
            .claim("app_id",token.equals("merchant")?"merchant-account":token.equals("wrong-app")?"other":"test-app")
            .claim("merchant_id",1).claim("jti","session").expiresAt(Instant.now().plusSeconds(600));
        if(!token.equals("merchant") && !token.equals("missing-binding")){if(token.equals("fraction"))b.claim("binding_id",1.5);else b.claim("binding_id",2);}
        return b.build();});}
    String key(){return UUID.randomUUID().toString();}

    @Test void validWritesAndReadsFailClosedWithoutDatabase()throws Exception{
        mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/order/1/redemption").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable());
    }
    @Test void invalidInputFailsBeforeDatabase()throws Exception{
        mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
}
