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
class OrderReviewsHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean OrderReviews service;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);return Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("subject_type",token.equals("owner")?"user":token.equals("binding")?"wechat_binding":"staff_account").claim("role",token.equals("owner")?"OWNER":token.equals("merchant")?"MERCHANT":"TECHNICIAN").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String key(){return UUID.randomUUID().toString();}
    String body(){return "{\"order_id\":1,\"rating\":5,\"content\":\"真实评价\",\"photo_file_ids\":[]}";}
    @Test void wrongRolesAndAnonymousCannotInvokeService()throws Exception{
        mvc.perform(post("/api/order/review").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());
        for(String token:List.of("merchant","tech","binding")){mvc.perform(post("/api/order/review").header("Authorization","Bearer "+token).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());mvc.perform(get("/api/order/1/review").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());}verifyNoInteractions(service);
    }
    @Test void duplicateTrailingExtraAndInvalidFieldInputsReject()throws Exception{
        var cases=new ArrayList<String>(List.of("null","[]","{}",body()+"{}",body().replace("\"rating\":5","\"rating\":5,\"rating\":1"),body().replace("\"rating\":5","\"rating\":5,\"user_id\":1")));
        for(String v:List.of("0","6","1.5","5.0","\"5\"","null","999999999999999999999"))cases.add(body().replace("\"rating\":5","\"rating\":"+v));
        for(String v:List.of("0","9007199254740992","1.5","\"1\""))cases.add(body().replace("\"order_id\":1","\"order_id\":"+v));
        for(String v:List.of("null","[1,1]","[1,2,3,4]","[0]","[1.5]","[\"1\"]"))cases.add(body().replace("\"photo_file_ids\":[]","\"photo_file_ids\":"+v));
        cases.add(body().replace("真实评价"," "));cases.add(body().replace("真实评价","🙂".repeat(501)));cases.add(body().replace("真实评价","a".repeat(8200)));
        for(String b:cases)mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));verifyNoInteractions(service);
    }
    @Test void keyQueryAndPathCannotOverrideScope()throws Exception{
        mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/order/review?merchant_id=1").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/order/0/review").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());mvc.perform(get("/api/order/1/review?user_id=1").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void validReadAndWriteUseOwnerAndNoStore()throws Exception{
        when(service.submit(any(),anyString(),any())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1))));when(service.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));
        mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/order/1/review").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(service).detail(isA(com.autocare.platform.vehicle.VehicleOwner.class),eq(1L));
    }
    @Test void conflictsAndDatabaseFailureHaveSafeCodesAndNoStore()throws Exception{
        when(service.submit(any(),anyString(),any())).thenThrow(new FulfillmentConflict(44001,"缺少核销资格"));mvc.perform(post("/api/order/review").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(44001)).andExpect(header().string("Cache-Control","no-store"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret database details")).when(service).detail(any(),eq(1L));mvc.perform(get("/api/order/1/review").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(jsonPath("$.message").value("评价服务暂不可用，请使用原请求重试"));
    }
}
