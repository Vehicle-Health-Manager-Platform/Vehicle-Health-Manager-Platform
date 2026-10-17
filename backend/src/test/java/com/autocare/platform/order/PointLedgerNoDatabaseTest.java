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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class PointLedgerNoDatabaseTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("1").claim("jti","session").claim("subject_type","user").claim("role","OWNER").claim("app_id","test-app").expiresAt(Instant.now().plusSeconds(600)).build());}
 @Test void noDatabaseIsUnavailableWhileBadBodyIsRejectedFirst()throws Exception{for(String path:java.util.List.of("/api/point/overview","/api/point/flow"))mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(post("/api/point/check-in").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());}
}
