package com.sentinelmesh.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "approvals", indexes = {
        @Index(name = "idx_approvals_status", columnList = "status"),
        @Index(name = "idx_approvals_ttl", columnList = "ttl_at")
})
public class ApprovalEntity {

    @Id @Column(columnDefinition = "uuid") private UUID id;
    @Column(name = "session_id", nullable = false, columnDefinition = "uuid") private UUID sessionId;
    @Column(name = "action_id", columnDefinition = "uuid") private UUID actionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestedPayload = new HashMap<>();

    @Column(name = "approver_id") private String approverId;
    @Column(name = "decision", length = 32) private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modified_payload", columnDefinition = "jsonb")
    private Map<String, Object> modifiedPayload;

    @Column(name = "blast_radius", precision = 4, scale = 3) private BigDecimal blastRadius;
    @Column(columnDefinition = "text") private String intent;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "ttl_at") private Instant ttlAt;
    @Column(nullable = false, length = 16) private String status;

    public ApprovalEntity() {}

    public ApprovalEntity(UUID id, UUID sessionId, UUID actionId,
                           Map<String, Object> requestedPayload, String approverId, String decision,
                           Map<String, Object> modifiedPayload, BigDecimal blastRadius, String intent,
                           Instant requestedAt, Instant decidedAt, Instant ttlAt, String status) {
        this.id = id; this.sessionId = sessionId; this.actionId = actionId;
        this.requestedPayload = requestedPayload; this.approverId = approverId; this.decision = decision;
        this.modifiedPayload = modifiedPayload; this.blastRadius = blastRadius; this.intent = intent;
        this.requestedAt = requestedAt; this.decidedAt = decidedAt; this.ttlAt = ttlAt; this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getActionId() { return actionId; }
    public Map<String, Object> getRequestedPayload() { return requestedPayload; }
    public String getApproverId() { return approverId; }
    public String getDecision() { return decision; }
    public Map<String, Object> getModifiedPayload() { return modifiedPayload; }
    public BigDecimal getBlastRadius() { return blastRadius; }
    public String getIntent() { return intent; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getTtlAt() { return ttlAt; }
    public String getStatus() { return status; }
}
