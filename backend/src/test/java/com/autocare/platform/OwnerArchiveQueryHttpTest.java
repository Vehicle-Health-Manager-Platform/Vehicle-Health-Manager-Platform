package com.autocare.platform;

import com.autocare.platform.vehicle.*;
import java.time.Instant;
import java.util.*;
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

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
class OwnerArchiveQueryHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ArchiveStructuredService structured;
    @MockitoBean ArchiveReminderService reminders;
    @MockitoBean HealthObservationService observations;
    @BeforeEach void token() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
            .header("alg","HS256").subject("1").claim("subject_type","user").claim("role","OWNER")
            .claim("app_id","test-app").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build());
    }
    @Test void unknownAndDuplicateSelectorsNeverReachPrivateServices() throws Exception {
        for (String path : List.of(
            "/api/archive/1/structured?user_id=2", "/api/archive/1/reminder?user_id=2",
            "/api/vehicle/reminders?vehicle_id=1&vehicle_id=2",
            "/api/vehicle/health-observations?vehicle_id=1&user_id=2",
            "/api/vehicle/health-observations?vehicle_id=1&vehicle_id=2",
            "/api/archive/1/health-observation?metric=OIL&metric=TIRE",
            "/api/archive/1/health-observation?metric=OIL&user_id=2",
            "/api/vehicle/health-observation-history?vehicle_id=1&metric=OIL&user_id=2",
            "/api/vehicle/health-observation-history?vehicle_id=1&metric=OIL&limit=1&limit=2",
            "/api/vehicle/health-observation-history?vehicle_id=1&metric=OIL&before_revision=1&before_revision=2"))
            mvc.perform(get(path).header("Authorization","Bearer owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(structured,reminders,observations);
    }
    @Test void writesRejectQueryBeforeBodyOrService() throws Exception {
        for (String path : List.of("/api/archive/1/structured","/api/archive/1/reminder","/api/archive/1/health-observation"))
            mvc.perform(put(path+"?user_id=2").header("Authorization","Bearer owner")
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001))
                .andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(structured,reminders,observations);
    }
    @Test void supportedSelectorsStillReachOwnerService() throws Exception {
        when(observations.history(any(),eq(1L),eq("OIL"),eq(3),eq(2))).thenReturn(Map.of("list",List.of()));
        mvc.perform(get("/api/vehicle/health-observation-history?vehicle_id=1&metric=OIL&before_revision=3&limit=2")
            .header("Authorization","Bearer owner")).andExpect(status().isOk());
        verify(observations).history(any(),eq(1L),eq("OIL"),eq(3),eq(2));
    }
    @Test void authenticationStillPrecedesQueryValidation() throws Exception {
        mvc.perform(get("/api/vehicle/reminders?vehicle_id=1&vehicle_id=2"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(structured,reminders,observations);
    }
}
