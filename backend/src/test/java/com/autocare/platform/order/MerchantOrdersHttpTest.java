package com.autocare.platform.order;

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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters") @AutoConfigureMockMvc
class MerchantOrdersHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean MerchantOrders orders;@MockitoBean OrderFulfillment fulfillment;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean owner=t.equals("owner");return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",owner?"user":"staff_account").claim("role",owner?"OWNER":"MERCHANT").claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","test").expiresAt(Instant.now().plusSeconds(600)).build();});}
    @Test void rolesAndStrictQuery()throws Exception{
        mvc.perform(get("/api/merchant/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/merchant/orders").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
        for(String query:new String[]{"?merchant_id=2","?date=2026-02-30","?status=WRONG","?page=0","?page_size=101","?status=PAID&status=CLOSED"})
            mvc.perform(get("/api/merchant/orders"+query).header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }
    @Test void callsOnlyAuthenticatedMerchantAndHidesDatabaseErrors()throws Exception{
        when(orders.list(any(),eq("PAID"),eq("2026-10-08"),eq(2),eq(20))).thenReturn(Map.of("items",new Object[]{},"total",0,"page",2,"page_size",20));
        mvc.perform(get("/api/merchant/orders?status=PAID&date=2026-10-08&page=2").header("Authorization","Bearer merchant"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.page").value(2));
        when(orders.detail(any(),eq(9L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));
        mvc.perform(get("/api/merchant/orders/9").header("Authorization","Bearer merchant"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("本店订单暂不可用，请稍后重试"));
    }
    @Test void acceptsEveryDeclaredStatusFilter()throws Exception{
        for(String status:OrderStatus.ALL){
            when(orders.list(any(),eq(status),any(),eq(1),eq(20))).thenReturn(Map.of("items",List.of(),"total",0,"page",1,"page_size",20));
            mvc.perform(get("/api/merchant/orders?status="+status).header("Authorization","Bearer merchant")).andExpect(status().isOk());
        }
    }
    @Test void actionNeedsRoleKeyShapeAndPositiveId()throws Exception{
        String body="{\"action\":\"RECEIVE\"}";
        mvc.perform(post("/api/merchant/orders/1/actions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/merchant/orders/1/actions").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/merchant/orders/1/actions").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/orders/1/actions").header("Authorization","Bearer merchant").header("Idempotency-Key","not-a-uuid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for(String malformed:List.of("{}","{\"action\":5}","{\"action\":\"RECEIVE\",\"extra\":1}","{\"action\":\"RECEIVE\",\"note\":7}","{\"note\":\"x\"}","{\"action\":\"RECEIVE\",\"note\":\"x\",\"extra\":1}"))
            mvc.perform(post("/api/merchant/orders/1/actions").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(malformed)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/orders/0/actions").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(fulfillment);
    }
    @Test void actionConflictsSurfaceTheStableCode()throws Exception{
        when(fulfillment.apply(any(),anyString(),eq(9L),eq(OrderStatus.RECEIVE),any())).thenThrow(new FulfillmentConflict(43001,"接车检查未完成，请先完成接车检查"));
        mvc.perform(post("/api/merchant/orders/9/actions").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RECEIVE\"}"))
            .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value(43001)).andExpect(jsonPath("$.message").value("请通过接车检查提交完整接车单"));
        when(fulfillment.apply(any(),anyString(),eq(9L),eq(OrderStatus.START_SERVICE),any())).thenThrow(new FulfillmentConflict(40905,"当前订单状态不支持该操作"));
        mvc.perform(post("/api/merchant/orders/9/actions").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START_SERVICE\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40905));
        when(fulfillment.apply(any(),anyString(),eq(9L),eq(OrderStatus.START_SERVICE),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));
        mvc.perform(post("/api/merchant/orders/9/actions").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START_SERVICE\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("本店订单暂不可用，请稍后重试"));
    }
}
