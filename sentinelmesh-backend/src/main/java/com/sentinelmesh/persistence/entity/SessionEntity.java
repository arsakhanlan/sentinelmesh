package com.sentinelmesh.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "policy_bundle_id", nullable = false)
    private String policyBundleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_token", columnDefinition = "jsonb")
    private Map<String, Object> capabilityToken = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "tenant_id", columnDefinition = "uuid")
    private UUID tenantId;

    public SessionEntity() {}

    public SessionEntity(UUID id, String userId, String goal, String status,
                          String policyBundleId, Map<String, Object> capabilityToken,
                          Instant createdAt, Instant endedAt, UUID tenantId) {
        this.id = id; this.userId = userId; this.goal = goal; this.status = status;
        this.policyBundleId = policyBundleId; this.capabilityToken = capabilityToken;
        this.createdAt = createdAt; this.endedAt = endedAt; this.tenantId = tenantId;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public String getGoal() { return goal; }
    public String getStatus() { return status; }
    public String getPolicyBundleId() { return policyBundleId; }
    public Map<String, Object> getCapabilityToken() { return capabilityToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public UUID getTenantId() { return tenantId; }

    public void setStatus(String status) { this.status = status; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
