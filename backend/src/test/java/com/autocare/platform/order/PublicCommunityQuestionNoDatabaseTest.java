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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc class PublicCommunityQuestionNoDatabaseTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
 @Test void bothReadScopesFailClosedWithoutDatabase()throws Exception{when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",t.equals("op")?"operator_account":"user").claim("role",t.equals("op")?"OPERATOR":"OWNER").claim("app_id","operator-account").expiresAt(Instant.now().plusSeconds(600)).build();});mvc.perform(get("/api/community/questions?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/admin/community/question-publications").header("Authorization","Bearer op")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));}
}
