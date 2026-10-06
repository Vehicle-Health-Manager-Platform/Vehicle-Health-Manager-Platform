package com.autocare.platform;

import com.autocare.platform.service.ServiceCatalog;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class ServiceCatalogHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ServiceCatalog catalog;
    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token=call.getArgument(0);
            if(token.equals("expired")) throw new BadJwtException("Expired");
            var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
            if(!token.equals("local")) builder.claim("subject_type",token.equals("binding")?"wechat_binding":token.equals("owner")?"user":"staff_account");
            if(!token.equals("missing-session")) builder.claim("jti","catalog-session");
            return builder.claim("role",token.equals("staff")?"TECHNICIAN":token.equals("merchant")?"MERCHANT":"OWNER").build();
        });
    }
    @Test void rejectsAnonymousExpiredAndOtherRoles() throws Exception {
        for(String path:List.of("/api/service/projects","/api/service/project/1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer expired")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100)).andExpect(header().string("Cache-Control","no-store"));
            for(String token:List.of("local","binding","staff","merchant","missing-session"))
                mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(catalog);
    }
    @Test void validatesQueriesAndIdsBeforeDatabase() throws Exception {
        for(String path:List.of("/api/service/projects?category=0","/api/service/projects?category=7",
            "/api/service/projects?page=0","/api/service/projects?page_size=101","/api/service/projects?page=abc",
            "/api/service/project/0","/api/service/project/9007199254740992","/api/service/project/abc"))
            mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }
    @Test void categoryPageAndOwnerReachCatalog() throws Exception {
        when(catalog.list(any(),eq(2),eq(3),eq(5))).thenReturn(Map.of("items",List.of(),"total",0,"page",3,"page_size",5));
        mvc.perform(get("/api/service/projects?category=2&page=3&page_size=5").header("Authorization","Bearer owner"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.page").value(3));
        verify(catalog).list(argThat(owner->owner.id()==1 && owner.session().equals("catalog-session")),eq(2),eq(3),eq(5));
    }
    @Test void detailHasExactPricesAndSafeFailure() throws Exception {
        when(catalog.detail(any(),eq(1L))).thenReturn(Map.of("id",1,"base_price_low","0.10","base_price_high","99999999.99"));
        mvc.perform(get("/api/service/project/1").header("Authorization","Bearer owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.base_price_low").value("0.10"));
        when(catalog.detail(any(),eq(2L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database URL"));
        mvc.perform(get("/api/service/project/2").header("Authorization","Bearer owner"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.message").value("服务项目暂不可用，请稍后重试"));
    }
}
