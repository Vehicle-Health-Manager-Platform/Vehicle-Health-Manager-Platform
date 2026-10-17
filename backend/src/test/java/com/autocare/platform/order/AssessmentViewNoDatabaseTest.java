package com.autocare.platform.order;
import java.time.Instant;
import java.util.*;
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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class AssessmentViewNoDatabaseTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op");var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":"staff_account").claim("role",op?"OPERATOR":"MERCHANT").claim("app_id",op?"operator-account":"merchant-account").expiresAt(Instant.now().plusSeconds(600));if(!op)b.claim("merchant_id",1L);return b.build();});}
 @Test void validReadsAreUnavailableButBadSelectorsStay400()throws Exception{mvc.perform(get("/api/admin/assessment/month?merchant_id=1&period_month=2026-10").header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));for(String path:List.of("/api/merchant/assessment/month?period_month=2026-10","/api/merchant/assessment/history?period_month=2026-10","/api/merchant/assessment/staff?period_month=2026-10","/api/merchant/assessment/snapshots/1/facts"))mvc.perform(get(path).header("Authorization","Bearer merchant")).andExpect(status().isServiceUnavailable());mvc.perform(get("/api/admin/assessment/month?merchant_id=1").header("Authorization","Bearer op")).andExpect(status().isBadRequest());}
}
