package com.autocare.platform.order;

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
class LocalFinanceHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LocalFinance reports;
    static final String PATH = "/api/admin/reconciliation";
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(c -> {
            boolean op = "op".equals(c.getArgument(0));
            return Jwt.withTokenValue("test").header("alg","HS256").subject("1").claim("jti","session")
                .claim("subject_type",op?"operator_account":"user").claim("role",op?"OPERATOR":"OWNER")
                .claim("app_id",op?"operator-account":"test-app").expiresAt(Instant.now().plusSeconds(600)).build();
        });
    }
    @Test void anonymousAndWrongRoleAreDenied() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH+"?date=2026-10-23&channel=LOCAL_TEST").header("Authorization","Bearer owner"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }
    @Test void missingDuplicateUnknownAndInvalidQueriesAreRejected() throws Exception {
        for(String q:List.of("", "date=2026-10-23", "date=2026-02-30&channel=WECHAT",
            "date=2026-10-23&channel=ALL", "date=2026-10-23&channel=WECHAT&channel=LOCAL_TEST",
            "date=2026-10-23&date=2026-10-24&channel=WECHAT", "date=2026-10-23&channel=WECHAT&page=1",
            "date=2026-1-01&channel=WECHAT", "date=1999-01-01&channel=WECHAT")) {
            mvc.perform(get(PATH+"?"+q).header("Authorization","Bearer op"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001))
                .andExpect(header().string("Cache-Control","no-store"));
        }
        verifyNoInteractions(reports);
    }
    @Test void readUsesOneEnvelopeAndNoStore() throws Exception {
        when(reports.report(any(),eq("2026-10-23"),eq("LOCAL_TEST"))).thenReturn(Map.of("date","2026-10-23"));
        mvc.perform(get(PATH+"?date=2026-10-23&channel=LOCAL_TEST").header("Authorization","Bearer op"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.date").value("2026-10-23")).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void databaseFailureIsSanitized() throws Exception {
        when(reports.report(any(),anyString(),anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db"));
        mvc.perform(get(PATH+"?date=2026-10-23&channel=WECHAT").header("Authorization","Bearer op"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300))
            .andExpect(header().string("Cache-Control","no-store"));
    }
}
