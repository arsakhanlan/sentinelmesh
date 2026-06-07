package com.sentinelmesh.domain.model;

import java.util.Map;
import java.util.UUID;

/** Input to the Sentinel pipeline. Either an outbound action or inbound content. */
public sealed interface InspectionInput {

    String tool();

    /** Session this input belongs to. May be {@code null} for anonymous/one-off inspections. */
    UUID sessionId();

    /**
     * Outbound tool invocation. When {@code originActor} and {@code currentActor}
     * are both non-null, {@link com.sentinelmesh.security.scanners.CapabilityEscalationScanner}
     * may block confused-deputy patterns (planner delegating a tool the planner
     * cannot use to a more-privileged executor).
     */
    record OutboundAction(
            UUID sessionId,
            String tool,
            Map<String, Object> args,
            String originActor,
            String currentActor
    ) implements InspectionInput {
        public OutboundAction {
            if (args == null) {
                args = Map.of();
            }
        }

        /** Back-compat: single-actor / legacy clients — escalation scanner is a no-op. */
        public static OutboundAction withoutProvenance(UUID sessionId, String tool, Map<String, Object> args) {
            return new OutboundAction(sessionId, tool, args != null ? args : Map.of(), null, null);
        }
    }

    record InboundContent(UUID sessionId, String tool, String content, Map<String, Object> meta) implements InspectionInput {
        public InboundContent {
            if (content == null) content = "";
        }
    }
}
