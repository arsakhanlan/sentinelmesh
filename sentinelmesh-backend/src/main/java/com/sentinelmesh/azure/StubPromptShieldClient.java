package com.sentinelmesh.azure;

import com.sentinelmesh.domain.port.out.PromptShieldClient;

import java.util.regex.Pattern;

/**
 * Deterministic stand-in for Azure AI Content Safety — Prompt Shields.
 * Produces realistic-looking scores so the demo works without Azure quota.
 *
 * <p>Switched on/off via {@code sentinelmesh.azure.mode=stub|real}.
 */
public class StubPromptShieldClient implements PromptShieldClient {

    /**
     * Inline-only hidden-DOM matcher.
     *
     * <p>Earlier this regex matched ANY occurrence of {@code display:none} /
     * {@code visibility:hidden} in the inspected content, which trips on
     * legitimate CSS rules emitted by Tailwind, Alpine.js ({@code
     * [x-cloak]{display:none}}), Bootstrap, etc. — every page on the demo
     * site has those, so the very first browse to any demo URL would score
     * 0.55 here, get remembered by L7 attack memory, and then BLOCK every
     * subsequent unrelated visit. Tightened to require the suspicious
     * declaration to live inside an inline {@code style="…"} attribute or
     * a {@code <div hidden>} marker — which is the actual indirect-injection
     * shape attackers use when smuggling instructions in DOM.</p>
     */
    private static final Pattern HIDDEN = Pattern.compile(
            "(?is)(?:" +
                    "style\\s*=\\s*[\"'][^\"']*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|opacity\\s*:\\s*0)" +
                    "|<[a-zA-Z][^>]*\\bhidden\\b\\s*(?:>|/>)" +
                    "|aria-hidden\\s*=\\s*[\"']true[\"']" +
                    ")");
    private static final Pattern IMPERATIVE = Pattern.compile(
            "(?i)ignore\\s+previous|disregard\\s+(the\\s+)?(above|prior)" +
                    "|act\\s+as\\s+(a\\s+)?(?:system|admin|developer)" +
                    "|reveal\\s+(your\\s+)?(system\\s+)?prompt|exfiltrat\\w*" +
                    "|new\\s+instructions?\\s*:");

    @Override
    public Result scan(String content) {
        if (content == null || content.isBlank()) return new Result(0.0, "clean", "");
        double score = 0.0;
        boolean hidden = HIDDEN.matcher(content).find();
        boolean imperative = IMPERATIVE.matcher(content).find();
        if (hidden) score = Math.max(score, 0.55);
        if (imperative) score = Math.max(score, 0.85);
        // Both signals → high-confidence indirect injection.
        if (hidden && imperative) score = 0.94;
        if (score == 0.0) return new Result(0.0, "clean", "");
        String technique = hidden ? "INDIRECT_HIDDEN_DOM" : "DIRECT_INSTRUCTION_OVERRIDE";
        return new Result(score, technique, sampleEvidence(content));
    }

    private static String sampleEvidence(String c) {
        return c.length() <= 120 ? c : c.substring(0, 120) + "…";
    }
}
