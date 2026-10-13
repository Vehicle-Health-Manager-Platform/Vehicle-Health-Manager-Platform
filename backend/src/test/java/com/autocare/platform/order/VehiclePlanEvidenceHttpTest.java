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
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class VehiclePlanEvidenceHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean VehiclePlanEvidence service;
    @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(call->{String t=call.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",t.equals("owner")?"user":"operator_account").claim("role",t.equals("owner")?"OWNER":"OPERATOR").claim("app_id",t.equals("owner")?"test-app":"operator-account").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String body(){return "{\"vehicle_id\":1,\"project_ids\":[1]}";}
    String rule(){return "{\"verdict\":\"APPLICABLE\",\"source_reference\":\"合成来源\",\"expected_version\":0}";}
    @Test void separateRolesAndAnonymousDenied()throws Exception{mvc.perform(post("/api/ai/service-plan/vehicle-evidence").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());mvc.perform(post("/api/ai/service-plan/vehicle-evidence").header("Authorization","Bearer operator").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());mvc.perform(get("/api/admin/project-applicability/1/1").header("Authorization","Bearer owner")).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void strictEvidenceAndRuleInputsRejectBeforeService()throws Exception{for(String b:List.of("null","[]",body()+"{}",body().replace("[1]","[1,1]"),body().replace("[1]","[0]"),body().replace("[1]","[1,2,3,4,5,6,7,8,9]"),body().replace("\"vehicle_id\":1","\"vehicle_id\":1,\"vehicle_id\":2")))mvc.perform(post("/api/ai/service-plan/vehicle-evidence").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());mvc.perform(post("/api/ai/service-plan/vehicle-evidence?user_id=1").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());for(String b:List.of(rule().replace("APPLICABLE","MUST"),rule().replace("合成来源"," "),rule().replace(":0",":-1")))mvc.perform(put("/api/admin/project-applicability/1/1").header("Authorization","Bearer operator").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());mvc.perform(put("/api/admin/project-applicability/1/1").header("Authorization","Bearer operator").contentType(MediaType.APPLICATION_JSON).content(rule())).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void noStoreSingleEnvelopeAndSafeDatabaseError()throws Exception{when(service.evidence(any(),any())).thenReturn(Map.of("applicability",List.of()));mvc.perform(post("/api/ai/service-plan/vehicle-evidence").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.applicability").isArray());when(service.readRule(any(),anyLong(),anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));mvc.perform(get("/api/admin/project-applicability/1/1").header("Authorization","Bearer operator")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("车辆方案证据暂不可用，请稍后重试"));}
}
