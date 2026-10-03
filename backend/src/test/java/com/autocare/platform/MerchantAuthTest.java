package com.autocare.platform;

import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.IdentityRepository;
import com.autocare.platform.gateway.identity.MerchantIdentityRepository;
import com.autocare.platform.gateway.identity.MerchantSmsSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"JWT_SECRET=test-only-secret-with-at-least-32-characters", "WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MerchantAuthTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean MerchantIdentityRepository merchants;
    @MockitoBean MerchantSmsSender sms;
    @MockitoBean AuthRateLimiter limiter;
    @MockitoBean AuthSessionRepository sessions;
    @MockitoBean IdentityRepository owners;

    @Test
    void passwordAndSmsAreBothRequiredAndMerchantSessionIsCheckedAgain() throws Exception {
        var merchant = new MerchantIdentityRepository.Merchant(31, 9, "MERCHANT", "ACTIVE",
            false, 1, false, "13800138000", new BCryptPasswordEncoder().encode("correct-password"));
        when(merchants.byAccount("shop-owner")).thenReturn(Optional.of(merchant));
        when(merchants.byId(31)).thenReturn(Optional.of(merchant));
        when(sessions.active(anyString())).thenReturn(true);
        AtomicReference<String> issuedCode = new AtomicReference<>();
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(3)).run();
            return null;
        }).when(merchants).issueSmsCode(eq(31L), eq("13800138000"), anyString(), any());
        doAnswer(invocation -> {
            issuedCode.set(invocation.getArgument(1));
            return null;
        }).when(sms).sendLoginCode(eq("13800138000"), anyString());

        mvc.perform(post("/api/auth/merchant/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"shop-owner\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/merchant/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"shop-owner\",\"password\":\"correct-password\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sent").value(true));
        String code = issuedCode.get();
        when(merchants.consumeSmsCode(31L, "13800138000", code)).thenReturn(true).thenReturn(false);
        String login = "{\"account\":\"shop-owner\",\"password\":\"correct-password\",\"sms_code\":\"" + code + "\"}";
        String body = mvc.perform(post("/api/auth/merchant/login").contentType(MediaType.APPLICATION_JSON)
                .content(login)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.role").value("merchant"))
            .andExpect(jsonPath("$.data.user.merchant_id").value(9))
            .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).path("data").path("access_token").asText();
        String refresh = mapper.readTree(body).path("data").path("refresh_token").asText();
        ArgumentCaptor<AuthSessionRepository.Session> captured = ArgumentCaptor.forClass(AuthSessionRepository.Session.class);
        verify(sessions).create(captured.capture(), anyString(), any());
        when(sessions.rotate(anyString(), anyString())).thenReturn(Optional.of(captured.getValue()));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("merchant"));
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/merchant/login").contentType(MediaType.APPLICATION_JSON)
                .content(login)).andExpect(status().isUnauthorized());
        when(merchants.byId(31)).thenReturn(Optional.of(new MerchantIdentityRepository.Merchant(31, 9,
            "MERCHANT", "ACTIVE", false, 2, false, "13800138000", merchant.passwordHash())));
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void technicianAccountCannotBecomeMerchantThroughPasswordLogin() throws Exception {
        when(merchants.byAccount("technician")).thenReturn(Optional.of(new MerchantIdentityRepository.Merchant(
            32, 9, "TECHNICIAN", "ACTIVE", false, 1, false, "13800138001",
            new BCryptPasswordEncoder().encode("correct-password"))));
        mvc.perform(post("/api/auth/merchant/code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"technician\",\"password\":\"correct-password\"}"))
            .andExpect(status().isUnauthorized());
    }
}
