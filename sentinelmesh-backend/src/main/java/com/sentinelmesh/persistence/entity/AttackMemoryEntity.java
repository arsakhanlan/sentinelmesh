package com.sentinelmesh.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent backing store for one entry in the L7 attack-memory bank.
 *
 * <p>The {@code embedding} is stored as a JSON array of floats (TEXT). We
 * deliberately avoid pgvector: this bank is small, the cosine math runs
 * cheaply in the JVM, and every Postgres install supports plain TEXT.
 */
@Entity
@Table(name = "attack_memory", indexes = {
        @Index(name = "idx_attack_memory_added_at_desc", columnList = "added_at DESC")
})
public class AttackMemoryEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 128)
    private String reason;

    @Column(nullable = false, length = 256)
    private String preview;

    @Column(nullable = false, columnDefinition = "text")
    private String embedding;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public AttackMemoryEntity() {}

    public AttackMemoryEntity(UUID id, String reason, String preview,
                              String embedding, Instant addedAt) {
        this.id = id;
        this.reason = reason;
        this.preview = preview;
        this.embedding = embedding;
        this.addedAt = addedAt;
    }

    public UUID getId() { return id; }
    public String getReason() { return reason; }
    public String getPreview() { return preview; }
    public String getEmbedding() { return embedding; }
    public Instant getAddedAt() { return addedAt; }
}
