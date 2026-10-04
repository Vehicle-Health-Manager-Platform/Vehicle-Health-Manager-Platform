package com.autocare.platform;

import com.autocare.platform.vehicle.*;
import com.autocare.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class VehicleHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean VehicleService service;
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(invocation -> {
            String token=invocation.getArgument(0);
            var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
            if (!token.equals("local")) builder.claim("subject_type",token.equals("binding")?"wechat_binding":token.equals("staff")?"staff_account":"user");
            if(!token.equals("missing-session")) builder.claim("jti","vehicle-test-session");
            return builder.claim("role",token.equals("staff")?"TECHNICIAN":"OWNER").build();
        });
    }
    @Test void anonymousLocalBindingAndStaffCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/vehicle/list")).andExpect(status().isUnauthorized());
        for(String token:new String[]{"local","staff","binding","missing-session"}) {
            mvc.perform(get("/api/vehicle/list").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/vehicle/add").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void filtersInvalidInputBeforeService() throws Exception {
        for(String body:new String[]{"{}","null","[]","{\"add_type\":4,\"model_id\":1,\"user_id\":2}","{\"add_type\":4,\"model_id\":1,\"vin\":\"I123\"}"})
            mvc.perform(post("/api/vehicle/add").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/vehicle/add").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"add_type\":4,\"model_id\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/vehicle/list?page_size=101").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void listsAreNoStoreAndUseOnlyAuthenticatedOwner() throws Exception {
        when(service.list(any(),eq(1),eq(20))).thenReturn(Map.of("list",List.of(),"page",1,"page_size",20,"total",0));
        mvc.perform(get("/api/vehicle/list?user_id=2").header("Authorization","Bearer owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.total").value(0));
        verify(service).list(argThat(owner->owner.id()==1 && owner.session().equals("vehicle-test-session")),eq(1),eq(20));
    }
    @Test void addNormalizesAndPreservesResponseEnvelope() throws Exception {
        when(service.add(any(),anyString(),any())).thenReturn(mapper.valueToTree(ApiResponse.success(Map.of("vehicle_id",7,"model_name","测试车型","need_archive",true))));
        mvc.perform(post("/api/vehicle/add").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"add_type\":4,\"model_id\":1,\"plate_no\":\"粤b12345\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.vehicle_id").value(7));
        verify(service).add(argThat(owner->owner.id()==1),anyString(),eq(new VehicleInput(1,0,"粤B12345","")));
    }
    @Test void catalogParentAndPaginationReachService() throws Exception {
        when(service.catalog(any(),eq("model"),eq(12L),eq(2),eq(100))).thenReturn(Map.of("list",List.of(),"page",2,"page_size",100,"total",0));
        mvc.perform(get("/api/model/list?series_id=12&page=2&page_size=100").header("Authorization","Bearer owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2));
        mvc.perform(get("/api/model/list").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
    }
    @Test void databaseReadFailureIsSafeUnavailableResponse() throws Exception {
        when(service.list(any(),anyInt(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection string"));
        mvc.perform(get("/api/vehicle/list").header("Authorization","Bearer owner"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value(50300)).andExpect(jsonPath("$.message").value("车辆数据库暂不可用，请稍后重试"));
    }
}
