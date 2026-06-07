package com.sentinelmesh.azure;

import com.sentinelmesh.domain.port.out.LlmJudgeClient;

import java.util.regex.Pattern;

/**
 * Stub for Azure OpenAI gpt-4o-mini judge. Returns plausible verdicts using
 * keyword heuristics — good enough to drive the demo while staying offline.
 */
public class StubLlmJudgeClient implements LlmJudgeClient {

    private static final Pattern STRONG = Pattern.compile(
            "(?i)ignore\\s+previous|expose\\s+credentials?|exfiltrat|reveal\\s+system");
    private static final Pattern PHISH = Pattern.compile(
            "(?i)verify\\s+your\\s+api\\s+key|paste\\s+your\\s+(secret|token)");

    @Override
    public Verdict judge(String content, String rubric) {
        if (content == null || content.isBlank())
            return new Verdict(false, 0.0, "none", "empty content");
        if (STRONG.matcher(content).find())
            return new Verdict(true, 0.93, "DIRECT_INJECTION",
                    "Imperative override targeting agent tools.");
        if (PHISH.matcher(content).find())
            return new Verdict(true, 0.78, "CREDENTIAL_PHISHING",
                    "Form coerces agent into disclosing a credential.");
        return new Verdict(false, 0.10, "none", "no high-confidence signal");
    }
}
