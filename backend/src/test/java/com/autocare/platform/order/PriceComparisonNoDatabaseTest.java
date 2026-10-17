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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc class PriceComparisonNoDatabaseTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("op").header("alg","HS256").subject("1").claim("jti","session").claim("subject_type","operator_account").claim("role","OPERATOR").claim("app_id","operator-account").expiresAt(Instant.now().plusSeconds(600)).build());}
 @Test void unconfiguredReadFailsClosed()throws Exception{for(String p:java.util.List.of("/api/admin/price-monitor/comparisons","/api/admin/price-monitor/comparisons/1"))mvc.perform(get(p).header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));}
}
