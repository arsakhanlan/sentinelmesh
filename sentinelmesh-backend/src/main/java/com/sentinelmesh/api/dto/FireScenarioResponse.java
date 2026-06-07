package com.sentinelmesh.api.dto;

import java.util.UUID;

public record FireScenarioResponse(UUID sessionId, String scenarioId, String message) {}
