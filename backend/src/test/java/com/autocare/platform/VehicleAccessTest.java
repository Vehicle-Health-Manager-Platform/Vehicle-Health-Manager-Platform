package com.autocare.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VehicleAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void ownerCanReadOwnVehicleButNotAnotherOwnersVehicle() throws Exception {
        String response = mvc.perform(post("/api/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"1001\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(response).path("data").path("access_token").asText();

        mvc.perform(get("/api/demo/vehicles/1001").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.owner_id").value(1001));

        mvc.perform(get("/api/demo/vehicles/2001").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get("/api/demo/vehicles/1001"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40100));
    }
}
