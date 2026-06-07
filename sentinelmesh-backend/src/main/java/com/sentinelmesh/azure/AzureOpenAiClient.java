package com.sentinelmesh.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.domain.port.out.LlmJudgeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Real Azure OpenAI client for the L4 judge. Uses chat completions with JSON
 * mode so the model returns structured output we can parse directly.
 */
public class AzureOpenAiClient implements LlmJudgeClient {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String key;
    private final String deployment;

    public AzureOpenAiClient(String endpoint, String key, String deployment, ObjectMapper mapper) {
        this.http = RestClient.builder().baseUrl(endpoint).build();
        this.key = key;
        this.deployment = deployment;
        this.mapper = mapper;
    }

    @Override
    public Verdict judge(String content, String rubric) {
        if (content == null || content.isBlank())
            return new Verdict(false, 0.0, "none", "empty");
        try {
            Map<String, Object> body = Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content", rubric),
                            Map.of("role", "user", "content", content)),
                    "temperature", 0.0,
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 256
            );
            String raw = http.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/openai/deployments/{deployment}/chat/completions")
                            .queryParam("api-version", "2024-08-01-preview")
                            .build(deployment))
                    .header("api-key", key)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode json = mapper.readTree(raw);
            String text = json.path("choices").path(0).path("message").path("content").asText("{}");
            JsonNode v = mapper.readTree(text);
            return new Verdict(
                    v.path("is_injection").asBoolean(false),
                    v.path("confidence").asDouble(0.0),
                    v.path("technique").asText("unknown"),
                    v.path("reasoning").asText(""));
        } catch (Exception e) {
            log.warn("Azure OpenAI call failed; returning safe verdict. {}", e.toString());
            return new Verdict(false, 0.0, "unavailable", e.toString());
        }
    }
}
