package com.sentinelmesh.adversary;

import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.domain.port.out.EventPublisher;
import com.sentinelmesh.security.SentinelInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The bridge between scripted scenarios and the rest of the system.
 *
 * <p>Each helper method ({@link #plan}, {@link #toolCall}, {@link #toolResult})
 * emits an {@code AgentEvent} and — for tool calls/results — runs the payload
 * through {@link SentinelInspectionService}, which handles the decision,
 * threats, audit, approvals, quarantine and redaction. The scenarios just call
 * these helpers in sequence.
 *
 * <p>This is the v1 replacement for the future LangGraph runtime. When that
 * ships, it will hit the same {@code /sentinel/inspect} endpoint (also backed by
 * {@link SentinelInspectionService}), so behaviour stays identical.
 */
@Component
public class AgentSimulator {

    private static final Logger log = LoggerFactory.getLogger(AgentSimulator.class);

    private final EventPublisher publisher;
    private final SentinelInspectionService inspection;
    private final AuditEventSink audit;
    private final Clock clock;
    private final ExecutorService runner;

    public AgentSimulator(EventPublisher publisher, SentinelInspectionService inspection,
                          AuditEventSink audit, Clock clock) {
        this.publisher = publisher;
        this.inspection = inspection;
        this.audit = audit;
        this.clock = clock;
        // Virtual threads so each running scenario gets its own cheap thread.
        this.runner = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name("adversary-", 0).factory());
    }

    public void runAsync(Runnable script) { runner.submit(script); }

    public void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void plan(UUID sessionId, String goal, Map<String, Object> plan) {
        Instant now = clock.instant();
        AgentEvent.Plan e = new AgentEvent.Plan(UuidV7.next(), sessionId, now, "planner", goal, plan);
        publisher.publish(e);
        audit.append("plan", "planner", sessionId,
                Map.of("goal", goal, "plan", plan));
    }

    /** Run an outbound tool call through Sentinel. Returns the decision. */
    public Decision toolCall(UUID sessionId, String tool, Map<String, Object> args) {
        return toolCall(sessionId, tool, args, null, null);
    }

    /**
     * Same as {@link #toolCall(UUID, String, Map)} but with actor provenance for
     * confused-deputy detection ({@code originActor} vs {@code currentActor}).
     */
    public Decision toolCall(UUID sessionId, String tool, Map<String, Object> args,
                             String originActor, String currentActor) {
        Instant now = clock.instant();
        AgentEvent.ToolCall call = new AgentEvent.ToolCall(
                UuidV7.next(), sessionId, now, currentActor == null ? "executor" : currentActor,
                tool, args);
        publisher.publish(call);

        InspectionInput input = new InspectionInput.OutboundAction(
                sessionId, tool, args == null ? Map.of() : args, originActor, currentActor);
        return inspection.inspect(input, call.eventId()).result().decision();
    }

    /** Run inbound tool content (e.g. a fetched web page body) through Sentinel. */
    public Decision toolResult(UUID sessionId, String tool, String contentSample,
                                Map<String, Object> meta) {
        Instant now = clock.instant();
        AgentEvent.ToolResult res = new AgentEvent.ToolResult(
                UuidV7.next(), sessionId, now, "executor", tool,
                meta == null ? Map.of() : meta, contentSample);
        publisher.publish(res);

        InspectionInput input = new InspectionInput.InboundContent(sessionId, tool, contentSample, meta);
        return inspection.inspect(input, res.eventId()).result().decision();
    }
}
