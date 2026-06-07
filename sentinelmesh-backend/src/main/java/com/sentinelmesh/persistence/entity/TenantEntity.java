package com.sentinelmesh.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 128, unique = true)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daily_tool_caps", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dailyToolCaps = new HashMap<>();

    @Column(name = "daily_spend_cap_inr", nullable = false)
    private BigDecimal dailySpendCapInr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TenantEntity() {}

    public TenantEntity(UUID id, String name, Map<String, Object> dailyToolCaps,
                        BigDecimal dailySpendCapInr, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.dailyToolCaps = dailyToolCaps != null ? dailyToolCaps : new HashMap<>();
        this.dailySpendCapInr = dailySpendCapInr;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getDailyToolCaps() { return dailyToolCaps; }
    public BigDecimal getDailySpendCapInr() { return dailySpendCapInr; }
    public Instant getCreatedAt() { return createdAt; }
}
