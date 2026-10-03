package com.autocare.platform;

import com.autocare.platform.gateway.identity.IdentityRepository;
import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.wechat.WechatCode2SessionClient;
import com.autocare.platform.gateway.wechat.WechatPhoneClient;
import com.autocare.platform.gateway.wechat.WechatExchangeException;
import com.autocare.platform.gateway.wechat.WechatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"JWT_SECRET=test-only-secret-with-at-least-32-characters", "WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class WechatAuthTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean WechatCode2SessionClient wechat;
    @MockitoBean WechatPhoneClient phone;
    @MockitoBean IdentityRepository identities;
    @MockitoBean AuthSessionRepository sessions;
    @MockitoBean AuthRateLimiter limiter;

    @BeforeEach
    void activeSession() {
        when(sessions.active(anyString())).thenReturn(true);
    }

    @Test
    void firstAndRepeatOwnerLoginReuseDatabaseIdentity() throws Exception {
        when(wechat.exchange("first")).thenReturn(new WechatSession("openid-a", ""));
        when(wechat.exchange("again")).thenReturn(new WechatSession("openid-a", ""));
        when(identities.createOwnerOrRead("openid-a"))
            .thenReturn(new IdentityRepository.Owner(1001, null, 1, false));
        when(identities.ownerById(1001)).thenReturn(Optional.of(new IdentityRepository.Owner(1001, null, 1, false)));
        String token = login("first", "owner");
        mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"again\",\"role\":\"owner\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.id").value(1001))
            .andExpect(jsonPath("$.data.user.phone_bound").value(false));
        mvc.perform(get("/api/demo/vehicles/1001").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void unboundTechnicianGetsOnlyBindingCredentialAndBindingChecksServerRecord() throws Exception {
        when(wechat.exchange("tech")).thenReturn(new WechatSession("openid-tech", ""));
        when(identities.technicianByOpenid("test-app", "openid-tech"))
            .thenReturn(Optional.empty());
        String body = mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"tech\",\"role\":\"technician\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("BIND_REQUIRED"))
            .andExpect(jsonPath("$.data.access_token").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String bindingToken = mapper.readTree(body).path("data").path("binding_token").asText();
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + bindingToken))
            .andExpect(status().isForbidden());
        when(identities.bindTechnician("test-app", "openid-tech", "employee-123"))
            .thenReturn(new IdentityRepository.Technician(7, 31, 9, "TECHNICIAN", "ACTIVE", false, 1, false));
        mvc.perform(post("/api/auth/technician/bind").header("Authorization", "Bearer " + bindingToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"employee_code\":\"employee-123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.id").value(31))
            .andExpect(jsonPath("$.data.user.merchant_id").value(9));
    }

    @Test
    void disabledOwnerAndInvalidRoleAreRejected() throws Exception {
        when(wechat.exchange("disabled")).thenReturn(new WechatSession("openid-disabled", ""));
        when(identities.createOwnerOrRead("openid-disabled"))
            .thenReturn(new IdentityRepository.Owner(22, null, 2, false));
        mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"disabled\",\"role\":\"owner\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"x\",\"role\":\"merchant\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCodeAndRateLimitKeepTheirHttpStatus() throws Exception {
        when(wechat.exchange("bad")).thenThrow(new WechatExchangeException(
            WechatExchangeException.Reason.INVALID_CODE, "微信临时登录凭证无效"));
        when(wechat.exchange("fast")).thenThrow(new WechatExchangeException(
            WechatExchangeException.Reason.RATE_LIMITED, "微信登录请求过于频繁"));
        mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"bad\",\"role\":\"owner\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"fast\",\"role\":\"owner\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void technicianTokenIsRejectedAfterBindingOrMerchantBecomesInactive() throws Exception {
        var active = new IdentityRepository.Technician(7, 31, 9, "TECHNICIAN", "ACTIVE", false, 1, false);
        var disabled = new IdentityRepository.Technician(7, 31, 9, "TECHNICIAN", "ACTIVE", false, 2, false);
        when(wechat.exchange("bound")).thenReturn(new WechatSession("openid-tech", ""));
        when(identities.technicianByOpenid("test-app", "openid-tech")).thenReturn(Optional.of(active));
        when(identities.technicianByBindingId(7)).thenReturn(Optional.of(active), Optional.of(disabled));
        String token = login("bound", "technician");
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerPhoneCodeIsExchangedForCurrentOpenidAndOtherRoleIsForbidden() throws Exception {
        when(wechat.exchange("owner-phone")).thenReturn(new WechatSession("openid-owner", ""));
        when(identities.createOwnerOrRead("openid-owner"))
            .thenReturn(new IdentityRepository.Owner(1001, null, 1, false));
        when(identities.ownerById(1001)).thenReturn(Optional.of(new IdentityRepository.Owner(1001, null, 1, false)));
        when(identities.ownerOpenidById(1001)).thenReturn(Optional.of("openid-owner"));
        when(phone.exchange("phone-code", "openid-owner")).thenReturn("13800138000");
        when(identities.bindOwnerPhone(1001, "13800138000"))
            .thenReturn(new IdentityRepository.Owner(1001, "13800138000", 1, false));
        String token = login("owner-phone", "owner");
        mvc.perform(post("/api/auth/phone/bind").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"phone-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone_bound").value(true))
            .andExpect(jsonPath("$.data.phone_masked").value("138****8000"));
        verify(phone).exchange("phone-code", "openid-owner");
        mvc.perform(post("/api/auth/phone/bind").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"phone-code\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndLogoutRevokesSession() throws Exception {
        when(wechat.exchange("owner-session")).thenReturn(new WechatSession("openid-session", ""));
        when(identities.createOwnerOrRead("openid-session"))
            .thenReturn(new IdentityRepository.Owner(1001, null, 1, false));
        when(identities.ownerById(1001)).thenReturn(Optional.of(new IdentityRepository.Owner(1001, null, 1, false)));
        String body = mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"owner-session\",\"role\":\"owner\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String accessToken = mapper.readTree(body).path("data").path("access_token").asText();
        String refreshToken = mapper.readTree(body).path("data").path("refresh_token").asText();
        when(sessions.rotate(anyString(), anyString())).thenReturn(Optional.of(new AuthSessionRepository.Session(
            "00000000-0000-0000-0000-000000000001", "user", 1001, "OWNER", "test-app", null, null)));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.access_token").exists())
            .andExpect(jsonPath("$.data.refresh_token").exists());
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.revoked").value(true));
        verify(sessions).revoke(anyString());
    }

    private String login(String code, String role) throws Exception {
        String body = mvc.perform(post("/api/auth/wx-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data").path("access_token").asText();
    }
}
