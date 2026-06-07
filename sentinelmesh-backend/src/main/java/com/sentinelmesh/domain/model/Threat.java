package com.sentinelmesh.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Threat(
        UUID id,
        UUID sessionId,
        UUID actionId,
        String category,
        Severity severity,
        double score,
        Map<String, Object> evidence,
        Instant createdAt
) {
    public enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

    public static Severity severityFromScore(double s) {
        if (s >= 0.9) return Severity.CRITICAL;
        if (s >= 0.7) return Severity.HIGH;
        if (s >= 0.4) return Severity.MEDIUM;
        if (s >= 0.2) return Severity.LOW;
        return Severity.INFO;
    }
}
