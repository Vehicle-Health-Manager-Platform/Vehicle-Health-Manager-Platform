package com.autocare.platform.order;

import java.time.Instant;
import java.util.UUID;
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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc
class OrderReviewsNoDatabaseTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("1").claim("subject_type","user").claim("role","OWNER").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());}
    @Test void validWritesAndReadsFailClosedWithoutDatabase()throws Exception{
        mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"rating\":5,\"content\":\"实际反馈\",\"photo_file_ids\":[]}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/order/1/review").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable());
    }
    @Test void invalidInputFailsBeforeDatabase()throws Exception{
        mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
}
