package com.autocare.platform.order;

import java.time.Instant;
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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class TechnicianAssignmentsNoDatabaseTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
    @BeforeEach void token(){when(decoder.decode("tech")).thenReturn(Jwt.withTokenValue("tech").header("alg","HS256").subject("12").claim("subject_type","staff_account").claim("role","TECHNICIAN").claim("app_id","test-app").claim("merchant_id",1).claim("binding_id",2).claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());}
    @Test void validReadIsUnavailableWithoutDatabase()throws Exception{mvc.perform(get("/api/tech/orders").header("Authorization","Bearer tech")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));}
    @Test void malformedQueryFailsBeforeDatabase()throws Exception{mvc.perform(get("/api/tech/orders?page=0").header("Authorization","Bearer tech")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));}
}
