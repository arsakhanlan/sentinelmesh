package com.sentinelmesh.security.capability;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Demo-sized per-actor tool allow-list for confused-deputy / capability-escalation
 * detection. Aligns with LangGraph roles ({@code planner}, {@code executor}, …).
 *
 * <p>The {@code tool} string on an outbound inspect (e.g. {@code email.send}) is
 * treated as the required capability name — same convention as L6 budget keys.
 */
@Component
public final class AgentCapabilityRegistry {

    // Capability sets are scoped by *role*, not by tool list parity.
    //
    // - planner / executor / agent are the legitimate decision + execution
    //   roles in the LangGraph topology. They are explicitly allowed to plan
    //   and run side-effecting tools (email.send, payments.charge,
    //   bookings.create). Treating planner→executor delegation of these as
    //   "escalation" was a false positive that gated every happy-path
    //   booking behind a human approval — the CAP scanner is for *confused
    //   deputies*, not for normal pipelined work.
    //
    // - memory / researcher / validator are intentionally read-only or
    //   evaluation-only. If one of them tries to delegate email.send or
    //   payments.charge through executor, that IS a confused deputy and
    //   the CAP scanner should fire. The adversary scenario uses
    //   memory→executor email.send to prove this.
    // notes.append is the agent's in-process scratchpad — it never leaves the
    // trust boundary, has no recipient, and is the standard way the planner
    // summarises a result back to the user. It must be a known capability for
    // every legitimate role, otherwise the CAP scanner fails closed on a
    // perfectly innocent step ("show me Goa hotels under 6k") and the demo
    // looks like a false-positive factory.
    //
    // memory / validator / researcher remain forbidden from notes.append:
    // a confused-deputy "memory writes a note that the planner later cites"
    // pattern stays caught.
    private static final Map<String, Set<String>> DEFAULT = Map.of(
            "planner",   Set.of("browser.goto", "http.get", "bookings.create",
                                "email.send", "payments.charge", "notes.append"),
            "executor",  Set.of("browser.goto", "http.get", "bookings.create",
                                "email.send", "payments.charge", "notes.append"),
            "agent",     Set.of("browser.goto", "http.get", "bookings.create",
                                "email.send", "payments.charge", "notes.append"),
            "validator", Set.of(),
            "memory",    Set.of("http.get"),
            "researcher", Set.of("browser.goto", "http.get"));

    /** Every tool string that appears in any actor's capability set (for fail-closed unknowns). */
    private static final Set<String> KNOWN_CAPABILITIES = DEFAULT.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    public Set<String> capabilitiesFor(String actor) {
        if (actor == null || actor.isBlank()) {
            return Set.of();
        }
        String key = actor.trim().toLowerCase(Locale.ROOT);
        return DEFAULT.getOrDefault(key, Set.of());
    }

    public boolean isKnownCapability(String tool) {
        return tool != null && KNOWN_CAPABILITIES.contains(tool.trim());
    }
}
