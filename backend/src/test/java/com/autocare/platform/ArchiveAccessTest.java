package com.autocare.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class ArchiveAccessTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;

    @Test void anonymousRequestsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/archive/1/files/1/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/archive/list").param("vehicle_id", "1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        mvc.perform(post("/api/archive/add").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
    }

    @Test void demoOrNonOwnerTokensCannotReachArchive() throws Exception {
        for (String role : new String[]{"OWNER", "MERCHANT"}) {
            String bearer = "Bearer " + LocalMileageTest.token(encoder, role);
            mvc.perform(get("/api/archive/1/files/1/access").header("Authorization", bearer)).andExpect(status().isForbidden());
            mvc.perform(get("/api/archive/list").header("Authorization", bearer).param("vehicle_id", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
            mvc.perform(post("/api/archive/add").header("Authorization", bearer)
                .header("Idempotency-Key", "123e4567-e89b-42d3-a456-426614174000")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        }
    }
}
