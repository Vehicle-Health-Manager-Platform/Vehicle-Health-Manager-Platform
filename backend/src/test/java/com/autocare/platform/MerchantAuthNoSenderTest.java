package com.autocare.platform;

import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.identity.MerchantIdentityRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"JWT_SECRET=test-only-secret-with-at-least-32-characters", "WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MerchantAuthNoSenderTest {
    @Autowired MockMvc mvc;
    @MockitoBean MerchantIdentityRepository merchants;
    @MockitoBean AuthRateLimiter limiter;

    @Test
    void codeRequestCannotSucceedWithoutConfiguredSmsProvider() throws Exception {
        when(merchants.byAccount("shop-owner")).thenReturn(Optional.of(new MerchantIdentityRepository.Merchant(
            31, 9, "MERCHANT", "ACTIVE", false, 1, false, "13800138000",
            new BCryptPasswordEncoder().encode("correct-password"))));
        mvc.perform(post("/api/auth/merchant/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"shop-owner\",\"password\":\"correct-password\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value(50300));
    }
}
