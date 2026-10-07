package com.autocare.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient http;
    private HttpResponse<String> response;

    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() throws Exception {
        http = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private DeepSeekClient client(String apiKey, String model, String baseUrl, String thinking) {
        return new DeepSeekClient(apiKey, model, baseUrl, thinking, http, mapper);
    }

    private static String requestBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        HttpResponse.BodySubscriber<String> subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer item) { subscriber.onNext(List.of(item)); }
            @Override public void onError(Throwable error) { subscriber.onError(error); }
            @Override public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private HttpRequest sentRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
    }

    private void okResponse(String json) {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(json);
    }

    @Test void sendsOpenAiCompatiblePayloadToConfiguredModel() throws Exception {
        okResponse("{\"model\":\"deepseek-flash\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"检查刹车片\"}}],"
            + "\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":12}}");
        var completion = client("sk-secret", "", "https://api.deepseek.com/", "")
            .complete("系统提示", List.of(new DeepSeekClient.Message("user", "刹车异响"),
                new DeepSeekClient.Message("assistant", "请描述"), new DeepSeekClient.Message("user", "低速时更明显")), 256);
        assertEquals("检查刹车片", completion.content());
        assertEquals("deepseek-flash", completion.model());
        assertEquals(31, completion.promptTokens());
        assertEquals(12, completion.completionTokens());

        HttpRequest request = sentRequest();
        assertEquals("https://api.deepseek.com/chat/completions", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer sk-secret", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        JsonNode payload = mapper.readTree(requestBody(request));
        assertEquals("deepseek-flash", payload.path("model").asText());
        assertFalse(payload.path("stream").asBoolean(true));
        assertEquals(256, payload.path("max_tokens").asInt());
        assertEquals("disabled", payload.path("thinking").path("type").asText());
        List<String> roles = new ArrayList<>();
        payload.path("messages").forEach(turn -> roles.add(turn.path("role").asText()));
        assertEquals(List.of("system", "user", "assistant", "user"), roles);
        assertEquals("系统提示", payload.path("messages").path(0).path("content").asText());
    }

    @Test void fallsBackToDefaultModelAndBaseUrlAndCanEnableThinking() throws Exception {
        okResponse("{\"choices\":[{\"message\":{\"content\":\"答复\"}}]}");
        var client = client("sk-secret", "  ", "https://proxy.example.test/v1/", "enabled");
        assertEquals(DeepSeekClient.DEFAULT_MODEL, client.model());
        client.complete("提示", List.of(new DeepSeekClient.Message("user", "你好")), 64);
        HttpRequest request = sentRequest();
        assertEquals("https://proxy.example.test/v1/chat/completions", request.uri().toString());
        assertEquals("enabled", mapper.readTree(requestBody(request)).path("thinking").path("type").asText());
    }

    @Test void refusesToCallWithoutAKey() {
        for (String key : List.of("", "  ", "unconfigured", "UNCONFIGURED")) {
            var exception = assertThrows(AiUpstreamException.class,
                () -> client(key, "deepseek-flash", "https://api.deepseek.com", "").complete("提示",
                    List.of(new DeepSeekClient.Message("user", "你好")), 64));
            assertEquals(AiUpstreamException.Reason.UNCONFIGURED, exception.reason());
        }
        verifyNoInteractions(http);
    }

    @Test void mapsProviderFailuresToSafeReasons() {
        var client = client("sk-secret", "", "", "");
        List<DeepSeekClient.Message> turns = List.of(new DeepSeekClient.Message("user", "你好"));
        when(response.statusCode()).thenReturn(401);
        assertEquals(AiUpstreamException.Reason.UNCONFIGURED,
            assertThrows(AiUpstreamException.class, () -> client.complete("提示", turns, 64)).reason());
        when(response.statusCode()).thenReturn(402);
        assertEquals(AiUpstreamException.Reason.UPSTREAM_FAILURE,
            assertThrows(AiUpstreamException.class, () -> client.complete("提示", turns, 64)).reason());
        when(response.statusCode()).thenReturn(429);
        assertEquals(AiUpstreamException.Reason.RATE_LIMITED,
            assertThrows(AiUpstreamException.class, () -> client.complete("提示", turns, 64)).reason());
        when(response.statusCode()).thenReturn(503);
        assertEquals(AiUpstreamException.Reason.UPSTREAM_FAILURE,
            assertThrows(AiUpstreamException.class, () -> client.complete("提示", turns, 64)).reason());
    }

    @Test void treatsEmptyOrMalformedAnswersAsUpstreamFailure() {
        var client = client("sk-secret", "", "", "");
        List<DeepSeekClient.Message> turns = List.of(new DeepSeekClient.Message("user", "你好"));
        when(response.statusCode()).thenReturn(200);
        for (String body : List.of("", "not-json", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}")) {
            when(response.body()).thenReturn(body);
            var exception = assertThrows(AiUpstreamException.class, () -> client.complete("提示", turns, 64));
            assertEquals(AiUpstreamException.Reason.UPSTREAM_FAILURE, exception.reason());
            assertFalse(exception.getMessage().contains("sk-secret"));
        }
    }
}
