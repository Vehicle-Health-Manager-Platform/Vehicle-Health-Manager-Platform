package com.autocare.platform;

import com.autocare.platform.vehicle.LocalMileageService;
import com.autocare.platform.gateway.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalMileageTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper mapper;
    @MockitoBean LocalMileageService service;
    static String token(JwtEncoder encoder, String role) {
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer(SecurityConfig.issuer()).subject("1001").claim("role", role)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build())).getTokenValue();
    }
    @Test void rejectsAnonymousAndWrongRole() throws Exception {
        mvc.perform(post("/api/demo/vehicles/1001/mileage").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/demo/vehicles/1001/mileage").header("Authorization", "Bearer " + token(encoder,"MERCHANT"))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(service);
    }
    @Test void rejectsInvalidKeysAndBodiesBeforeCallingWriteService() throws Exception {
        String auth = "Bearer " + token(encoder, "OWNER");
        for (String key : new String[]{"", "not-a-uuid", "1-1-1-1-1"}) {
            mvc.perform(post("/api/demo/vehicles/1001/mileage").header("Authorization",auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"current_mileage\":20}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        }
        mvc.perform(post("/api/demo/vehicles/1001/mileage").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"current_mileage\":20}"))
            .andExpect(status().isBadRequest());
        for (String body : new String[]{"{}", "null", "[]", "{\"current_mileage\":-1}",
            "{\"current_mileage\":1.5}", "{\"current_mileage\":\"20\"}", "{\"current_mileage\":2147483648}",
            "{\"current_mileage\":20,\"user_id\":2001}", "{"}) {
            mvc.perform(post("/api/demo/vehicles/1001/mileage").header("Authorization",auth)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        }
        verifyNoInteractions(service);
    }
    @Test void passesOnlyServerIdentityAndValidatedValuesToService() throws Exception {
        String key = UUID.randomUUID().toString();
        when(service.update(1001,1001,key,20)).thenReturn(mapper.readTree("{\"code\":0,\"data\":{\"current_mileage\":20},\"request_id\":\"original\"}"));
        mvc.perform(post("/api/demo/vehicles/1001/mileage").header("Authorization","Bearer " + token(encoder,"OWNER"))
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"current_mileage\":20}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.request_id").value("original"));
        verify(service).update(1001,1001,key,20);
    }
}
