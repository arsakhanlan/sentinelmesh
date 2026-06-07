package com.sentinelmesh.api.dto;

import com.sentinelmesh.domain.model.Threat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ThreatResponse(
        UUID id, UUID sessionId, UUID actionId, String category, String severity,
        double score, Map<String, Object> evidence, Instant createdAt
) {
    public static ThreatResponse from(Threat t) {
        return new ThreatResponse(t.id(), t.sessionId(), t.actionId(),
                t.category(), t.severity().name(), t.score(),
                t.evidence(), t.createdAt());
    }
}
