package com.sentinelmesh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.azure.*;
import com.sentinelmesh.domain.port.out.ContentSafetyCategoriesClient;
import com.sentinelmesh.domain.port.out.LlmJudgeClient;
import com.sentinelmesh.domain.port.out.PromptShieldClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureConfig {

    private static final Logger log = LoggerFactory.getLogger(AzureConfig.class);

    @Bean
    public PromptShieldClient promptShieldClient(
            @Value("${sentinelmesh.azure.mode:stub}") String mode,
            @Value("${sentinelmesh.azure.contentSafety.endpoint:}") String endpoint,
            @Value("${sentinelmesh.azure.contentSafety.key:}") String key,
            ObjectMapper mapper) {
        if ("real".equalsIgnoreCase(mode) && !endpoint.isBlank() && !key.isBlank()) {
            log.info("Using real Azure Content Safety Prompt Shields client (endpoint={})", endpoint);
            return new AzureContentSafetyClient(endpoint, key, mapper);
        }
        log.info("Using STUB Prompt Shields client (sentinelmesh.azure.mode={})", mode);
        return new StubPromptShieldClient();
    }

    /**
     * L2 categories client. Calls Azure Content Safety {@code text:analyze}
     * for Hate / SelfHarm / Sexual / Violence severity scoring.
     *
     * <p>Activated when {@code sentinelmesh.azure.contentSafety.categories.mode=real}
     * (or when the legacy {@code sentinelmesh.azure.mode=real} is on with a
     * Content Safety endpoint configured). Falls back to a deterministic stub
     * so the demo runs without Azure quota.
     */
    @Bean
    public ContentSafetyCategoriesClient contentSafetyCategoriesClient(
            @Value("${sentinelmesh.azure.contentSafety.categories.mode:}") String mode,
            @Value("${sentinelmesh.azure.mode:stub}") String legacyMode,
            @Value("${sentinelmesh.azure.contentSafety.endpoint:}") String endpoint,
            @Value("${sentinelmesh.azure.contentSafety.key:}") String key,
            @Value("${sentinelmesh.azure.contentSafety.categories.flagThreshold:4}") int flagThreshold,
            ObjectMapper mapper) {
        // Categories mode falls back to the legacy azure.mode so existing
        // deployments that flip a single switch get both Prompt Shields and
        // Categories in real mode without separate config.
        String effective = (mode == null || mode.isBlank()) ? legacyMode : mode;
        if ("real".equalsIgnoreCase(effective) && !endpoint.isBlank() && !key.isBlank()) {
            log.info("Using real Azure Content Safety Categories client (endpoint={}, threshold={})",
                    endpoint, flagThreshold);
            return new AzureContentSafetyCategoriesClient(endpoint, key, flagThreshold, mapper);
        }
        log.info("Using STUB Content Safety Categories client (mode={}, threshold={})",
                effective, flagThreshold);
        return new StubContentSafetyCategoriesClient(flagThreshold);
    }

    /**
     * L4 judge client. Provider is chosen via {@code sentinelmesh.l4.provider}:
     * <ul>
     *   <li>{@code stub} — deterministic local stub (default; zero cost)</li>
     *   <li>{@code azure} — Azure OpenAI deployment (uses {@code sentinelmesh.azure.openai.*})</li>
     *   <li>{@code openai} — plain OpenAI or any OpenAI-compatible host
     *       (uses {@code sentinelmesh.openai.*}; works with Groq, OpenRouter, Together, Ollama, ...)</li>
     * </ul>
     *
     * <p>Backwards compatibility: if {@code sentinelmesh.l4.provider} is unset and the legacy
     * {@code sentinelmesh.azure.mode=real} flag is on with an Azure endpoint configured, we
     * fall back to the Azure client so existing deployments keep working.
     */
    @Bean
    public LlmJudgeClient llmJudgeClient(
            @Value("${sentinelmesh.l4.provider:}") String provider,
            @Value("${sentinelmesh.azure.mode:stub}") String legacyMode,
            @Value("${sentinelmesh.azure.openai.endpoint:}") String azureEndpoint,
            @Value("${sentinelmesh.azure.openai.key:}") String azureKey,
            @Value("${sentinelmesh.azure.openai.deployment:gpt-4o-mini}") String azureDeployment,
            @Value("${sentinelmesh.openai.baseUrl:}") String openAiBaseUrl,
            @Value("${sentinelmesh.openai.key:}") String openAiKey,
            @Value("${sentinelmesh.openai.model:gpt-4o-mini}") String openAiModel,
            ObjectMapper mapper) {

        String resolved = provider == null ? "" : provider.trim().toLowerCase();
        if (resolved.isEmpty()) {
            // legacy fallback: sentinelmesh.azure.mode=real implies azure provider
            resolved = "real".equalsIgnoreCase(legacyMode) ? "azure" : "stub";
        }

        switch (resolved) {
            case "openai" -> {
                if (openAiKey == null || openAiKey.isBlank()) {
                    log.warn("sentinelmesh.l4.provider=openai but sentinelmesh.openai.key is blank; falling back to STUB");
                    return new StubLlmJudgeClient();
                }
                String shown = openAiBaseUrl.isBlank() ? "https://api.openai.com" : openAiBaseUrl;
                log.info("Using OpenAI-compatible LLM judge (baseUrl={}, model={})", shown, openAiModel);
                return new OpenAiClient(openAiBaseUrl, openAiKey, openAiModel, mapper);
            }
            case "azure" -> {
                if (azureEndpoint.isBlank() || azureKey.isBlank()) {
                    log.warn("sentinelmesh.l4.provider=azure but azure endpoint/key missing; falling back to STUB");
                    return new StubLlmJudgeClient();
                }
                log.info("Using Azure OpenAI judge (endpoint={}, deployment={})", azureEndpoint, azureDeployment);
                return new AzureOpenAiClient(azureEndpoint, azureKey, azureDeployment, mapper);
            }
            case "stub" -> {
                log.info("Using STUB LLM judge (sentinelmesh.l4.provider=stub)");
                return new StubLlmJudgeClient();
            }
            default -> {
                log.warn("Unknown sentinelmesh.l4.provider='{}'; falling back to STUB", provider);
                return new StubLlmJudgeClient();
            }
        }
    }
}
