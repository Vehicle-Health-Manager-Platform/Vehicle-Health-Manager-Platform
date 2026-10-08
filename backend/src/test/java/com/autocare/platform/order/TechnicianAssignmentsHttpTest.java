package com.autocare.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class TechnicianAssignmentsHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean TechnicianAssignments service;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);
        var b=Jwt.withTokenValue(token).header("alg","HS256").subject("12").claim("subject_type",token.equals("owner")?"user":token.equals("binding")?"wechat_binding":"staff_account")
            .claim("role",token.equals("owner")?"OWNER":token.equals("merchant")?"MERCHANT":"TECHNICIAN")
            .claim("app_id",token.equals("merchant")?"merchant-account":token.equals("wrong-app")?"other":"test-app")
            .claim("merchant_id",1).claim("jti","session").expiresAt(Instant.now().plusSeconds(600));
        if(!token.equals("merchant") && !token.equals("missing-binding")){if(token.equals("fraction"))b.claim("binding_id",1.5);else b.claim("binding_id",2);}
        return b.build();});}
    String key(){return UUID.randomUUID().toString();}
    @Test void roleAndBindingScopeAreStrict()throws Exception{
        mvc.perform(get("/api/tech/orders")).andExpect(status().isUnauthorized());
        for(String token:List.of("owner","merchant","binding","wrong-app","fraction","missing-binding"))mvc.perform(get("/api/tech/orders").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/technicians").header("Authorization","Bearer tech")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void pagingRejectsUnknownDuplicateAndInvalidParameters()throws Exception{
        for(String q:List.of("page=0","page=01","page=1000001","page_size=101","page=1&page=2","technician_id=12","assignment_status=PENDING","assignment_status="))
            mvc.perform(get("/api/tech/orders?"+q).header("Authorization","Bearer tech")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/technicians?assignment_status=ASSIGNED").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void assignRequiresKeyAndStrictIntegerBody()throws Exception{
        mvc.perform(post("/api/merchant/orders/1/assign").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content("{\"technician_id\":12}")).andExpect(status().isBadRequest());
        for(String body:List.of("{}","{\"technician_id\":\"12\"}","{\"technician_id\":1.5}","{\"technician_id\":0}","{\"technician_id\":9007199254740992}","{\"technician_id\":12,\"status\":\"IN_SERVICE\"}"))
            mvc.perform(post("/api/merchant/orders/1/assign").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void acceptRejectsSpoofedBodyAndResourceIds()throws Exception{
        for(String body:List.of("null","[]","{\"technician_id\":12}","{\"status\":\"IN_SERVICE\"}"))mvc.perform(post("/api/tech/orders/1/accept").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tech/orders/0").header("Authorization","Bearer tech")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tech/orders/1?merchant_id=1").header("Authorization","Bearer tech")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tech/orders/1/accept").header("Authorization","Bearer tech").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void allReadsUseNoStoreAndCorrectActors()throws Exception{
        when(service.candidates(any(),eq(1),eq(20))).thenReturn(Map.of("items",List.of()));
        when(service.merchantDetail(any(),eq(1L))).thenReturn(Collections.singletonMap("assignment",null));
        when(service.list(any(),isNull(),eq(1),eq(20))).thenReturn(Map.of("items",List.of()));when(service.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));
        for(String path:List.of("/api/merchant/technicians","/api/merchant/orders/1/assignment","/api/tech/orders","/api/tech/orders/1"))mvc.perform(get(path).header("Authorization","Bearer "+(path.contains("merchant")?"merchant":"tech"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void writesPreserveReceiptAndConflictCodes()throws Exception{
        when(service.assign(any(),anyString(),eq(1L),eq(12L))).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("changed",true))));
        mvc.perform(post("/api/merchant/orders/1/assign").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"technician_id\":12}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.changed").value(true));
        when(service.accept(any(),anyString(),eq(1L))).thenThrow(new FulfillmentConflict(43003,"车主尚未确认"));
        mvc.perform(post("/api/tech/orders/1/accept").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(43003));
    }
    @Test void databaseFailureDoesNotExposeSql()throws Exception{
        when(service.detail(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));
        mvc.perform(get("/api/tech/orders/1").header("Authorization","Bearer tech")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
