package com.sentinelmesh.tenant;

import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.persistence.entity.TenantApiKeyEntity;
import com.sentinelmesh.persistence.repository.TenantApiKeyJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registers API keys for seed tenants on startup (idempotent upsert by hash).
 */
@Component
@Order(0)
public class TenantApiKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantApiKeyBootstrap.class);

    private final TenantApiKeyJpaRepository keys;
    private final Clock clock;

    @Value("${sentinelmesh.api-key:dev-api-key-change-me}") private String globexApiKey;
    /** Second demo tenant — tight budgets for the budget-runaway scenario. */
    @Value("${sentinelmesh.acme-api-key:acme-demo-key-low-budget}") private String acmeApiKey;
    /** Public-demo key injected by Caddy for anonymous SOC dashboard visitors. */
    @Value("${sentinelmesh.public-demo-key:dev-public-demo-key}") private String publicDemoKey;

    public TenantApiKeyBootstrap(TenantApiKeyJpaRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = clock.instant();
        upsert(TenantIds.GLOBEX, globexApiKey, "globex-default", now);
        upsert(TenantIds.GLOBEX, publicDemoKey, "public-demo", now);
        upsert(TenantIds.ACME, acmeApiKey, "acme-default", now);
        log.info("Tenant API keys bootstrapped for globex + public-demo + acme");
    }

    private void upsert(UUID tenantId, String rawKey, String label, Instant now) {
        if (rawKey == null || rawKey.isBlank()) return;
        String hash = ApiKeyTenantService.sha256Hex(rawKey);
        if (keys.findByApiKeyHash(hash).isPresent()) return;
        keys.save(new TenantApiKeyEntity(UuidV7.next(), tenantId, hash, label, now));
    }
}
