package com.sentinelmesh.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-scanner scores plus the policy-weighted composite. All values in [0, 1].
 *
 * <p>Using an insertion-ordered map keeps the timeline UI rendering scanners in
 * the order they fired (L1 → L2 → L3 → L4 → L5).
 */
public record RiskScore(Map<String, Double> scores, double composite) {

    public RiskScore {
        scores = Map.copyOf(scores);
        if (composite < 0.0 || composite > 1.0) {
            throw new IllegalArgumentException("composite must be in [0,1], got " + composite);
        }
    }

    public static RiskScore of(Map<String, Double> scores, double composite) {
        return new RiskScore(new LinkedHashMap<>(scores), composite);
    }

    public static RiskScore safe() { return new RiskScore(Map.of(), 0.0); }
}
