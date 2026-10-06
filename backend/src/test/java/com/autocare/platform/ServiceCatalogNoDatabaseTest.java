package com.autocare.platform;

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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class ServiceCatalogNoDatabaseTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Test void missingDatabaseReturns503() throws Exception {
        when(decoder.decode("owner")).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("1")
            .claim("subject_type","user").claim("role","OWNER").claim("jti","catalog-test")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        for(String path:new String[]{"/api/service/projects","/api/service/project/1"})
            mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
