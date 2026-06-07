package com.sentinelmesh.api.dto;

import com.sentinelmesh.domain.model.Session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SessionResponse(
        UUID id, String userId, String goal, String status,
        String policyBundleId, Map<String, Object> capabilityToken,
        Instant createdAt, Instant endedAt,
        UUID tenantId
) {
    public static SessionResponse from(Session s) {
        return new SessionResponse(s.id(), s.userId(), s.goal(), s.status().name(),
                s.policyBundleId(), s.capabilityToken(), s.createdAt(), s.endedAt(), s.tenantId());
    }
}
