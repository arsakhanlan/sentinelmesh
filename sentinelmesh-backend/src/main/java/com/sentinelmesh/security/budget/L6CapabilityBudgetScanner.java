package com.sentinelmesh.security.budget;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L6 — Capability budget enforcement.
 *
 * <p>Runs on every outbound tool call and asks one question:
 * <em>"would running this call exceed the session's capability token?"</em>
 *
 * <p>This is the layer that makes the {@code capability_token} more than
 * paperwork. Even if the prompt-injection scanners miss something, the
 * behavioral anomaly model is in cold-start, and the DLP filter has nothing
 * to redact, the agent <b>cannot</b> send more than {@code email.send: 1}
 * emails or charge more than {@code spend_cap_inr} in a single session.
 *
 * <p>The scanner publishes a high-score finding when over budget, evidenced
 * by structured data (used/cap, spend used/cap, which tool, why) so the
 * policy engine can BLOCK with a precise reason and the SOC drawer can
 * visualise the exhaustion in real time.
 */
@Component
@Order(70) // after DLP (60), L5 (50) — budget is the last word before policy.
public class L6CapabilityBudgetScanner implements ScannerStage {

    private static final Logger log = LoggerFactory.getLogger(L6CapabilityBudgetScanner.class);

    private final BudgetTracker tracker;

    public L6CapabilityBudgetScanner(BudgetTracker tracker) {
        this.tracker = tracker;
    }

    @Override public String name() { return "L6"; }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        return input instanceof InspectionInput.OutboundAction;
    }

    @Override
    public Finding scan(InspectionInput input) {
        InspectionInput.OutboundAction a = (InspectionInput.OutboundAction) input;
        BudgetTracker.Check check = tracker.recordAndCheck(a.sessionId(), a.tool(), a.args());

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("tool", check.tool());
        ev.put("used", check.used());
        ev.put("cap", check.cap() == Integer.MAX_VALUE ? -1 : check.cap());
        ev.put("spend_used_inr", check.spendUsed());
        ev.put("spend_cap_inr", check.spendCap());
        ev.put("over_budget", check.exceeded());
        ev.put("budget_scope", check.scope().name());

        if (check.exceeded()) {
            log.info("L6 budget exceeded for session {}: {} ({})", a.sessionId(), check.reason(), check.scope());
            String prefix = check.scope() == BudgetTracker.BudgetScope.TENANT
                    ? "Tenant budget exceeded: " : "Capability budget exceeded: ";
            return new Finding(1.0, prefix + check.reason(), ev);
        }
        // Warn signal at 80%+ utilisation so the SOC can foreshadow exhaustion
        // (doesn't itself block — just informs the timeline).
        double headroom = (double) check.used() / Math.max(1, check.cap());
        if (check.cap() != Integer.MAX_VALUE && headroom >= 0.8) {
            return new Finding(0.25,
                    "Approaching budget: " + check.used() + "/" + check.cap() + " " + check.tool(), ev);
        }
        return new Finding(0.0, "Within budget", ev);
    }
}
