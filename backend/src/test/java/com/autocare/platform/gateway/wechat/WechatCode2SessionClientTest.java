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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WechatCode2SessionClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exchangesCodeAndKeepsSessionKeyOutOfResult() throws IOException {
        AtomicReference<String> query = new AtomicReference<>();
        WechatCode2SessionClient client = stub("{\"openid\":\"owner-1\",\"unionid\":\"union-1\",\"session_key\":\"private-value\"}", query);

        WechatSession result = client.exchange("code with space");

        assertThat(result).isEqualTo(new WechatSession("owner-1", "union-1"));
        assertThat(result.toString()).doesNotContain("private-value");
        assertThat(query.get()).contains("appid=test-app", "secret=test-secret", "js_code=code+with+space", "grant_type=authorization_code");
    }

    @Test
    void mapsKnownWechatErrors() throws IOException {
        WechatCode2SessionClient invalid = stub("{\"errcode\":40029}", new AtomicReference<>());
        assertThatThrownBy(() -> invalid.exchange("used-code"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                exception -> assertThat(exception.reason()).isEqualTo(WechatExchangeException.Reason.INVALID_CODE));

        WechatCode2SessionClient limited = stub("{\"errcode\":45011}", new AtomicReference<>());
        assertThatThrownBy(() -> limited.exchange("rate-code"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                exception -> assertThat(exception.reason()).isEqualTo(WechatExchangeException.Reason.RATE_LIMITED));
    }

    @Test
    void rejectsMissingConfigurationAndBadCodeBeforeNetworkCall() {
        WechatCode2SessionClient unconfigured = new WechatCode2SessionClient(
            "test-app", "unconfigured", URI.create("http://127.0.0.1:1"), HttpClient.newHttpClient(), new ObjectMapper());
        assertThatThrownBy(() -> unconfigured.exchange("valid-code"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                exception -> assertThat(exception.reason()).isEqualTo(WechatExchangeException.Reason.UNCONFIGURED));

        WechatCode2SessionClient configured = new WechatCode2SessionClient(
            "test-app", "test-secret", URI.create("http://127.0.0.1:1"), HttpClient.newHttpClient(), new ObjectMapper());
        assertThatThrownBy(() -> configured.exchange(" "))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                exception -> assertThat(exception.reason()).isEqualTo(WechatExchangeException.Reason.INVALID_CODE));
    }

    @Test
    void rejectsMalformedSuccessfulResponses() throws IOException {
        WechatCode2SessionClient missingOpenid = stub("{\"session_key\":\"private-value\"}", new AtomicReference<>());
        assertThatThrownBy(() -> missingOpenid.exchange("code"))
            .isInstanceOfSatisfying(WechatExchangeException.class,
                exception -> assertThat(exception.reason()).isEqualTo(WechatExchangeException.Reason.UPSTREAM_FAILURE));
    }

    private WechatCode2SessionClient stub(String response, AtomicReference<String> query) throws IOException {
        if (server != null) {
            server.stop(0);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/jscode2session", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sns/jscode2session");
        return new WechatCode2SessionClient("test-app", "test-secret", endpoint,
            HttpClient.newHttpClient(), new ObjectMapper());
    }
}
