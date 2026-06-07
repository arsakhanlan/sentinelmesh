package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.port.out.PromptShieldClient;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L3 — Azure AI Content Safety "Prompt Shields" classifier.
 *
 * <p>Wraps {@link PromptShieldClient} which has two implementations:
 * a real Azure HTTP client (profile=real) and a deterministic stub
 * (profile=stub) that the hackathon demo can run against.
 */
@Component
@Order(30)
public class L3PromptShieldScanner implements ScannerStage {

    private final PromptShieldClient client;

    public L3PromptShieldScanner(PromptShieldClient client) { this.client = client; }

    @Override public String name() { return "L3"; }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        // Skip if L1 already saturated — no point spending the API call.
        Double l1 = scoresSoFar.get("L1");
        if (l1 != null && l1 >= 0.95) return false;
        return input instanceof InspectionInput.InboundContent;
    }

    @Override
    public Finding scan(InspectionInput input) {
        String content = ((InspectionInput.InboundContent) input).content();
        PromptShieldClient.Result r = client.scan(content);
        if (r.score() <= 0.0) return Finding.clean("prompt-shield: clean");
        return new Finding(r.score(),
                "Azure Prompt Shield: " + r.technique(),
                Map.of("technique", r.technique(),
                       "evidence_span", r.evidenceSpan() == null ? "" : r.evidenceSpan()));
    }
}
