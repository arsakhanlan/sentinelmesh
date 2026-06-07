package com.sentinelmesh.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Either {@code action} (outbound tool call) or {@code content} (inbound tool
 * result / fetched web page) must be set.
 */
public record InspectRequest(
        UUID sessionId,
        @NotNull UUID actionId,
        Direction direction,
        @NotBlank String tool,
        Map<String, Object> args,
        String content,
        Map<String, Object> meta,
        /** When set with {@code currentActor}, enables confused-deputy / capability-escalation detection. */
        String originActor,
        String currentActor
) {
    public enum Direction { OUTBOUND, INBOUND }
}
