package com.sentinelmesh.adversary;

import com.sentinelmesh.adversary.scenarios.AttackScenario;
import com.sentinelmesh.common.error.NotFoundException;
import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.port.in.FireAdversaryScenarioUseCase;
import com.sentinelmesh.domain.port.in.StartSessionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdversaryService implements FireAdversaryScenarioUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdversaryService.class);

    private final Map<String, AttackScenario> scenarios;
    private final AgentSimulator simulator;
    private final StartSessionUseCase sessions;

    public AdversaryService(List<AttackScenario> scenarios, AgentSimulator simulator,
                             StartSessionUseCase sessions) {
        this.scenarios = scenarios.stream().collect(
                java.util.stream.Collectors.toMap(AttackScenario::id, s -> s));
        this.simulator = simulator;
        this.sessions = sessions;
        log.info("Registered {} adversary scenarios: {}", scenarios.size(), this.scenarios.keySet());
    }

    @Override
    public UUID fire(String scenarioId, UUID sessionId) {
        AttackScenario s = scenarios.get(scenarioId);
        if (s == null) throw new NotFoundException("Scenario not found: " + scenarioId);

        UUID effectiveSession = sessionId;
        if (effectiveSession == null) {
            Session created = sessions.start("demo-user",
                    "Adversary scenario: " + s.displayName(), "default");
            effectiveSession = created.id();
        }
        UUID toRun = effectiveSession;
        simulator.runAsync(() -> {
            try {
                log.info("Playing scenario {} on session {}", scenarioId, toRun);
                s.play(toRun, simulator);
            } catch (Exception ex) {
                log.error("Scenario {} failed: {}", scenarioId, ex.toString(), ex);
            }
        });
        return effectiveSession;
    }

    @Override
    public List<ScenarioInfo> listScenarios() {
        return scenarios.values().stream()
                .map(s -> new ScenarioInfo(s.id(), s.displayName(), s.description()))
                .sorted(java.util.Comparator.comparing(ScenarioInfo::id))
                .toList();
    }
}
