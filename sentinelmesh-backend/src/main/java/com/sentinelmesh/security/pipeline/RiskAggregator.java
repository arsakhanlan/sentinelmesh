package com.sentinelmesh.security.pipeline;

import java.util.Map;

/**
 * Combines per-scanner scores into a single composite risk in [0,1].
 * Currently a simple weighted average — policy weights live elsewhere.
 */
public final class RiskAggregator {

    private final Map<String, Double> weights;

    public RiskAggregator(Map<String, Double> weights) {
        this.weights = Map.copyOf(weights);
    }

    public static RiskAggregator defaults() {
        return new RiskAggregator(Map.of(
                "L1", 1.0,
                "L2", 1.2,
                "L3", 1.5,
                "L4", 1.5,
                "L5", 0.8,
                "CAP", 2.2,
                "DLP", 1.4
        ));
    }

    public double aggregate(Map<String, Double> scores) {
        if (scores.isEmpty()) return 0.0;
        double weighted = 0.0;
        double weightTotal = 0.0;
        double max = 0.0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            double w = weights.getOrDefault(e.getKey(), 1.0);
            weighted += w * e.getValue();
            weightTotal += w;
            max = Math.max(max, e.getValue());
        }
        double avg = weighted / weightTotal;
        // Blend weighted-average with max so a single strong signal cannot be diluted.
        return Math.min(1.0, 0.6 * max + 0.4 * avg);
    }
}
