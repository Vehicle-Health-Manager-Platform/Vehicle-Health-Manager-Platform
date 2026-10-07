package com.autocare.platform.order;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters") @AutoConfigureMockMvc
class MerchantOrdersNoDatabaseTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
    @Test void databaseNotConfigured()throws Exception{
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("merchant").header("alg","HS256").subject("1")
            .claim("subject_type","staff_account").claim("role","MERCHANT").claim("app_id","merchant-account")
            .claim("merchant_id",1).claim("jti","test").expiresAt(Instant.now().plusSeconds(600)).build());
        mvc.perform(get("/api/merchant/orders").header("Authorization","Bearer merchant"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
    }
}
