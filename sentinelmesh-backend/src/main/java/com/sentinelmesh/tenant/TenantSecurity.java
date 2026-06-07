package com.sentinelmesh.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the tenant resolved from {@code X-API-Key} during the request.
 * Populated by {@link com.sentinelmesh.config.ApiKeyAuthenticationFilter}.
 */
public final class TenantSecurity {

    private TenantSecurity() {}

    public static Optional<UUID> currentTenantId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof TenantPrincipalAuthentication tp) {
            return Optional.ofNullable(tp.tenantId());
        }
        return Optional.empty();
    }

    public static Optional<String> currentTenantName() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof TenantPrincipalAuthentication tp) {
            return Optional.ofNullable(tp.tenantName());
        }
        return Optional.empty();
    }
}
