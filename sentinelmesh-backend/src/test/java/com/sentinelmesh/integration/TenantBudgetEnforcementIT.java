package com.sentinelmesh.integration;

import com.sentinelmesh.api.dto.CreateSessionRequest;
import com.sentinelmesh.api.dto.InspectResponse;
import com.sentinelmesh.api.dto.SessionResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-tenant isolation + tenant daily caps on the live stack.
 *
 * <p>Requires Postgres on {@code localhost:5432} (same as the dev compose stack)
 * and the Flyway V4 seed tenants + {@link com.sentinelmesh.tenant.TenantApiKeyBootstrap}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("concurrency")
class TenantBudgetEnforcementIT {

    private static final String ACME_KEY = "acme-demo-key-low-budget";
    private static final String GLOBEX_KEY = "dev-api-key-change-me";

    @LocalServerPort int port;

    @Autowired TestRestTemplate restTemplate;

    @BeforeAll
    static void requirePostgresOnLocalhost() {
        try (Socket s = new Socket("localhost", 5432)) {
            assumeTrue(s.isConnected(), "Postgres on localhost:5432 not reachable");
        } catch (Exception e) {
            assumeTrue(false, "Postgres on localhost:5432 not reachable: " + e.getMessage());
        }
    }

    @Test
    void tenant_daily_email_cap_blocks_second_session_while_other_tenant_unaffected() {
        String base = "http://localhost:" + port;

        HttpHeaders acmeHeaders = jsonHeaders(ACME_KEY);
        HttpHeaders globexHeaders = jsonHeaders(GLOBEX_KEY);

        SessionResponse acmeSession = postSession(base, acmeHeaders, "tenant-it-acme", "book hotel");
        assertThat(acmeSession.tenantId()).isNotNull();

        SessionResponse globexSession = postSession(base, globexHeaders, "tenant-it-globex", "book hotel");
        assertThat(globexSession.tenantId()).isNotNull();

        // Exhaust acme's tenant daily http.get cap (20) across one session, then
        // prove a fresh session in the same tenant still hits the org-wide 24h wall.
        for (int i = 0; i < 20; i++) {
            InspectResponse step = postInspect(base, acmeHeaders, acmeSession.id(), "http.get", Map.of());
            assertThat(step.decision()).withFailMessage("iteration " + i + " reason=" + step.reason())
                    .isNotEqualTo("BLOCK");
        }

        SessionResponse acmeSession2 = postSession(base, acmeHeaders, "tenant-it-acme-2", "retry");
        InspectResponse a2 = postInspect(base, acmeHeaders, acmeSession2.id(), "http.get", Map.of());
        assertThat(a2.decision()).isEqualTo("BLOCK");
        assertThat(a2.reason()).containsIgnoringCase("tenant");

        InspectResponse g1 = postInspect(base, globexHeaders, globexSession.id(), "http.get", Map.of());
        assertThat(g1.decision()).isNotEqualTo("BLOCK");
    }

    private static HttpHeaders jsonHeaders(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", apiKey);
        return h;
    }

    private SessionResponse postSession(String base, HttpHeaders headers, String user, String goal) {
        CreateSessionRequest body = new CreateSessionRequest(user, goal, null);
        ResponseEntity<SessionResponse> res = restTemplate.exchange(
                base + "/api/v1/sessions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                SessionResponse.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private InspectResponse postInspect(String base, HttpHeaders headers, UUID sessionId,
                                        String tool, Map<String, Object> args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("actionId", UUID.randomUUID());
        body.put("direction", "OUTBOUND");
        body.put("tool", tool);
        body.put("args", args);
        body.put("content", null);
        body.put("meta", Map.of());

        ResponseEntity<InspectResponse> res = restTemplate.exchange(
                base + "/api/v1/sentinel/inspect",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                InspectResponse.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }
}
