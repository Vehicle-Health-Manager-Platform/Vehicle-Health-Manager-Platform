package com.autocare.platform.order;

import java.time.Instant;
import java.util.Map;
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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters") @AutoConfigureMockMvc
class MerchantOrdersHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean MerchantOrders orders;
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
}
