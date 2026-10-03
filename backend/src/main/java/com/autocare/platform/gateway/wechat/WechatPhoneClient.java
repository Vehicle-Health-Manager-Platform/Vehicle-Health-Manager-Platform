package com.autocare.platform.gateway.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-only exchange of the getPhoneNumber button code. It is distinct from wx.login code. */
@Component
public class WechatPhoneClient {
    private static final URI TOKEN_ENDPOINT = URI.create("https://api.weixin.qq.com/cgi-bin/stable_token");
    private static final URI PHONE_ENDPOINT = URI.create("https://api.weixin.qq.com/wxa/business/getuserphonenumber");
    private final String appId;
    private final String appSecret;
    private final URI tokenEndpoint;
    private final URI phoneEndpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private volatile AccessToken cached;

    private record AccessToken(String value, Instant expiresAt) {}

    @Autowired
    public WechatPhoneClient(@Value("${WECHAT_APP_ID:}") String appId,
                             @Value("${WECHAT_APP_SECRET:}") String appSecret, ObjectMapper mapper) {
        this(appId, appSecret, TOKEN_ENDPOINT, PHONE_ENDPOINT,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper);
    }

    WechatPhoneClient(String appId, String appSecret, URI tokenEndpoint, URI phoneEndpoint,
                      HttpClient http, ObjectMapper mapper) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.tokenEndpoint = tokenEndpoint;
        this.phoneEndpoint = phoneEndpoint;
        this.http = http;
        this.mapper = mapper;
    }

    public String exchange(String code, String openid) {
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new WechatExchangeException(WechatExchangeException.Reason.INVALID_CODE, "手机号授权凭证无效");
        }
        if (openid == null || openid.isBlank()) {
            throw new WechatExchangeException(WechatExchangeException.Reason.INVALID_CODE, "微信身份无效");
        }
        URI uri = URI.create(phoneEndpoint + "?access_token=" + URLEncoder.encode(accessToken(), StandardCharsets.UTF_8));
        JsonNode body = post(uri, Map.of("code", code, "openid", openid));
        int error = body.path("errcode").asInt(-1);
        if (error == 40029 || error == 40013) {
            throw new WechatExchangeException(WechatExchangeException.Reason.INVALID_CODE, "手机号授权凭证无效或已使用");
        }
        if (error == 45011) {
            throw new WechatExchangeException(WechatExchangeException.Reason.RATE_LIMITED, "手机号授权请求过于频繁");
        }
        if (error != 0) throw upstreamFailure();
        JsonNode info = body.path("phone_info");
        String phone = info.path("phoneNumber").asText("");
        String watermarkAppId = info.path("watermark").path("appid").asText("");
        if (!appId.equals(watermarkAppId) || !phone.matches("\\+?[0-9]{6,20}") || phone.length() > 20) {
            throw upstreamFailure();
        }
        return phone;
    }

    private synchronized String accessToken() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()
            || "unconfigured".equalsIgnoreCase(appSecret)) {
            throw new WechatExchangeException(WechatExchangeException.Reason.UNCONFIGURED, "微信手机号服务尚未配置");
        }
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();
        JsonNode body = post(tokenEndpoint, Map.of("grant_type", "client_credential", "appid", appId,
            "secret", appSecret, "force_refresh", false));
        String value = body.path("access_token").asText("");
        long seconds = body.path("expires_in").asLong(0);
        if (value.isBlank() || seconds < 120) throw upstreamFailure();
        cached = new AccessToken(value, Instant.now().plusSeconds(seconds - 60));
        return value;
    }

    private JsonNode post(URI uri, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw upstreamFailure();
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) throw upstreamFailure();
            return body;
        } catch (IOException exception) {
            throw upstreamFailure();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw upstreamFailure();
        }
    }

    private static WechatExchangeException upstreamFailure() {
        return new WechatExchangeException(WechatExchangeException.Reason.UPSTREAM_FAILURE,
            "微信手机号服务暂时不可用");
    }
}
