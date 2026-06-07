package com.sentinelmesh.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Session(
        UUID id,
        String userId,
        String goal,
        Status status,
        String policyBundleId,
        Map<String, Object> capabilityToken,
        Instant createdAt,
        Instant endedAt,
        /** Owning tenant for per-tenant budgets and SOC isolation; nullable for legacy rows. */
        UUID tenantId
) {
    public enum Status { CREATED, PLANNING, EXECUTING, AWAITING_APPROVAL, COMPLETED, QUARANTINED, FAILED }

    public Session withStatus(Status newStatus) {
        return new Session(id, userId, goal, newStatus, policyBundleId,
                capabilityToken, createdAt, endedAt, tenantId);
    }
}
