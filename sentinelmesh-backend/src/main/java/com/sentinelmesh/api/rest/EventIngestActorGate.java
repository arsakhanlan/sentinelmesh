package com.sentinelmesh.api.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards {@link EventIngestController} against arbitrary {@code actor} strings
 * on the external ingest path (the Python agent and other runtimes). This is
 * the demo-sized analogue of "service impersonation" controls — not a full
 * cryptographic identity layer (see {@code docs/Multi-Agent-Security.md}).
 */
@Component
public final class EventIngestActorGate {

    private final EventIngestAllowlist allowlist;

    public EventIngestActorGate(
            @Value("${sentinelmesh.event-ingest.actor-allowlist:planner,executor,agent,memory,validator,researcher}") String raw) {
        this.allowlist = EventIngestAllowlist.parse(raw);
    }

    public boolean isPermissive() {
        return allowlist.permissive();
    }

    /**
     * @return normalized actor string, or {@code null} when the actor is not allow-listed
     * @throws IllegalArgumentException when actor is null/blank
     */
    public String validateActor(String actor) {
        return allowlist.validateActor(actor);
    }

    /** Pure allow-list rules (used by {@link EventIngestActorGate} and unit tests). */
    static final class EventIngestAllowlist {
        private final boolean permissive;
        private final Set<String> allowedActorsLowercase;

        private EventIngestAllowlist(boolean permissive, Set<String> allowedActorsLowercase) {
            this.permissive = permissive;
            this.allowedActorsLowercase = allowedActorsLowercase;
        }

        static EventIngestAllowlist parse(String allowlist) {
            String trimmed = allowlist == null ? "" : allowlist.trim();
            if ("*".equals(trimmed)) {
                return new EventIngestAllowlist(true, Set.of());
            }
            Set<String> allowed = Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            return new EventIngestAllowlist(false, allowed);
        }

        boolean permissive() {
            return permissive;
        }

        String validateActor(String actor) {
            if (actor == null || actor.isBlank()) {
                throw new IllegalArgumentException("actor is required");
            }
            if (permissive) {
                return actor.trim();
            }
            String key = actor.trim().toLowerCase(Locale.ROOT);
            if (!allowedActorsLowercase.contains(key)) {
                return null;
            }
            return actor.trim();
        }
    }
}
