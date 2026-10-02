package com.autocare.platform.gateway.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WechatPhoneClientTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void exchangesOneTimeCodeForWatermarkedPhoneAndCachesServerToken() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicReference<String> tokenRequest = new AtomicReference<>();
        AtomicReference<String> phoneRequest = new AtomicReference<>();
        var client = stub("{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"13800138000\","
            + "\"watermark\":{\"appid\":\"test-app\"}}}", tokenCalls, tokenRequest, phoneRequest);
        assertThat(client.exchange("phone-code-1", "openid-1")).isEqualTo("13800138000");
        assertThat(client.exchange("phone-code-2", "openid-1")).isEqualTo("13800138000");
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(mapper.readTree(tokenRequest.get()).path("secret").asText()).isEqualTo("test-secret");
        assertThat(mapper.readTree(tokenRequest.get()).path("force_refresh").asBoolean()).isFalse();
        assertThat(mapper.readTree(phoneRequest.get()).path("openid").asText()).isEqualTo("openid-1");
        assertThat(mapper.readTree(phoneRequest.get()).path("code").asText()).isEqualTo("phone-code-2");
    }

    @Test
    void rejectsInvalidCodeAndWrongWatermark() throws Exception {
        var invalid = stub("{\"errcode\":40029}", new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> invalid.exchange("used", "openid"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                error -> assertThat(error.reason()).isEqualTo(WechatExchangeException.Reason.INVALID_CODE));
        server.stop(0);
        server = null;
        var wrongApp = stub("{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"13800138000\","
            + "\"watermark\":{\"appid\":\"other-app\"}}}", new AtomicInteger(),
            new AtomicReference<>(), new AtomicReference<>());
        assertThatThrownBy(() -> wrongApp.exchange("code", "openid"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                error -> assertThat(error.reason()).isEqualTo(WechatExchangeException.Reason.UPSTREAM_FAILURE));
    }

    @Test
    void requiresPrivateConfiguration() {
        var client = new WechatPhoneClient("test-app", "unconfigured", URI.create("http://127.0.0.1:1"),
            URI.create("http://127.0.0.1:1"), HttpClient.newHttpClient(), mapper);
        assertThatThrownBy(() -> client.exchange("code", "openid"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                error -> assertThat(error.reason()).isEqualTo(WechatExchangeException.Reason.UNCONFIGURED));
    }

    private WechatPhoneClient stub(String phoneResponse, AtomicInteger tokenCalls,
                                   AtomicReference<String> tokenRequest, AtomicReference<String> phoneRequest)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/stable_token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "{\"access_token\":\"server-only-token\",\"expires_in\":7200}");
        });
        server.createContext("/wxa/business/getuserphonenumber", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("access_token=server-only-token");
            phoneRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, phoneResponse);
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        return new WechatPhoneClient("test-app", "test-secret", URI.create(root + "/cgi-bin/stable_token"),
            URI.create(root + "/wxa/business/getuserphonenumber"), HttpClient.newHttpClient(), mapper);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
