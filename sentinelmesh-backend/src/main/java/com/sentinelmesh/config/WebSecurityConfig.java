package com.sentinelmesh.config;

import com.sentinelmesh.tenant.ApiKeyTenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal API-key gateway. Endpoints listed in {@link #PUBLIC_PATHS} are open;
 * everything else requires a valid {@code X-API-Key} registered for a tenant
 * in {@code tenant_api_keys}.
 */
@Configuration
public class WebSecurityConfig {

    private static final java.util.List<String> PUBLIC_PATHS = java.util.List.of(
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/swagger-ui", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
            "/ws/**", "/error"
    );

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyTenantService tenantKeys) {
        return new ApiKeyAuthenticationFilter(tenantKeys);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS.toArray(new String[0])).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Constant-time string comparison for WebSocket handshake (and any other
     * non-filter callers that need timing-safe equality).
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }
}
