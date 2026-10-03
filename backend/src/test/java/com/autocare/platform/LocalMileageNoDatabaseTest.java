package com.autocare.platform;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalMileageNoDatabaseTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Test void unconfiguredDatabaseFailsClosed() throws Exception {
        mvc.perform(post("/api/demo/vehicles/1001/mileage")
            .header("Authorization","Bearer " + LocalMileageTest.token(encoder,"OWNER"))
            .header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"current_mileage\":20}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));
    }
}
