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
class OrderRedemptionHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean OrderRedemption service;@MockitoBean com.autocare.platform.file.PrivateFileAccessService files;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);
        var b=Jwt.withTokenValue(token).header("alg","HS256").subject("12").claim("subject_type",token.equals("owner")?"user":token.equals("binding")?"wechat_binding":"staff_account")
            .claim("role",token.equals("owner")?"OWNER":token.equals("merchant")?"MERCHANT":"TECHNICIAN")
            .claim("app_id",token.equals("merchant")?"merchant-account":token.equals("wrong-app")?"other":"test-app")
            .claim("merchant_id",1).claim("jti","session").expiresAt(Instant.now().plusSeconds(600));
        if(!token.equals("merchant") && !token.equals("missing-binding")){if(token.equals("fraction"))b.claim("binding_id",1.5);else b.claim("binding_id",2);}
        return b.build();});}
    String key(){return UUID.randomUUID().toString();}

    String body(){return "{\"code\":\"123456\"}";}
    @Test void rejectsWrongRolesBeforeCallingBusinessService()throws Exception{
        for(String token:List.of("owner","tech","binding","wrong-app","fraction"))mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer "+token).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictCodeKeyIdQueryAndDuplicateJsonAreRejected()throws Exception{
        for(String b:List.of(body()+"{}","{}","null","[]","{\"code\":123456}","{\"code\":\"１２３４５６\"}","{\"code\":\"12345\"}","{\"code\":\"123456\",\"status\":\"COMPLETED\"}","{\"code\":\"123456\",\"code\":\"000000\"}"))mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/orders/0/redemption").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());mvc.perform(get("/api/order/1/redemption?user_id=12").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void writeAndBothReadsAreNoStoreAndUseAuthoritativeActors()throws Exception{
        when(service.redeem(any(),anyString(),eq(1L),any())).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1,"test_mode",true))));when(service.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));
        mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.test_mode").value(true));
        mvc.perform(get("/api/merchant/orders/1/redemption").header("Authorization","Bearer merchant")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get("/api/order/1/redemption").header("Authorization","Bearer owner")).andExpect(status().isOk());verify(service).detail(isA(com.autocare.platform.vehicle.VehicleOwner.class),eq(1L));verify(service).detail(isA(com.autocare.platform.service.MerchantActor.class),eq(1L));
    }
    @Test void rateLimitPreservesRetryHeaderAndNoStore()throws Exception{
        when(service.redeem(any(),anyString(),anyLong(),any())).thenThrow(new OrderRedemption.RateLimited(37));mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","37")).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value(42900));
    }
    @Test void paymentConflictAndDatabaseFailurePreserveSafeError()throws Exception{
        when(service.redeem(any(),anyString(),anyLong(),any())).thenThrow(new FulfillmentConflict(43009,"付款异常"));mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(43009));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret database details")).when(service).redeem(any(),anyString(),anyLong(),any());mvc.perform(post("/api/merchant/orders/1/redeem").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
