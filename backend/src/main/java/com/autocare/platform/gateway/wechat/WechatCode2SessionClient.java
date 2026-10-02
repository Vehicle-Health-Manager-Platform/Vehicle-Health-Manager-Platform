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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exchanges a one-time wx.login code on the server. Never expose the AppSecret or session_key. */
@Component
public class WechatCode2SessionClient {
    private static final URI OFFICIAL_ENDPOINT = URI.create("https://api.weixin.qq.com/sns/jscode2session");
    private final String appId;
    private final String appSecret;
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public WechatCode2SessionClient(
        @Value("${WECHAT_APP_ID:}") String appId,
        @Value("${WECHAT_APP_SECRET:}") String appSecret,
        ObjectMapper mapper
    ) {
        this(appId, appSecret, OFFICIAL_ENDPOINT,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper);
    }

    WechatCode2SessionClient(String appId, String appSecret, URI endpoint, HttpClient http, ObjectMapper mapper) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
    }

    public WechatSession exchange(String code) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()
            || "unconfigured".equalsIgnoreCase(appSecret)) {
            throw new WechatExchangeException(WechatExchangeException.Reason.UNCONFIGURED,
                "微信登录服务尚未配置");
        }
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new WechatExchangeException(WechatExchangeException.Reason.INVALID_CODE,
                "微信临时登录凭证无效");
        }

        String query = "appid=" + encode(appId)
            + "&secret=" + encode(appSecret)
            + "&js_code=" + encode(code)
            + "&grant_type=authorization_code";
        URI url = URI.create(endpoint + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw upstreamFailure();
            }
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw upstreamFailure();
            }
            int errorCode = body.path("errcode").asInt(0);
            if (errorCode == 40029) {
                throw new WechatExchangeException(WechatExchangeException.Reason.INVALID_CODE,
                    "微信临时登录凭证无效或已使用");
            }
            if (errorCode == 45011) {
                throw new WechatExchangeException(WechatExchangeException.Reason.RATE_LIMITED,
                    "微信登录请求过于频繁，请稍后重试");
            }
            if (errorCode != 0) {
                throw upstreamFailure();
            }
            String openid = body.path("openid").asText("");
            if (openid.isBlank()) {
                throw upstreamFailure();
            }
            return new WechatSession(openid, body.path("unionid").asText(""));
        } catch (IOException exception) {
            throw upstreamFailure();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw upstreamFailure();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static WechatExchangeException upstreamFailure() {
        return new WechatExchangeException(WechatExchangeException.Reason.UPSTREAM_FAILURE,
            "微信身份服务暂时不可用");
    }
}
