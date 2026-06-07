package com.sentinelmesh.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record FireScenarioRequest(
        @NotBlank String scenarioId,
        UUID sessionId    // optional; creates a new session if null
) {}
