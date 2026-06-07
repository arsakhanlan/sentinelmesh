package com.sentinelmesh.tenant;

import com.sentinelmesh.persistence.entity.TenantApiKeyEntity;
import com.sentinelmesh.persistence.entity.TenantEntity;
import com.sentinelmesh.persistence.repository.TenantApiKeyJpaRepository;
import com.sentinelmesh.persistence.repository.TenantJpaRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an inbound raw API key to a tenant via {@code tenant_api_keys.api_key_hash}.
 */
@Service
public class ApiKeyTenantService {

    private final TenantApiKeyJpaRepository keys;
    private final TenantJpaRepository tenants;

    public ApiKeyTenantService(TenantApiKeyJpaRepository keys, TenantJpaRepository tenants) {
        this.keys = keys;
        this.tenants = tenants;
    }

    public Optional<TenantPrincipalAuthentication> authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) return Optional.empty();
        String hash = sha256Hex(rawApiKey);
        Optional<TenantApiKeyEntity> row = keys.findByApiKeyHash(hash);
        if (row.isEmpty()) return Optional.empty();
        UUID tid = row.get().getTenantId();
        return tenants.findById(tid).map(t -> new TenantPrincipalAuthentication(
                "apiClient", tid, t.getName(),
                List.of(new SimpleGrantedAuthority("ROLE_API"))));
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
