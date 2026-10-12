package com.autocare.platform;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class HealthObservationAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Test void anonymousCannotReadOrWriteTargets() throws Exception {
        for (String path : List.of("/api/archive/11/health-observation", "/api/vehicle/health-observations?vehicle_id=101"))
            mvc.perform(get(path).param("metric","OIL")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(put("/api/archive/11/health-observation").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void demoAndNonOwnerRolesCannotReachReminderService() throws Exception {
        for (String role : List.of("OWNER", "MERCHANT", "TECHNICIAN", "OPERATOR")) {
            String bearer = "Bearer " + LocalMileageTest.token(encoder, role);
            for (String path : List.of("/api/archive/11/health-observation", "/api/vehicle/health-observations?vehicle_id=101"))
                mvc.perform(get(path).param("metric","OIL").header("Authorization", bearer)).andExpect(status().isForbidden());
            mvc.perform(put("/api/archive/11/health-observation").header("Authorization", bearer).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}
