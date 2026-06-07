package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.port.out.ContentSafetyCategoriesClient;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * L2 — Azure AI Content Safety category classifier.
 *
 * <p>Where L1 catches structural injection (regex for "ignore previous", hidden
 * DOM tricks, credential phishing), L2 catches <em>harmful content categories</em>
 * — Azure Content Safety's {@code text:analyze} axes: Hate / SelfHarm / Sexual /
 * Violence — on a 0–7 severity scale. The actual scoring is delegated to a
 * {@link ContentSafetyCategoriesClient} so the production deployment talks to
 * the real Azure endpoint while the offline demo gets a deterministic stub
 * with the same return shape.
 *
 * <p>This layer was deliberately wired in at @Order(20) so the pipeline reads
 * end-to-end as L1 → L2 → L3 → L4 → L5 → CAP → DLP → L6 → L7. Without it the
 * pipeline had a numbering gap, which made it harder to talk through in the
 * demo (and made the architecture diagram lie).
 *
 * <h3>Score mapping</h3>
 * <p>Azure severity → SentinelMesh score in [0,1]:
 * <ul>
 *   <li>severity 7 → 0.95 (saturating BLOCK)</li>
 *   <li>severity 6 → 0.80</li>
 *   <li>severity 4–5 → 0.55–0.65 (REQUIRE_APPROVAL band)</li>
 *   <li>severity 2–3 → 0.20–0.30 (medium-risk REWRITE band)</li>
 *   <li>severity 0–1 → 0.0 (clean)</li>
 * </ul>
 * <p>Multiple flagged categories add a small bonus so e.g.
 * Violence=4 + Hate=4 lands above the BLOCK threshold even though neither
 * individually would.
 */
@Component
@Order(20)
public class L2ContentSafetyCategoryScanner implements ScannerStage {

    private final ContentSafetyCategoriesClient client;

    public L2ContentSafetyCategoryScanner(ContentSafetyCategoriesClient client) {
        this.client = client;
    }

    @Override public String name() { return "L2"; }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        Double l1 = scoresSoFar.get("L1");
        // If L1 already saturated, downstream will BLOCK anyway — skip the work.
        return l1 == null || l1 < 0.95;
    }

    @Override
    public Finding scan(InspectionInput input) {
        String text = extractText(input);
        if (text.isEmpty()) return Finding.clean("no text");

        ContentSafetyCategoriesClient.Result r = client.analyze(text);
        if (r == null) {
            // Transport failure — fail open so the rest of the pipeline still
            // gets to score the payload. The L4 LLM judge typically catches
            // anything we'd miss at this layer.
            return Finding.clean("L2 transport unavailable");
        }
        if (r.maxSeverity() <= 0) return Finding.clean("L2 categories: clean");

        double score = severityToScore(r.maxSeverity());
        // Compound when multiple categories light up — single category at 4
        // is REQUIRE_APPROVAL, two at 4 is BLOCK.
        if (r.flaggedCategories().size() >= 2) {
            score = Math.min(1.0, score + 0.15);
        }

        Map<String, Object> ev = new HashMap<>();
        ev.put("severities", r.categorySeverities());
        ev.put("max_severity", r.maxSeverity());
        ev.put("flagged_categories", r.flaggedCategories());

        return new Finding(score,
                "L2 content-safety categories: " + r.flaggedCategories(),
                ev);
    }

    private static double severityToScore(int severity) {
        return switch (severity) {
            case 0, 1 -> 0.0;
            case 2 -> 0.20;
            case 3 -> 0.30;
            case 4 -> 0.55;
            case 5 -> 0.65;
            case 6 -> 0.80;
            case 7 -> 0.95;
            default -> Math.min(1.0, severity / 7.0);
        };
    }

    private static String extractText(InspectionInput input) {
        if (input instanceof InspectionInput.InboundContent c) return c.content();
        if (input instanceof InspectionInput.OutboundAction a)
            return a.args() == null ? "" : a.args().toString();
        return "";
    }
}
