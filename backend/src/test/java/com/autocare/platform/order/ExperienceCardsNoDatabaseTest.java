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
class ExperienceCardsNoDatabaseTest {
    @Autowired MockMvc mvc; @MockitoBean JwtDecoder decoder;
    @Test void unconfiguredDatabaseFailsClosedAndInvalidInputStillRejects() throws Exception {
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("1").claim("subject_type","user").claim("role","OWNER").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());
        mvc.perform(get("/api/experience-cards?vehicle_id=1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/experience-cards/1/consent").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/experience-cards/1/withdraw").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isServiceUnavailable());
    }
}
