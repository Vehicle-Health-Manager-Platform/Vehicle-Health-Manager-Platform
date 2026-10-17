package com.autocare.platform.order;
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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class BusinessDashboardHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean BusinessDashboard dashboards;
 @BeforeEach void token(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op"),shop=t.equals("merchant")||t.equals("staff");var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":shop?"staff_account":"user").claim("role",op?"OPERATOR":shop?t.equals("staff")?"STAFF":"MERCHANT":"OWNER").claim("app_id",op?"operator-account":shop?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(shop)b.claim("merchant_id",1L);return b.build();});}
 @Test void exactQueriesAndRolesAreRejectedBeforeDashboardSource()throws Exception{mvc.perform(get("/api/admin/business/merchants")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/business/merchants").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/business/dashboard?period_month=2026-11").header("Authorization","Bearer staff")).andExpect(status().isForbidden());for(String q:List.of("merchant_id=1","merchant_id=1&period_month=2026-13","merchant_id=1&merchant_id=2&period_month=2026-11","merchant_id=1&period_month=2026-11&page=1"))mvc.perform(get("/api/admin/business/dashboard?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/business/dashboard?period_month=2026-11&merchant_id=2").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());verifyNoInteractions(dashboards);}
 @Test void onlyNoStoreReadEnvelopesAreExposed()throws Exception{when(dashboards.dashboard(any(com.autocare.platform.gateway.identity.OperatorActor.class),eq(1L),eq("2026-11"))).thenReturn(Map.of("period_month","2026-11"));when(dashboards.dashboard(any(com.autocare.platform.service.MerchantActor.class),eq("2026-11"))).thenReturn(Map.of("period_month","2026-11"));for(String path:List.of("/api/admin/business/dashboard?merchant_id=1&period_month=2026-11","/api/merchant/business/dashboard?period_month=2026-11"))mvc.perform(get(path).header("Authorization",path.contains("/admin/")?"Bearer op":"Bearer merchant")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.period_month").value("2026-11"));}
}
