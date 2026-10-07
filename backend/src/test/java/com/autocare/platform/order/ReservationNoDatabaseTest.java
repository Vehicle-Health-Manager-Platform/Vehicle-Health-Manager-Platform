package com.autocare.platform.order;
import java.time.Instant;
import org.junit.jupiter.api.Test;
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
class ReservationNoDatabaseTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;
    @Test void missingDatabaseIs503()throws Exception{when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean owner=t.equals("owner");return Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("subject_type",owner?"user":"staff_account").claim("role",owner?"OWNER":"MERCHANT").claim("app_id","merchant-account").claim("merchant_id",1).claim("jti","test").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();});mvc.perform(get("/api/order/list").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300));mvc.perform(get("/api/merchant/slots").header("Authorization","Bearer merchant")).andExpect(status().isServiceUnavailable());}
}
