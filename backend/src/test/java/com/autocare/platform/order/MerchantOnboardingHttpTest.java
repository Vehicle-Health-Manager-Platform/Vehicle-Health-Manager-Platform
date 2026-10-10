package com.autocare.platform.order;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters"}) @AutoConfigureMockMvc
class MerchantOnboardingHttpTest {
    @Autowired MockMvc mvc;@MockitoBean MerchantOnboarding service;@MockitoBean JwtDecoder decoder;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").expiresAt(Instant.now().plusSeconds(900)).claim("subject_type",t.equals("operator")?"operator_account":t.equals("owner")?"user":"staff_account").claim("role",t.equals("operator")?"OPERATOR":t.equals("owner")?"OWNER":"MERCHANT").claim("app_id","operator-account").build();});}
    String submit(){return "{\"merchant_name\":\"演示汽修厂\",\"category\":\"REPAIR\",\"region_code\":\"440106\",\"address\":\"广州市天河区演示路1号\",\"contact_phone\":\"13800000000\",\"qualification_file_ids\":[1,2]}";}
    String moderate(){return "{\"revision\":1,\"decision\":\"APPROVE\",\"reason_code\":null}";}
    String quota(){return "{\"region_code\":\"440106\",\"category\":\"REPAIR\",\"max_active\":3}";}
    String key(){return UUID.randomUUID().toString();}
    String ownerJwt="Bearer owner",operatorJwt="Bearer operator";
    static final List<String> ADMIN=List.of("/api/admin/merchant-applications","/api/admin/merchant-applications/1","/api/admin/merchant-quotas");

    @Test void roleIsolationAndAnonymousReject() throws Exception {
        for(String path:ADMIN) mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/merchant-applications/mine")).andExpect(status().isUnauthorized());
        for(String token:List.of("owner","merchant")) for(String path:ADMIN)
            mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant-applications/mine").header("Authorization",operatorJwt)).andExpect(status().isForbidden());
        mvc.perform(post("/api/merchant-applications").header("Authorization",operatorJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(submit())).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/merchant-quotas").header("Authorization",ownerJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(quota())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void strictApplicationBodyAndIdentityOverrideRejected() throws Exception {
        for(String body:List.of("{}",submit()+"{}",
            submit().replace("\"category\":\"REPAIR\"","\"category\":\"UNKNOWN\""),
            submit().replace("\"region_code\":\"440106\"","\"region_code\":\"44010\""),
            submit().replace("\"contact_phone\":\"13800000000\"","\"contact_phone\":\"1380000000\""),
            submit().replace("\"qualification_file_ids\":[1,2]","\"qualification_file_ids\":[]"),
            submit().replace("[1,2]","[1,1]"),
            submit().replace("[1,2]","[0]"),
            submit().replace("[1,2]","[\"1\"]"),
            submit().replace("[1,2]","[1,2,3,4,5,6,7,8,9,10]"),
            submit().replace("\"演示汽修厂\"","\"甲\""),
            submit().replace("\"演示汽修厂\"","\""+"甲".repeat(65)+"\""),
            submit().replace("\"演示汽修厂\"","\" 演示汽修厂\""),
            submit().replace("\"merchant_name\"","\"extra\":1,\"merchant_name\""),
            submit().replace("\"category\":\"REPAIR\"","\"category\":\"REPAIR\",\"category\":\"TIRE\"")))
            mvc.perform(post("/api/merchant-applications").header("Authorization",ownerJwt).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        // 缺少幂等键同样在进入服务前拒绝
        mvc.perform(post("/api/merchant-applications").header("Authorization",ownerJwt)
            .contentType(MediaType.APPLICATION_JSON).content(submit())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void missingIdempotencyKeyAndUnknownQueryParamsRejected() throws Exception {
        mvc.perform(post("/api/merchant-applications").header("Authorization",ownerJwt)
            .contentType(MediaType.APPLICATION_JSON).content(submit())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant-applications?user_id=2").header("Authorization",ownerJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(submit())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant-applications/mine?page=1").header("Authorization",ownerJwt)).andExpect(status().isBadRequest());
        for(String query:List.of("page=0","page_size=51","page=1&unknown=1","page=1&page=2","page=9007199254740992"))
            mvc.perform(get("/api/admin/merchant-applications?"+query).header("Authorization",operatorJwt)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/merchant-applications/1?x=1").header("Authorization",operatorJwt)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/merchant-applications/1/files/2/access?x=1").header("Authorization",operatorJwt)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/merchant-quotas?x=1").header("Authorization",operatorJwt)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/merchant-quotas?x=1").header("Authorization",operatorJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(quota())).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/merchant-quotas").header("Authorization",operatorJwt)
            .contentType(MediaType.APPLICATION_JSON).content(quota())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void strictModerationAndQuotaBodiesRejected() throws Exception {
        for(String body:List.of("{}",moderate().replace("\"revision\":1","\"revision\":0"),
            moderate().replace("\"revision\":1","\"revision\":1.0"),
            moderate().replace("null","\"QUALIFICATION_INCOMPLETE\""),
            moderate().replace("APPROVE","UNKNOWN"),moderate().replace("APPROVE","REJECT"),
            moderate().replace("\"reason_code\":null","\"reason_code\":\"UNKNOWN\""),
            moderate().replace("\"decision\"","\"extra\":1,\"decision\"")))
            mvc.perform(post("/api/admin/merchant-applications/1/moderate").header("Authorization",operatorJwt)
                .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for(String body:List.of("{}",quota().replace("\"max_active\":3","\"max_active\":-1"),
            quota().replace("\"max_active\":3","\"max_active\":100001"),quota().replace("\"max_active\":3","\"max_active\":true"),
            quota().replace("REPAIR","UNKNOWN"),quota().replace("440106","4401"),
            quota().replace("\"category\"","\"extra\":1,\"category\"")))
            mvc.perform(put("/api/admin/merchant-quotas").header("Authorization",operatorJwt)
                .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void noStoreSuccessConflictAndDependencyErrors() throws Exception {
        when(service.pending(any(),eq(1),eq(20))).thenReturn(Map.of("items",List.of(),"total",0L,"page",1,"page_size",20));
        when(service.mine(any())).thenReturn(Map.of());
        when(service.detail(any(),eq(1L))).thenReturn(Map.of());
        when(service.quotas(any())).thenReturn(Map.of("items",List.of()));
        when(service.submit(any(),anyString(),any())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(service.moderate(any(),eq(1L),anyString(),any())).thenReturn(JsonNodeFactory.instance.objectNode());
        when(service.setQuota(any(),anyString(),any())).thenReturn(JsonNodeFactory.instance.objectNode());
        mvc.perform(get("/api/admin/merchant-applications").header("Authorization",operatorJwt))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/admin/merchant-quotas").header("Authorization",operatorJwt))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/admin/merchant-applications/1").header("Authorization",operatorJwt)).andExpect(status().isOk());
        mvc.perform(get("/api/merchant-applications/mine").header("Authorization",ownerJwt)).andExpect(status().isOk());
        mvc.perform(post("/api/merchant-applications").header("Authorization",ownerJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(submit())).andExpect(status().isOk());
        mvc.perform(put("/api/admin/merchant-quotas").header("Authorization",operatorJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(quota())).andExpect(status().isOk());
        when(service.moderate(any(),eq(1L),anyString(),any())).thenThrow(new FulfillmentConflict(49010,"该区域品类配额已满"));
        mvc.perform(post("/api/admin/merchant-applications/1/moderate").header("Authorization",operatorJwt)
            .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(moderate()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(49010))
            .andExpect(jsonPath("$.message").value("该区域品类配额已满"));
        when(service.pending(any(),eq(1),eq(20))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL detail"));
        mvc.perform(get("/api/admin/merchant-applications").header("Authorization",operatorJwt))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300))
            .andExpect(header().string("Cache-Control","no-store"));
        when(service.detail(any(),eq(1L))).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"入驻申请或资质文件不存在"));
        mvc.perform(get("/api/admin/merchant-applications/1").header("Authorization",operatorJwt))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }

    /** The write scopes return the stored envelope; wrapping it again breaks every client. */
    static com.fasterxml.jackson.databind.node.ObjectNode storedEnvelope(){
        var application=JsonNodeFactory.instance.objectNode();
        application.put("application_id",7L);application.put("revision",2);application.put("status","PENDING_REVIEW");
        var data=JsonNodeFactory.instance.objectNode();
        data.set("application",application);data.put("revision",2);
        var node=JsonNodeFactory.instance.objectNode();
        node.put("code",0);node.put("message","success");node.set("data",data);node.put("request_id","synthetic-request");
        return node;
    }

    @Test void writeScopesReturnSingleEnvelope() throws Exception {
        when(service.submit(any(),anyString(),any())).thenReturn(storedEnvelope());
        when(service.moderate(any(),eq(1L),anyString(),any())).thenReturn(storedEnvelope());
        when(service.setQuota(any(),anyString(),any())).thenReturn(storedEnvelope());
        mvc.perform(post("/api/merchant-applications").header("Authorization",ownerJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(submit()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.application.application_id").value(7))
            .andExpect(jsonPath("$.data.code").doesNotExist())
            .andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(post("/api/admin/merchant-applications/1/moderate").header("Authorization",operatorJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(moderate()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.application.application_id").value(7))
            .andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(put("/api/admin/merchant-quotas").header("Authorization",operatorJwt).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(quota()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.application.application_id").value(7))
            .andExpect(jsonPath("$.data.data").doesNotExist());
    }
}
