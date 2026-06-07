package com.sentinelmesh.adversary.scenarios;

import com.sentinelmesh.adversary.AgentSimulator;

import java.util.UUID;

/**
 * One scripted adversarial scenario. Each implementation is a self-contained
 * story (DOM payload, tool calls, expected detections) that the AgentSimulator
 * plays back deterministically.
 */
public interface AttackScenario {
    String id();
    String displayName();
    String description();
    void play(UUID sessionId, AgentSimulator sim);
}
