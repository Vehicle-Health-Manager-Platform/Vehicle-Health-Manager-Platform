package com.autocare.platform;

import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.identity.IdentityRepository;
import com.autocare.platform.gateway.wechat.WechatCode2SessionClient;
import com.autocare.platform.gateway.wechat.WechatPhoneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/auth/cloud-login} 的网关身份路径。身份只来自微信云托管注入的请求头。
 */
@SpringBootTest(properties = {"JWT_SECRET=test-only-secret-with-at-least-32-characters",
    "WECHAT_APP_ID=test-app", "WECHAT_CLOUD_RUN_ENABLED=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CloudRunAuthTest {
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cloudLogin(String role,
                                                                                                String source,
                                                                                                String callerAppId,
                                                                                                String openid) {
        var builder = post("/api/auth/cloud-login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"" + role + "\"}");
        if (source != null) builder = builder.header("X-WX-SOURCE", source);
        if (callerAppId != null) builder = builder.header("X-WX-APPID", callerAppId);
        if (openid != null) builder = builder.header("X-WX-OPENID", openid);
        return builder;
    }

    @Test
    void gatewayHeadersIssueOwnerSessionWithoutCallingCode2Session() throws Exception {
        when(identities.createOwnerOrRead("openid-cloud"))
            .thenReturn(new IdentityRepository.Owner(2001, null, 1, false));
        when(identities.ownerById(2001)).thenReturn(Optional.of(new IdentityRepository.Owner(2001, null, 1, false)));
        String body = mvc.perform(cloudLogin("owner", "wxcloudrun", "test-app", "openid-cloud"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.id").value(2001))
            .andExpect(jsonPath("$.data.user.role").value("owner"))
            .andExpect(jsonPath("$.data.user.phone_bound").value(false))
            .andExpect(jsonPath("$.data.access_token").exists())
            .andExpect(jsonPath("$.data.refresh_token").exists())
            .andReturn().getResponse().getContentAsString();
        String accessToken = mapper.readTree(body).path("data").path("access_token").asText();
        // 云托管通道下不应再向微信换码。
        verify(wechat, never()).exchange(anyString());
        mvc.perform(get("/api/demo/vehicles/2001").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());
    }

    @Test
    void forgedOrIncompleteGatewayHeadersAreRejected() throws Exception {
        // 缺少来源头：公网伪造者不会带上它。
        mvc.perform(cloudLogin("owner", null, "test-app", "openid-cloud")).andExpect(status().isForbidden());
        // 其他小程序的 AppID。
        mvc.perform(cloudLogin("owner", "wxcloudrun", "other-app", "openid-cloud")).andExpect(status().isForbidden());
        // 缺少 openid。
        mvc.perform(cloudLogin("owner", "wxcloudrun", "test-app", null)).andExpect(status().isForbidden());
        mvc.perform(cloudLogin("owner", "wxcloudrun", "test-app", "  ")).andExpect(status().isForbidden());
        verify(identities, never()).createOwnerOrRead(anyString());
    }

    @Test
    void unsupportedRoleIsRejectedBeforeAnyIdentityLookup() throws Exception {
        mvc.perform(cloudLogin("merchant", "wxcloudrun", "test-app", "openid-cloud"))
            .andExpect(status().isBadRequest());
        mvc.perform(cloudLogin("", "wxcloudrun", "test-app", "openid-cloud"))
            .andExpect(status().isBadRequest());
        verify(identities, never()).createOwnerOrRead(anyString());
    }

    @Test
    void disabledOwnerIsRejected() throws Exception {
        when(identities.createOwnerOrRead("openid-disabled"))
            .thenReturn(new IdentityRepository.Owner(22, null, 2, false));
        mvc.perform(cloudLogin("owner", "wxcloudrun", "test-app", "openid-disabled"))
            .andExpect(status().isForbidden());
    }

    @Test
    void unboundTechnicianGetsOnlyBindingCredentialThatCannotReachBusinessApi() throws Exception {
        when(identities.technicianByOpenid("test-app", "openid-tech")).thenReturn(Optional.empty());
        String body = mvc.perform(cloudLogin("technician", "wxcloudrun", "test-app", "openid-tech"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("BIND_REQUIRED"))
            .andExpect(jsonPath("$.data.access_token").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String bindingToken = mapper.readTree(body).path("data").path("binding_token").asText();
        mvc.perform(get("/api/private").header("Authorization", "Bearer " + bindingToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void boundTechnicianReusesExistingStaffWithoutRebinding() throws Exception {
        var technician = new IdentityRepository.Technician(7, 31, 9, "TECHNICIAN", "ACTIVE", false, 1, false);
        when(identities.technicianByOpenid("test-app", "openid-tech")).thenReturn(Optional.of(technician));
        when(identities.technicianByBindingId(7)).thenReturn(Optional.of(technician));
        mvc.perform(cloudLogin("technician", "wxcloudrun", "test-app", "openid-tech"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.id").value(31))
            .andExpect(jsonPath("$.data.user.role").value("technician"))
            .andExpect(jsonPath("$.data.user.merchant_id").value(9));
        verify(identities, never()).bindTechnician(anyString(), anyString(), anyString());
    }
}
