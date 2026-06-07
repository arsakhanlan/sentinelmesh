package com.sentinelmesh.domain.port.in;

import java.util.List;
import java.util.UUID;

public interface FireAdversaryScenarioUseCase {
    /** Returns the session id the scenario plays into. */
    UUID fire(String scenarioId, UUID sessionId);
    List<ScenarioInfo> listScenarios();

    record ScenarioInfo(String id, String displayName, String description) {}
}
