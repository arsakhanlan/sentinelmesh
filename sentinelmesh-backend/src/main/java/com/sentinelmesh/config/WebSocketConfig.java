package com.sentinelmesh.config;

import com.sentinelmesh.api.ws.EventWebSocketHandler;
import com.sentinelmesh.tenant.ApiKeyTenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket wiring. Handshakes accept any API key registered for a tenant
 * (same resolution as REST {@code X-API-Key}).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final EventWebSocketHandler handler;
    private final ApiKeyTenantService tenantKeys;

    public WebSocketConfig(EventWebSocketHandler handler, ApiKeyTenantService tenantKeys) {
        this.handler = handler;
        this.tenantKeys = tenantKeys;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        ApiKeyHandshakeInterceptor auth = new ApiKeyHandshakeInterceptor(tenantKeys);
        registry.addHandler(handler, "/ws/sessions/**")
                .addInterceptors(auth).setAllowedOriginPatterns("*");
        registry.addHandler(handler, "/ws/events")
                .addInterceptors(auth).setAllowedOriginPatterns("*");
    }

    public static class ApiKeyHandshakeInterceptor implements HandshakeInterceptor {

        private static final Logger log = LoggerFactory.getLogger(ApiKeyHandshakeInterceptor.class);

        private final ApiKeyTenantService tenantKeys;

        public ApiKeyHandshakeInterceptor(ApiKeyTenantService tenantKeys) {
            this.tenantKeys = tenantKeys;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String supplied = tokenFromQuery(request.getURI().getQuery());
            if (supplied == null) {
                supplied = request.getHeaders().getFirst("X-API-Key");
            }
            if (tenantKeys.authenticate(supplied).isPresent()) {
                return true;
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.warn("Rejected unauthenticated WebSocket handshake to {}", request.getURI().getPath());
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        private static String tokenFromQuery(String query) {
            if (query == null || query.isEmpty()) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq);
                if ("token".equals(key) || "apiKey".equals(key)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }
}
