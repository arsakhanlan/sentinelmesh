package com.sentinelmesh.adversary.scenarios;

import com.sentinelmesh.adversary.AgentSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the SOC through a <em>service-impersonation</em> story on the external
 * {@code POST /api/v1/events} path: a runtime claims {@code FakeExecutor}, the
 * mesh rejects it with {@code 403}, and a {@code AGENT_IDENTITY_VIOLATION}
 * threat appears on the firehose before a clean tool call proves the session is
 * still healthy.
 */
@Component
public class AgentIdentitySpoofScenario implements AttackScenario {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentitySpoofScenario.class);

    @Value("${sentinelmesh.api-key:dev-api-key-change-me}")
    private String apiKey;

    /** Base URL of this backend as seen from inside the JVM (demo default: loopback). */
    @Value("${sentinelmesh.event-ingest.self-base-url:http://127.0.0.1:8080}")
    private String selfBaseUrl;

    @Override public String id() { return "agent_identity_spoof"; }

    @Override public String displayName() { return "Agent identity spoof (ingest)"; }

    @Override public String description() {
        return "A rogue runtime posts tool_call as FakeExecutor to /api/v1/events — mesh rejects + threat, then a legitimate call.";
    }

    @Override
    public void play(UUID sessionId, AgentSimulator sim) {
        sim.plan(sessionId, "Demonstrate agent identity controls on external event ingest",
                Map.of("steps", java.util.List.of(
                        "publish legitimate plan",
                        "spoof disallowed actor via HTTP ingest",
                        "continue with inspected tool call")));
        sim.sleep(200);

        String json = String.format(Locale.US, """
                {"sessionId":"%s","kind":"tool_call","actor":"FakeExecutor","payload":{"tool":"email.send","args":{}}}
                """, sessionId);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(selfBaseUrl) + "/api/v1/events"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 403) {
                log.warn("Expected HTTP 403 from spoofed ingest, got {} body={}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Spoof ingest HTTP call failed (backend may be on a different bind URL): {}", e.toString());
        }

        sim.sleep(350);
        sim.toolCall(sessionId, "http.get", Map.of("url", "https://example.com/"));
    }

    private static String trimSlash(String u) {
        if (u == null || u.isEmpty()) return "http://127.0.0.1:8080";
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
