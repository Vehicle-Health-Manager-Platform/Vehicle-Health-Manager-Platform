package com.autocare.platform;

import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.IdentityRepository;
import com.autocare.platform.gateway.wechat.WechatCode2SessionClient;
import com.autocare.platform.gateway.wechat.WechatPhoneClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未开启云托管开关时，网关身份端点不得存在，避免在自建/本机环境误暴露只信任请求头的入口。
 */
@SpringBootTest(properties = {"JWT_SECRET=test-only-secret-with-at-least-32-characters", "WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CloudRunDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean WechatCode2SessionClient wechat;
    @MockitoBean WechatPhoneClient phone;
    @MockitoBean IdentityRepository identities;
    @MockitoBean AuthSessionRepository sessions;
    @MockitoBean AuthRateLimiter limiter;

    @Test
    void cloudLoginEndpointDoesNotExistWhenSwitchIsOff() throws Exception {
        mvc.perform(post("/api/auth/cloud-login").contentType(MediaType.APPLICATION_JSON)
                .header("X-WX-SOURCE", "wxcloudrun").header("X-WX-APPID", "test-app")
                .header("X-WX-OPENID", "openid-cloud").content("{\"role\":\"owner\"}"))
            .andExpect(status().isNotFound());
    }
}
