package com.autocare.platform;

import com.autocare.platform.service.*;
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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class MerchantDiscoveryHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean MerchantDiscovery discovery;
    @MockitoBean MerchantQuotes quotes;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String role=call.getArgument(0);
        return Jwt.withTokenValue(role).header("alg","HS256").subject("1").claim("jti","test-session")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).claim("subject_type",role.equals("OWNER")?"user":"staff_account").claim("role",role).build();});}
    @Test void anonymousAndNonOwnerCannotReadMinimalStorefront() throws Exception {
        mvc.perform(get("/api/service/merchant/1")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        for(String role:List.of("MERCHANT","TECHNICIAN","OPERATOR"))mvc.perform(get("/api/service/merchant/1").header("Authorization","Bearer "+role)).andExpect(status().isForbidden());
        verifyNoInteractions(discovery);
    }
    @Test void invalidPagesIdsAndRegionsRejectBeforeDatabase() throws Exception {
        for(String path:List.of("/api/service/merchant/0","/api/service/merchant/1?project_id=0","/api/service/merchant/1?page=0","/api/service/merchant/1?page_size=101"))
            mvc.perform(get(path).header("Authorization","Bearer OWNER")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/service/project/11/merchants").param("region_code","440305 ").header("Authorization","Bearer OWNER")).andExpect(status().isBadRequest());
        verifyNoInteractions(discovery,quotes);
    }
    @Test void ownerParametersReachServiceAndFailuresArePrivateAndNoStore() throws Exception {
        when(discovery.detail(any(),eq(1L),eq(11L),eq(2),eq(20))).thenReturn(Map.of("merchant",Map.of("merchant_id",1),"offers",Map.of()));
        mvc.perform(get("/api/service/merchant/1?project_id=11&page=2").header("Authorization","Bearer OWNER")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(discovery).detail(argThat(owner->owner.id()==1),eq(1L),eq(11L),eq(2),eq(20));
        when(quotes.ownerList(any(),eq(11L),eq("price_asc"),eq(1),eq(20),eq("440305"))).thenReturn(Map.of("items",List.of(),"total",0,"page",1,"page_size",20));
        mvc.perform(get("/api/service/project/11/merchants?region_code=440305").header("Authorization","Bearer OWNER")).andExpect(status().isOk());
        verify(quotes).ownerList(any(),eq(11L),eq("price_asc"),eq(1),eq(20),eq("440305"));
        when(discovery.detail(any(),eq(1L),isNull(),eq(1),eq(20))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL and phone"));
        mvc.perform(get("/api/service/merchant/1").header("Authorization","Bearer OWNER")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("门店详情暂不可用，请稍后重试"));
    }
}
