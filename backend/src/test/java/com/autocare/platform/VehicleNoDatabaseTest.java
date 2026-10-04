package com.autocare.platform;

import java.time.Instant;
import java.util.UUID;
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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class VehicleNoDatabaseTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Test void missingDatabaseFailsClosedForAllRoutes() throws Exception {
        when(decoder.decode("owner")).thenReturn(Jwt.withTokenValue("owner").header("alg","HS256").subject("1").claim("subject_type","user").claim("role","OWNER").claim("jti","test").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        for(String path:new String[]{"/api/vehicle/list","/api/brand/list","/api/series/list?brand_id=1","/api/model/list?series_id=1"})
            mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
        mvc.perform(post("/api/vehicle/add").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"add_type\":4,\"model_id\":1}"))
            .andExpect(status().isServiceUnavailable());
    }
}
