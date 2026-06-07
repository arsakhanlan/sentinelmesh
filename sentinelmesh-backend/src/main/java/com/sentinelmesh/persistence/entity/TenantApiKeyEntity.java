package com.sentinelmesh.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_api_keys")
public class TenantApiKeyEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    private UUID tenantId;

    @Column(name = "api_key_hash", nullable = false, length = 64, unique = true)
    private String apiKeyHash;

    @Column(length = 64)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TenantApiKeyEntity() {}

    public TenantApiKeyEntity(UUID id, UUID tenantId, String apiKeyHash, String label, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.apiKeyHash = apiKeyHash;
        this.label = label;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getApiKeyHash() { return apiKeyHash; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
}
