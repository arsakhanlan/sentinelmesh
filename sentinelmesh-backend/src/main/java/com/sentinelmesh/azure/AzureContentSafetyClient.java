package com.sentinelmesh.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.domain.port.out.PromptShieldClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Real client for Azure AI Content Safety — Prompt Shields.
 * Activated when {@code sentinelmesh.azure.mode=real} and both endpoint+key are set.
 *
 * <p>API contract (paraphrased):
 * <pre>POST {endpoint}/contentsafety/text:shieldPrompt?api-version=2024-09-01
 *   { "userPrompt": "...", "documents": ["..."] }
 *   → { "userPromptAnalysis": { "attackDetected": bool },
 *       "documentsAnalysis": [{ "attackDetected": bool }] }</pre>
 */
public class AzureContentSafetyClient implements PromptShieldClient {

    private static final Logger log = LoggerFactory.getLogger(AzureContentSafetyClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String key;

    public AzureContentSafetyClient(String endpoint, String key, ObjectMapper mapper) {
        this.http = RestClient.builder().baseUrl(endpoint).build();
        this.key = key;
        this.mapper = mapper;
    }

    @Override
    public Result scan(String content) {
        if (content == null || content.isBlank()) return new Result(0.0, "clean", "");
        try {
            Map<String, Object> body = Map.of("documents", List.of(content), "userPrompt", "");
            String raw = http.post()
                    .uri("/contentsafety/text:shieldPrompt?api-version=2024-09-01")
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode json = mapper.readTree(raw);
            boolean attack = false;
            JsonNode docs = json.path("documentsAnalysis");
            if (docs.isArray() && docs.size() > 0) {
                attack = docs.get(0).path("attackDetected").asBoolean(false);
            }
            double score = attack ? 0.91 : 0.05;
            String technique = attack ? "INDIRECT_PROMPT_INJECTION" : "clean";
            return new Result(score, technique, content.length() > 120
                    ? content.substring(0, 120) + "…" : content);
        } catch (Exception e) {
            log.warn("Azure Content Safety call failed; returning conservative score. {}", e.toString());
            return new Result(0.0, "unavailable", "");
        }
    }
}
