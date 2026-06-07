package com.sentinelmesh.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_session", columnList = "session_id"),
        @Index(name = "idx_audit_ts", columnList = "ts")
})
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sequence;

    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    private UUID eventId;

    @Column(name = "session_id", columnDefinition = "uuid")
    private UUID sessionId;

    @Column(nullable = false) private Instant ts;
    @Column(nullable = false, length = 64) private String kind;
    @Column(nullable = false, length = 64) private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "prev_hash", nullable = false, columnDefinition = "bytea")
    private byte[] prevHash;

    @Column(name = "hash", nullable = false, columnDefinition = "bytea")
    private byte[] hash;

    public AuditEventEntity() {}

    public AuditEventEntity(UUID eventId, UUID sessionId, Instant ts, String kind,
                             String actor, Map<String, Object> payload,
                             byte[] prevHash, byte[] hash) {
        this.eventId = eventId; this.sessionId = sessionId; this.ts = ts;
        this.kind = kind; this.actor = actor; this.payload = payload;
        this.prevHash = prevHash; this.hash = hash;
    }

    public Long getSequence() { return sequence; }
    public UUID getEventId() { return eventId; }
    public UUID getSessionId() { return sessionId; }
    public Instant getTs() { return ts; }
    public String getKind() { return kind; }
    public String getActor() { return actor; }
    public Map<String, Object> getPayload() { return payload; }
    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
}
