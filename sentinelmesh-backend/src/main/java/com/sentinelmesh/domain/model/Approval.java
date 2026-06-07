package com.sentinelmesh.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Approval(
        UUID id,
        UUID sessionId,
        UUID actionId,
        Map<String, Object> requestedPayload,
        String approverId,
        Decision decision,
        Map<String, Object> modifiedPayload,
        double blastRadius,
        String intent,
        Instant requestedAt,
        Instant decidedAt,
        Instant ttlAt,
        Status status
) {
    public enum Status { PENDING, APPROVED, DENIED, MODIFIED, EXPIRED }

    public Approval decide(Decision d, String approver, Map<String, Object> modified, Instant now) {
        Status s = switch (d) {
            case ALLOW -> (modified != null && !modified.isEmpty()) ? Status.MODIFIED : Status.APPROVED;
            case BLOCK -> Status.DENIED;
            default -> Status.DENIED;
        };
        return new Approval(id, sessionId, actionId, requestedPayload, approver,
                d, modified, blastRadius, intent, requestedAt, now, ttlAt, s);
    }

    public boolean isExpired(Instant now) {
        return status == Status.PENDING && ttlAt != null && now.isAfter(ttlAt);
    }
}
