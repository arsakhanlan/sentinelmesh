package com.sentinelmesh.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.domain.port.out.LlmJudgeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain OpenAI client for the L4 judge — hits {@code https://api.openai.com/v1/chat/completions}
 * (or any OpenAI-compatible base URL: Groq, OpenRouter, Together, ...).
 *
 * <p>Difference from {@link AzureOpenAiClient}: Azure uses
 * {@code /openai/deployments/{deployment}/chat/completions?api-version=...} with
 * {@code api-key} header, while OpenAI uses {@code /v1/chat/completions} with
 * {@code Authorization: Bearer ...}.
 */
public class OpenAiClient implements LlmJudgeClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String key;
    private final String model;

    public OpenAiClient(String baseUrl, String key, String model, ObjectMapper mapper) {
        String normalized = (baseUrl == null || baseUrl.isBlank())
                ? "https://api.openai.com"
                : baseUrl.replaceAll("/$", "");
        // Allow users to pass either the API root or the /v1 root; normalize to root.
        if (normalized.endsWith("/v1")) normalized = normalized.substring(0, normalized.length() - 3);
        this.http = RestClient.builder().baseUrl(normalized).build();
        this.key = key;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public Verdict judge(String content, String rubric) {
        if (content == null || content.isBlank())
            return new Verdict(false, 0.0, "none", "empty");
        try {
            String raw;
            try {
                raw = chat(rubric, content, /*jsonMode=*/ true);
            } catch (RestClientResponseException badReq) {
                // Some OpenAI-compatible hosts (DeepSeek, older proxies) reject
                // response_format=json_object on certain prompts. Retry once
                // without JSON mode — _coerceJson below still recovers an object
                // from the model's reply.
                if (badReq.getStatusCode().value() == 400) {
                    log.warn("Provider rejected response_format=json_object (400); retrying without. {}",
                            badReq.getResponseBodyAsString());
                    raw = chat(rubric, content, /*jsonMode=*/ false);
                } else {
                    throw badReq;
                }
            }
            JsonNode json = mapper.readTree(raw);
            String text = json.path("choices").path(0).path("message").path("content").asText("{}");
            JsonNode v = coerceJson(text);
            return new Verdict(
                    v.path("is_injection").asBoolean(false),
                    v.path("confidence").asDouble(0.0),
                    v.path("technique").asText("unknown"),
                    v.path("reasoning").asText(""));
        } catch (Exception e) {
            log.warn("OpenAI call failed; returning safe verdict. {}", e.toString());
            return new Verdict(false, 0.0, "unavailable", e.toString());
        }
    }

    private String chat(String rubric, String content, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", rubric),
                Map.of("role", "user", "content", content)));
        body.put("temperature", 0.0);
        body.put("max_tokens", 256);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return http.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(String.class);
    }

    /** Best-effort JSON extraction from a model reply that may have prose around it. */
    private JsonNode coerceJson(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception ignored) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(text.substring(start, end + 1));
                } catch (Exception ignored2) {
                    // fall through
                }
            }
            return mapper.createObjectNode();
        }
    }
}
