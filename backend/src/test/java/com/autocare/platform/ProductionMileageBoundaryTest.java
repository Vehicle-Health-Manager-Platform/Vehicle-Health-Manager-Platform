package com.autocare.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class ProductionMileageBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Test void demoWriteIsAbsentOutsideLocalProfile() throws Exception {
        mvc.perform(post("/api/demo/vehicles/1001/mileage")
            .header("Authorization","Bearer " + LocalMileageTest.token(encoder,"OWNER")))
            .andExpect(status().isNotFound());
    }
}
