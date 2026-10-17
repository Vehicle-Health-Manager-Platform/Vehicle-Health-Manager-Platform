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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class AssessmentViewHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean AssessmentViews views;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean op=t.equals("op"),shop=t.equals("merchant")||t.equals("staff");var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",op?"operator_account":shop?"staff_account":"user").claim("role",op?"OPERATOR":shop?t.equals("staff")?"STAFF":"MERCHANT":"OWNER").claim("app_id",op?"operator-account":shop?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(shop)b.claim("merchant_id",1L);return b.build();});}
 @Test void roleAndStrictSelectorsRejectCrossStoreOrEmployeeRequestsBeforeService()throws Exception{mvc.perform(get("/api/admin/assessment/month?merchant_id=1&period_month=2026-10")).andExpect(status().isUnauthorized());for(String path:List.of("/api/merchant/assessment/month?period_month=2026-10","/api/merchant/assessment/history?period_month=2026-10","/api/merchant/assessment/snapshots/1","/api/merchant/assessment/snapshots/1/facts"))mvc.perform(get(path).header("Authorization","Bearer staff")).andExpect(status().isForbidden());for(String q:List.of("period_month=2026-10&merchant_id=1&merchant_id=2","period_month=2026-13&merchant_id=1","period_month=2026-10&merchant_id=1&page=1","merchant_id=1"))mvc.perform(get("/api/admin/assessment/month?"+q).header("Authorization","Bearer op")).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/assessment/staff?period_month=2026-10&staff_id=2").header("Authorization","Bearer staff")).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/assessment/history?period_month=2026-10&merchant_id=2").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());verifyNoInteractions(views);}
 @Test void preciseMonthAndReadonlyPagesReturnSingleNoStoreEnvelope()throws Exception{when(views.context(any(com.autocare.platform.gateway.identity.OperatorActor.class),eq(1L),eq("2026-10"))).thenReturn(Map.of("revision",21));when(views.staff(any(),eq("2026-10"))).thenReturn(Map.of("state","NO_SNAPSHOT"));mvc.perform(get("/api/admin/assessment/month?period_month=2026-10&merchant_id=1").header("Authorization","Bearer op")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.revision").value(21));mvc.perform(get("/api/merchant/assessment/staff?period_month=2026-10").header("Authorization","Bearer staff")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.state").value("NO_SNAPSHOT"));}
}
