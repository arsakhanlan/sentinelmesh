package com.sentinelmesh.domain.service;

import com.sentinelmesh.common.error.NotFoundException;
import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.port.in.StartSessionUseCase;
import com.sentinelmesh.domain.port.out.EventPublisher;
import com.sentinelmesh.domain.port.out.SessionRepository;
import com.sentinelmesh.tenant.TenantSecurity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService implements StartSessionUseCase {

    private final SessionRepository sessions;
    private final EventPublisher publisher;
    private final Clock clock;

    public SessionService(SessionRepository sessions, EventPublisher publisher, Clock clock) {
        this.sessions = sessions;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public Session start(String userId, String goal, String policyBundleId) {
        Instant now = clock.instant();
        Session created = new Session(
                UuidV7.next(), userId, goal, Session.Status.CREATED,
                policyBundleId == null ? "default" : policyBundleId,
                defaultCapabilityToken(),
                now, null,
                TenantSecurity.currentTenantId().orElse(null));
        Session saved = sessions.save(created);
        publisher.publish(new AgentEvent.StateTransition(
                UuidV7.next(), saved.id(), now, "system", "NONE", "CREATED"));
        return saved;
    }

    /**
     * Default capability grant for a fresh session. The L6 budget scanner reads
     * this; the user (or a higher-trust controller) can override by passing a
     * custom token. We intentionally keep grants tight so the demo can show
     * budget exhaustion easily.
     */
    private static Map<String, Object> defaultCapabilityToken() {
        Map<String, Object> caps = new java.util.LinkedHashMap<>();
        caps.put("email.send", 1);
        caps.put("payments.charge", 1);
        caps.put("http.get", 20);
        caps.put("browser.goto", 12);
        caps.put("notes.append", 20);
        Map<String, Object> token = new java.util.LinkedHashMap<>();
        token.put("can_browse", "*");
        token.put("spend_cap_inr", 7_000);
        token.put("tool_caps", caps);
        return token;
    }

    @Override
    public Session get(UUID id) {
        return sessions.findById(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
    }

    /** Non-throwing lookup. Used by the inspection pipeline to honor quarantine. */
    public Optional<Session> find(UUID id) {
        if (id == null) return Optional.empty();
        return sessions.findById(id);
    }

    /**
     * Freeze a session for forensic inspection after a QUARANTINE decision.
     * Best-effort: persists the status transition when the session row exists,
     * and always emits a {@code state_transition} event so the timeline reflects it.
     */
    public void markQuarantined(UUID sessionId) {
        if (sessionId == null) return;
        Instant now = clock.instant();
        sessions.findById(sessionId).ifPresent(s -> {
            if (s.status() != Session.Status.QUARANTINED) {
                sessions.save(s.withStatus(Session.Status.QUARANTINED));
            }
        });
        publisher.publish(new AgentEvent.StateTransition(
                UuidV7.next(), sessionId, now, "sentinel", "EXECUTING", "QUARANTINED"));
    }
}
