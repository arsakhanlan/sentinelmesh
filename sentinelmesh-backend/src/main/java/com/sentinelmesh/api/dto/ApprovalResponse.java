package com.sentinelmesh.api.dto;

import com.sentinelmesh.domain.model.Approval;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ApprovalResponse(
        UUID id, UUID sessionId, UUID actionId, String intent,
        Map<String, Object> requestedPayload, Map<String, Object> modifiedPayload,
        String approverId, String decision, String status,
        double blastRadius, Instant requestedAt, Instant decidedAt, Instant ttlAt
) {
    public static ApprovalResponse from(Approval a) {
        return new ApprovalResponse(a.id(), a.sessionId(), a.actionId(), a.intent(),
                a.requestedPayload(), a.modifiedPayload(),
                a.approverId(), a.decision() == null ? null : a.decision().name(),
                a.status().name(), a.blastRadius(),
                a.requestedAt(), a.decidedAt(), a.ttlAt());
    }
}
