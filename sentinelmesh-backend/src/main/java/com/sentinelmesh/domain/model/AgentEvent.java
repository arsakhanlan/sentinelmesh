package com.sentinelmesh.domain.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Single source-of-truth envelope for everything that happens in the system.
 * Every layer (agent simulator, security pipeline, audit, websocket) emits and
 * consumes this type. The frontend timeline is a direct projection of this stream.
 *
 * <p>Polymorphic (de)serialization uses an explicit {@code kind} discriminator
 * to keep the wire format stable even if class names evolve.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.Plan.class,             name = "plan"),
        @JsonSubTypes.Type(value = AgentEvent.ToolCall.class,         name = "tool_call"),
        @JsonSubTypes.Type(value = AgentEvent.ToolResult.class,       name = "tool_result"),
        @JsonSubTypes.Type(value = AgentEvent.SentinelDecision.class, name = "sentinel_decision"),
        @JsonSubTypes.Type(value = AgentEvent.ThreatDetected.class,   name = "threat"),
        @JsonSubTypes.Type(value = AgentEvent.ApprovalRequested.class,name = "approval_requested"),
        @JsonSubTypes.Type(value = AgentEvent.ApprovalDecided.class,  name = "approval_decided"),
        @JsonSubTypes.Type(value = AgentEvent.StateTransition.class,  name = "state_transition"),
})
public sealed interface AgentEvent {
    UUID eventId();
    UUID sessionId();
    Instant timestamp();
    String actor();

    record Plan(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            String goal, Map<String, Object> plan
    ) implements AgentEvent {}

    record ToolCall(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            String tool, Map<String, Object> args
    ) implements AgentEvent {}

    record ToolResult(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            String tool, Map<String, Object> result, String contentSample
    ) implements AgentEvent {}

    record SentinelDecision(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            Decision decision, RiskScore risk, String reason,
            Map<String, Object> interceptedAction
    ) implements AgentEvent {}

    record ThreatDetected(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            String category, String severity, double score,
            Map<String, Object> evidence
    ) implements AgentEvent {}

    record ApprovalRequested(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            UUID approvalId, String intent, Map<String, Object> action,
            double blastRadius
    ) implements AgentEvent {}

    record ApprovalDecided(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            UUID approvalId, Decision decision, String approverId
    ) implements AgentEvent {}

    record StateTransition(
            UUID eventId, UUID sessionId, Instant timestamp, String actor,
            String from, String to
    ) implements AgentEvent {}
}
