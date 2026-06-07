package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.capability.AgentCapabilityRegistry;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Confused deputy / capability escalation: {@code currentActor} attempts a tool
 * the {@code originActor} is not allowed to invoke, while the current actor is.
 *
 * <p>Requires provenance on {@link InspectionInput.OutboundAction}; when
 * {@code originActor} / {@code currentActor} are unset, this stage is a no-op
 * so existing single-actor clients keep working.
 */
@Component
@Order(55) // after L5 (50), before DLP (60) — fail fast on delegation abuse.
public class CapabilityEscalationScanner implements ScannerStage {

    private final AgentCapabilityRegistry registry;

    public CapabilityEscalationScanner(AgentCapabilityRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "CAP";
    }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        return input instanceof InspectionInput.OutboundAction;
    }

    @Override
    public Finding scan(InspectionInput input) {
        if (!(input instanceof InspectionInput.OutboundAction a)) {
            return Finding.clean("not outbound");
        }
        String origin = a.originActor();
        String current = a.currentActor();
        if (origin == null || current == null || origin.isBlank() || current.isBlank()) {
            return Finding.clean("no actor provenance");
        }
        String o = origin.trim().toLowerCase(Locale.ROOT);
        String c = current.trim().toLowerCase(Locale.ROOT);
        if (o.equals(c)) {
            return Finding.clean("same actor");
        }
        String tool = a.tool() == null ? "" : a.tool().trim();
        if (tool.isEmpty()) {
            return escalation(1.0, "blank_tool", o, c, "", "empty tool name on delegated outbound action");
        }
        if (!registry.isKnownCapability(tool)) {
            return escalation(1.0, "unknown_capability", o, c, tool,
                    "delegated action uses unknown capability; failing closed");
        }
        Set<String> originCaps = registry.capabilitiesFor(o);
        Set<String> currentCaps = registry.capabilitiesFor(c);
        if (currentCaps.contains(tool) && !originCaps.contains(tool)) {
            return escalation(0.99, "capability_escalation", o, c, tool,
                    o + " attempted to indirectly invoke " + tool + " via " + c
                            + " despite lacking " + tool + " capability");
        }
        return Finding.clean("origin authorized for capability or deputy lacks tool");
    }

    private static Finding escalation(double score, String code, String origin, String current,
                                      String capability, String reason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("code", code);
        ev.put("origin_actor", origin);
        ev.put("current_actor", current);
        ev.put("requested_capability", capability);
        ev.put("action_name", capability);
        ev.put("reason", reason);
        return new Finding(score, reason, ev);
    }
}
