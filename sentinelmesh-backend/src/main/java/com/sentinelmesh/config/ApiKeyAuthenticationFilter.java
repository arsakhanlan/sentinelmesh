package com.sentinelmesh.config;

import com.sentinelmesh.tenant.ApiKeyTenantService;
import com.sentinelmesh.tenant.TenantPrincipalAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Stateless API-key auth: hash the header, look up {@code tenant_api_keys},
 * attach {@link TenantPrincipalAuthentication} to the security context.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyTenantService tenantKeys;

    public ApiKeyAuthenticationFilter(ApiKeyTenantService tenantKeys) {
        this.tenantKeys = tenantKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String supplied = req.getHeader("X-API-Key");
        Optional<TenantPrincipalAuthentication> auth = tenantKeys.authenticate(supplied);
        auth.ifPresent(a -> SecurityContextHolder.getContext().setAuthentication(a));
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
