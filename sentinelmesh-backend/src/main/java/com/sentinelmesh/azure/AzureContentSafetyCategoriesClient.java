package com.sentinelmesh.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.domain.port.out.ContentSafetyCategoriesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real client for Azure AI Content Safety — <b>text:analyze</b> (categories).
 *
 * <p>Distinct from {@link AzureContentSafetyClient} which targets
 * {@code text:shieldPrompt}. Both share the same Content Safety resource
 * endpoint and key — only the URL path and request shape differ.
 *
 * <p>API contract (paraphrased):
 * <pre>POST {endpoint}/contentsafety/text:analyze?api-version=2024-09-01
 *   {
 *     "text": "...",
 *     "categories": ["Hate", "SelfHarm", "Sexual", "Violence"],
 *     "outputType": "EightSeverityLevels"
 *   }
 *   → {
 *     "blocklistsMatch": [],
 *     "categoriesAnalysis": [
 *       { "category": "Hate", "severity": 0 },
 *       { "category": "SelfHarm", "severity": 0 },
 *       { "category": "Sexual", "severity": 0 },
 *       { "category": "Violence", "severity": 4 }
 *     ]
 *   }</pre>
 *
 * <p>Severity scale ({@code EightSeverityLevels}): 0 = clean … 7 = severe.
 * The scanner uses a configurable threshold (default 4) to decide which
 * categories count as "flagged".
 */
public class AzureContentSafetyCategoriesClient implements ContentSafetyCategoriesClient {

    private static final Logger log = LoggerFactory.getLogger(AzureContentSafetyCategoriesClient.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String key;
    private final int flagThreshold;

    public AzureContentSafetyCategoriesClient(String endpoint, String key, int flagThreshold,
                                              ObjectMapper mapper) {
        this.http = RestClient.builder().baseUrl(endpoint).build();
        this.key = key;
        this.flagThreshold = flagThreshold;
        this.mapper = mapper;
    }

    @Override
    public Result analyze(String content) {
        if (content == null || content.isBlank()) return Result.clean();
        try {
            Map<String, Object> body = Map.of(
                    "text", content,
                    "categories", List.of("Hate", "SelfHarm", "Sexual", "Violence"),
                    "outputType", "EightSeverityLevels"
            );
            String raw = http.post()
                    .uri("/contentsafety/text:analyze?api-version=2024-09-01")
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode json = mapper.readTree(raw);

            Map<String, Integer> sev = new LinkedHashMap<>();
            int max = 0;
            List<String> flagged = new ArrayList<>();
            for (JsonNode entry : json.path("categoriesAnalysis")) {
                String cat = entry.path("category").asText("");
                int s = entry.path("severity").asInt(0);
                if (cat.isEmpty()) continue;
                sev.put(cat, s);
                if (s > max) max = s;
                if (s >= flagThreshold) flagged.add(cat);
            }
            // Make sure the four canonical keys are always present so downstream
            // scoring is deterministic even when Azure returns a partial set.
            sev.putIfAbsent("Hate", 0);
            sev.putIfAbsent("SelfHarm", 0);
            sev.putIfAbsent("Sexual", 0);
            sev.putIfAbsent("Violence", 0);

            return new Result(Map.copyOf(sev), max, List.copyOf(flagged));
        } catch (Exception e) {
            log.warn("Azure Content Safety text:analyze failed; returning null. {}", e.toString());
            return null;
        }
    }
}
