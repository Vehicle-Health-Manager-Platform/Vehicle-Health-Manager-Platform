package com.autocare.platform;

import com.autocare.platform.service.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class MerchantQuotesHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean MerchantQuotes quotes;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{
        String token=call.getArgument(0);boolean owner=token.equals("owner");
        var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("jti","test-session")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
        return builder.claim("subject_type",owner?"user":token.equals("binding")?"wechat_binding":"staff_account")
            .claim("role",owner?"OWNER":token.equals("technician")?"TECHNICIAN":"MERCHANT")
            .claim("app_id","merchant-account").claim("merchant_id",2).build();
    });}
    @Test void roleAndAnonymousBoundaries() throws Exception {
        for(String path:List.of("/api/merchant/projects","/api/merchant/standard-projects")){
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for(String token:List.of("owner","technician","binding"))mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/service/project/1/merchants").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());
        mvc.perform(post("/api/merchant/projects").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(quotes);
    }
    @Test void exactInputRejectsMerchantOverrideAndBadMoney() throws Exception {
        for(String price:List.of("0.00","-1.00","1.1","1.001","100000000.00","1e2","01.00"))
            mvc.perform(post("/api/merchant/projects").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"standard_project_id\":1,\"price\":\""+price+"\",\"status\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/projects").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"standard_project_id\":1,\"price\":\"1.00\",\"status\":1,\"merchant_id\":99}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/service/project/1/merchants?sort=distance").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        verifyNoInteractions(quotes);
    }
    @Test void onlyAuthenticatedMerchantScopeReachesSave() throws Exception {
        when(quotes.save(any(),anyString(),any())).thenReturn(mapper.readTree("{\"code\":0,\"data\":{\"version\":1}}"));
        mvc.perform(post("/api/merchant/projects").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"standard_project_id\":1,\"price\":\"0.01\",\"status\":1}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(quotes).save(argThat(actor->actor.staffId()==1 && actor.merchantId()==2),anyString(),argThat(input->input.price().toPlainString().equals("0.01")));
    }
    @Test void ownerSortAndDatabaseFailureAreSafe() throws Exception {
        when(quotes.ownerList(any(),eq(1L),eq("price_desc"),eq(2),eq(20))).thenReturn(Map.of("items",List.of(),"total",0,"page",2,"page_size",20));
        mvc.perform(get("/api/service/project/1/merchants?sort=price_desc&page=2").header("Authorization","Bearer owner")).andExpect(status().isOk());
        when(quotes.ownList(any(),anyInt(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));
        mvc.perform(get("/api/merchant/projects").header("Authorization","Bearer merchant")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("报价服务暂不可用，请稍后重试"));
    }
}
