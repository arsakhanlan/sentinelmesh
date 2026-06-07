package com.sentinelmesh.tenant;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/**
 * API client authenticated with a tenant-bound API key.
 */
public final class TenantPrincipalAuthentication extends AbstractAuthenticationToken {

    private final String principalName;
    private final UUID tenantId;
    private final String tenantName;

    public TenantPrincipalAuthentication(String principalName, UUID tenantId, String tenantName,
                                         Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principalName = principalName;
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        setAuthenticated(true);
    }

    public UUID tenantId() { return tenantId; }
    public String tenantName() { return tenantName; }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return principalName; }
}
