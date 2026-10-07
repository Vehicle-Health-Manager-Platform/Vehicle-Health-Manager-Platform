package com.autocare.platform.order;
import java.time.Instant;
import java.util.UUID;
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
class ReservationHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean ReservationOrders orders;@MockitoBean ReservationSlots slots;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean owner=t.equals("owner");return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",owner?"user":"staff_account").claim("role",owner?"OWNER":t.equals("technician")?"TECHNICIAN":"MERCHANT").claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","test").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();});}
    @Test void rolesAndAnonymous()throws Exception{mvc.perform(get("/api/order/list")).andExpect(status().isUnauthorized());mvc.perform(get("/api/order/list").header("Authorization","Bearer merchant")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/slots").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/slots").header("Authorization","Bearer technician")).andExpect(status().isForbidden());verifyNoInteractions(orders,slots);}
    @Test void strictBodyAndMinuteTimezoneValidation()throws Exception{
        for(String body:new String[]{"{\"merchant_project_id\":1,\"quote_version_id\":1,\"vehicle_id\":1,\"slot_id\":1,\"amount\":\"0.01\"}","{\"merchant_project_id\":1,\"quote_version_id\":1,\"vehicle_id\":-1,\"slot_id\":1}"})mvc.perform(post("/api/order/create").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for(String start:new String[]{"2026-10-09T10:00:01+08:00","2026-10-09T10:00:00"})mvc.perform(post("/api/merchant/slots").header("Authorization","Bearer merchant").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"standard_project_id\":1,\"starts_at\":\""+start+"\",\"ends_at\":\"2026-10-09T11:00:00+08:00\",\"capacity\":1}")).andExpect(status().isBadRequest());verifyNoInteractions(orders,slots);
    }
    @Test void explicitConflictAndSafeDatabaseError()throws Exception{when(orders.quote(any(),eq(1L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret SQL"));mvc.perform(get("/api/order/quote/1").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("预约服务暂不可用，请稍后重试"));when(orders.create(any(),anyString(),any())).thenThrow(new ReservationConflict(40901,"报价已更新，请重新确认"));mvc.perform(post("/api/order/create").header("Authorization","Bearer owner").header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{\"merchant_project_id\":1,\"quote_version_id\":1,\"vehicle_id\":1,\"slot_id\":1}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40901)).andExpect(header().string("Cache-Control","no-store"));}
}
