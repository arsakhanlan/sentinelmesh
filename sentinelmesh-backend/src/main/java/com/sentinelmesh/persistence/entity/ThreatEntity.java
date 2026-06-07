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
@Table(name = "threats", indexes = {
        @Index(name = "idx_threats_session", columnList = "session_id"),
        @Index(name = "idx_threats_category", columnList = "category")
})
public class ThreatEntity {

    @Id @Column(columnDefinition = "uuid") private UUID id;
    @Column(name = "session_id", nullable = false, columnDefinition = "uuid") private UUID sessionId;
    @Column(name = "action_id", columnDefinition = "uuid") private UUID actionId;
    @Column(nullable = false, length = 64) private String category;
    @Column(nullable = false, length = 16) private String severity;
    @Column(nullable = false, precision = 4, scale = 3) private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evidence = new HashMap<>();

    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public ThreatEntity() {}

    public ThreatEntity(UUID id, UUID sessionId, UUID actionId, String category,
                        String severity, BigDecimal score, Map<String, Object> evidence,
                        Instant createdAt) {
        this.id = id; this.sessionId = sessionId; this.actionId = actionId;
        this.category = category; this.severity = severity; this.score = score;
        this.evidence = evidence; this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getActionId() { return actionId; }
    public String getCategory() { return category; }
    public String getSeverity() { return severity; }
    public BigDecimal getScore() { return score; }
    public Map<String, Object> getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
}
