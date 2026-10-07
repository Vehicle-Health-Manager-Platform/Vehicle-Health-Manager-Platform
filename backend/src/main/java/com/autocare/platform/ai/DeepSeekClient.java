package com.autocare.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the DeepSeek OpenAI-compatible chat completion API.
 * The API key is read from the environment only and never leaves the server.
 */
@Component
public class DeepSeekClient {
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";

    private final String apiKey;
    private final String model;
    private final boolean thinking;
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public DeepSeekClient(
        @Value("${DEEPSEEK_API_KEY:}") String apiKey,
        @Value("${DEEPSEEK_MODEL:}") String model,
        @Value("${DEEPSEEK_BASE_URL:}") String baseUrl,
        @Value("${DEEPSEEK_THINKING:}") String thinking,
        ObjectMapper mapper
    ) {
        this(apiKey, model, baseUrl, thinking,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), mapper);
    }

    DeepSeekClient(String apiKey, String model, String baseUrl, String thinking, HttpClient http, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.thinking = "enabled".equalsIgnoreCase(thinking == null ? "" : thinking.trim());
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        this.http = http;
        this.mapper = mapper;
    }

    public String model() {
        return model;
    }

    public boolean configured() {
        return !apiKey.isBlank() && !"unconfigured".equalsIgnoreCase(apiKey);
    }

    public record Message(String role, String content) {}

    public record Completion(String content, String model, int promptTokens, int completionTokens) {}

    public Completion complete(String system, List<Message> turns, int maxTokens) {
        if (!configured()) {
            throw new AiUpstreamException(AiUpstreamException.Reason.UNCONFIGURED, "AI 管家尚未配置");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("temperature", 0.3);
        payload.put("max_tokens", maxTokens);
        ArrayNode messages = payload.putArray("messages");
        messages.add(mapper.createObjectNode().put("role", "system").put("content", system));
        for (Message turn : turns) {
            messages.add(mapper.createObjectNode().put("role", turn.role()).put("content", turn.content()));
        }
        // V4 默认开启思考；Flash 的非思考通道更快更省，仅在显式启用时才带上推理。
        payload.putObject("thinking").put("type", thinking ? "enabled" : "disabled");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new AiUpstreamException(AiUpstreamException.Reason.UNCONFIGURED, "AI 管家密钥无效");
            }
            if (status == 429) {
                throw new AiUpstreamException(AiUpstreamException.Reason.RATE_LIMITED, "AI 管家请求过于频繁，请稍后重试");
            }
            if (status < 200 || status >= 300) {
                throw upstreamFailure();
            }
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw upstreamFailure();
            }
            JsonNode content = body.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw upstreamFailure();
            }
            JsonNode usage = body.path("usage");
            return new Completion(content.asText(), body.path("model").asText(model),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        } catch (IOException exception) {
            throw upstreamFailure();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw upstreamFailure();
        }
    }

    private static AiUpstreamException upstreamFailure() {
        return new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_FAILURE, "AI 管家暂时不可用");
    }
}
