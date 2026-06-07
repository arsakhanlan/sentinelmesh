package com.sentinelmesh.security.blast;

import com.sentinelmesh.domain.model.InspectionInput;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Heuristic blast-radius estimator. Returns a value in [0, 1] where 1.0 is
 * "potentially catastrophic, hard to reverse." See design doc §5.6.
 */
@Component
public class BlastRadiusEstimator {

    // First-party, idempotent, fully reversible writes (e.g. bookings.create
    // on the SkyNest demo backend) are deliberately rated low: they involve
    // no external money movement and a misfire can be cancel_booking'd. This
    // is what lets the "happy path" hotel-booking flow land below the 0.7
    // approval threshold without weakening checks on real payments.
    private static final Map<String, Double> BLAST = Map.ofEntries(
            // notes.append writes to the agent's local scratchpad — no
            // external delivery, no money, fully discardable. Pin it lower
            // even than browser.goto so a "summarise the result" tail step
            // never crosses an approval threshold.
            Map.entry("notes.append",     0.02),
            Map.entry("browser.goto",     0.05),
            Map.entry("browser.fill",     0.15),
            Map.entry("http.get",         0.20),
            Map.entry("bookings.create",  0.30),
            Map.entry("http.post",        0.45),
            Map.entry("email.send",       0.70),
            Map.entry("payments.charge",  0.95),
            Map.entry("payments.refund",  0.80),
            Map.entry("db.write",         0.90),
            Map.entry("db.delete",        0.95),
            Map.entry("files.write",      0.40)
    );

    public double estimate(InspectionInput input) {
        if (!(input instanceof InspectionInput.OutboundAction a)) return 0.0;
        double base = BLAST.getOrDefault(a.tool(), 0.10);
        // Inflate based on argument heuristics.
        Object amount = a.args() == null ? null : a.args().get("amount");
        if (amount instanceof Number n && n.doubleValue() >= 50_000) base = Math.min(1.0, base + 0.20);
        Object recipient = a.args() == null ? null : a.args().get("to");
        if (recipient instanceof String s && s.contains("@") && !s.endsWith("@company.com")) {
            base = Math.min(1.0, base + 0.10);
        }
        return base;
    }
}
