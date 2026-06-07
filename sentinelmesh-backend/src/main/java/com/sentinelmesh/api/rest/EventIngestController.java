package com.sentinelmesh.api.rest;

import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.model.Threat;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.domain.port.out.EventPublisher;
import com.sentinelmesh.domain.port.out.ThreatRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * External event ingest. The Python agent service uses this to publish plan,
 * tool_call, tool_result events into the same stream the websocket emits and
 * the audit log records.
 *
 * <p>Untrusted clients cannot invent arbitrary {@code actor} identities unless
 * they appear in {@code sentinelmesh.event-ingest.actor-allowlist} (or the list
 * is set to {@code *} to disable the check). Rejected attempts emit a
 * high-severity {@code threat} event so the SOC timeline lights up.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "External event ingest (for non-Java agents)")
public class EventIngestController {

    private final EventPublisher publisher;
    private final AuditEventSink audit;
    private final Clock clock;
    private final EventIngestActorGate actorGate;
    private final ThreatRepository threats;

    public EventIngestController(EventPublisher publisher, AuditEventSink audit, Clock clock,
                                 EventIngestActorGate actorGate, ThreatRepository threats) {
        this.publisher = publisher;
        this.audit = audit;
        this.clock = clock;
        this.actorGate = actorGate;
        this.threats = threats;
    }

    public record PublishRequest(
            UUID sessionId,
            @NotBlank String kind,                // "plan" | "tool_call" | "tool_result" | "state_transition"
            @NotBlank String actor,               // "planner" | "executor" | "validator" | ...
            Map<String, Object> payload
    ) {}

    public record PublishResponse(UUID eventId, UUID sessionId) {}

    @PostMapping
    public PublishResponse publish(@Valid @RequestBody PublishRequest req) {
        UUID sessionId = req.sessionId() == null ? UuidV7.next() : req.sessionId();
        UUID eventId = UuidV7.next();
        Instant now = clock.instant();
        Map<String, Object> payload = req.payload() == null ? Map.of() : req.payload();

        String normalizedActor;
        try {
            normalizedActor = actorGate.validateActor(req.actor());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (normalizedActor == null) {
            publishIdentityViolation(sessionId, now, req.actor(), req.kind());
            audit.append("ingest_rejected", "sentinel", sessionId,
                    Map.of("reason", "actor_not_allowlisted", "actor", req.actor(), "kind", req.kind()));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agent identity not registered for event ingest: '" + req.actor()
                            + "'. Configure sentinelmesh.event-ingest.actor-allowlist "
                            + "(use * to disable in dev-only environments).");
        }

        AgentEvent event = switch (req.kind()) {
            case "plan" -> new AgentEvent.Plan(eventId, sessionId, now, normalizedActor,
                    String.valueOf(payload.getOrDefault("goal", "")), payload);
            case "tool_call" -> new AgentEvent.ToolCall(eventId, sessionId, now, normalizedActor,
                    String.valueOf(payload.getOrDefault("tool", "unknown")),
                    asMap(payload.get("args")));
            case "tool_result" -> new AgentEvent.ToolResult(eventId, sessionId, now, normalizedActor,
                    String.valueOf(payload.getOrDefault("tool", "unknown")),
                    asMap(payload.get("result")),
                    String.valueOf(payload.getOrDefault("sample", "")));
            case "state_transition" -> new AgentEvent.StateTransition(eventId, sessionId, now, normalizedActor,
                    String.valueOf(payload.getOrDefault("from", "")),
                    String.valueOf(payload.getOrDefault("to", "")));
            default -> throw new IllegalArgumentException("Unknown event kind: " + req.kind());
        };

        publisher.publish(event);
        audit.append(req.kind(), normalizedActor, sessionId, payload);
        return new PublishResponse(eventId, sessionId);
    }

    private void publishIdentityViolation(UUID sessionId, Instant now, String actor, String kind) {
        Map<String, Object> evidence = Map.of(
                "rejected_actor", actor,
                "kind", kind,
                "message", "External ingest claimed an actor that is not on the mesh allow-list.");
        UUID threatId = UuidV7.next();
        UUID actionId = UuidV7.next();
        try {
            threats.save(new Threat(threatId, sessionId, actionId, "AGENT_IDENTITY_VIOLATION",
                    Threat.Severity.HIGH, 1.0, evidence, now));
        } catch (Exception ex) {
            // Best-effort: timeline must still show the WS threat if persistence fails.
        }
        publisher.publish(new AgentEvent.ThreatDetected(
                UuidV7.next(),
                sessionId,
                now,
                "sentinel",
                "AGENT_IDENTITY_VIOLATION",
                "HIGH",
                1.0,
                evidence));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
