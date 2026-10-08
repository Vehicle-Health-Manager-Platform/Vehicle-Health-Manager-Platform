package com.autocare.platform.order;

import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters") @AutoConfigureMockMvc
class DisputeNoDatabaseTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){
        when(decoder.decode("merchant")).thenReturn(Jwt.withTokenValue("merchant").header("alg","HS256").subject("1").claim("subject_type","staff_account").claim("role","MERCHANT").claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());
        when(decoder.decode("owner")).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("7").claim("subject_type","user").claim("role","OWNER").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());
    }
    @Test void validWritesAreUnavailableWithoutDatabase()throws Exception{
        mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key",java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对照片\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
        mvc.perform(post("/api/check/pickup/dispute/review").header("Authorization","Bearer owner").header("Idempotency-Key",java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"order_id\":1,\"decision\":\"ACCEPT\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
    }
    @Test void malformedRequestsFailBeforeTheDatabase()throws Exception{
        mvc.perform(post("/api/merchant/orders/0/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key",java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对照片\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        mvc.perform(post("/api/merchant/orders/1/dispute/handle").header("Authorization","Bearer merchant").header("Idempotency-Key","not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已核对照片\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
    }
}
