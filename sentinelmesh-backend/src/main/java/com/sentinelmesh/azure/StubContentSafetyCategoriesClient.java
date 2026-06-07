package com.sentinelmesh.azure;

import com.sentinelmesh.domain.port.out.ContentSafetyCategoriesClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for Azure AI Content Safety — text:analyze
 * (categories). Same return shape as {@link AzureContentSafetyCategoriesClient}
 * so the {@link com.sentinelmesh.security.scanners.L2ContentSafetyCategoryScanner}
 * is oblivious to which one is wired.
 *
 * <p>Severity 0–7 mirrors Azure's EightSeverityLevels scale. Strong matches
 * land at 6 (above the default flag threshold of 4); weak matches at 2.
 */
public class StubContentSafetyCategoriesClient implements ContentSafetyCategoriesClient {

    private static final Map<String, Pattern> STRONG = new LinkedHashMap<>() {{
        put("Violence", Pattern.compile(
                "(?i)\\b(kill|murder|assault|behead|stab|shoot)\\s+(?:the|a|him|her|them|someone)\\b"
                        + "|\\b(?:assault\\s+rifles?|grenades?|c4|semtex|ied)\\b"));
        put("SelfHarm", Pattern.compile(
                "(?i)\\b(?:self[- ]?harm|kill\\s+myself|commit\\s+suicide|end\\s+my\\s+life)\\b"));
        put("Hate", Pattern.compile(
                "(?i)\\b(?:exterminate|deport|cleanse)\\s+(?:all|every|the)\\s+\\w+s?\\b"));
        put("Sexual", Pattern.compile(
                "(?i)\\b(?:child|underage|minor)\\b.{0,30}\\b(?:sexual|nude|porn)\\b"));
    }};

    private static final Map<String, Pattern> WEAK = new LinkedHashMap<>() {{
        put("Violence", Pattern.compile("(?i)\\b(?:bloody|gore|brutal|vicious)\\b"));
        put("Hate", Pattern.compile("(?i)\\b(?:slur|epithet)\\b"));
        put("SelfHarm", Pattern.compile("(?i)\\b(?:cutting|overdose|suicidal)\\b"));
        put("Sexual", Pattern.compile("(?i)\\b(?:explicit\\s+content|nsfw)\\b"));
    }};

    private final int flagThreshold;

    public StubContentSafetyCategoriesClient(int flagThreshold) {
        this.flagThreshold = flagThreshold;
    }

    @Override
    public Result analyze(String content) {
        if (content == null || content.isBlank()) return Result.clean();

        Map<String, Integer> sev = new LinkedHashMap<>();
        sev.put("Hate", 0);
        sev.put("SelfHarm", 0);
        sev.put("Sexual", 0);
        sev.put("Violence", 0);

        for (Map.Entry<String, Pattern> e : STRONG.entrySet()) {
            if (e.getValue().matcher(content).find()) {
                sev.merge(e.getKey(), 6, Integer::max);
            }
        }
        for (Map.Entry<String, Pattern> e : WEAK.entrySet()) {
            if (e.getValue().matcher(content).find()) {
                sev.merge(e.getKey(), 2, Integer::max);
            }
        }

        int max = 0;
        List<String> flagged = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sev.entrySet()) {
            if (e.getValue() > max) max = e.getValue();
            if (e.getValue() >= flagThreshold) flagged.add(e.getKey());
        }
        return new Result(Map.copyOf(sev), max, List.copyOf(flagged));
    }
}
