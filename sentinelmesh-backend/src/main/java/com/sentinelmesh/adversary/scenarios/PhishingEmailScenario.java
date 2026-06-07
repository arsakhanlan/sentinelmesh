package com.sentinelmesh.adversary.scenarios;

import com.sentinelmesh.adversary.AgentSimulator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class PhishingEmailScenario implements AttackScenario {

    @Override public String id() { return "phishing_email"; }
    @Override public String displayName() { return "Outbound Phishing / Secret Leak"; }
    @Override public String description() {
        return "Agent drafts a confirmation email that includes an embedded API key — DLP rewrites it.";
    }

    @Override
    public void play(UUID sessionId, AgentSimulator sim) {
        sim.plan(sessionId, "Send booking confirmation to traveller",
                Map.of("steps", java.util.List.of("draft", "send")));
        sim.sleep(150);

        // Outbound email that contains an OpenAI-style API key and a PAN number.
        sim.toolCall(sessionId, "email.send", Map.of(
                "to", "user@example.com",
                "subject", "Your booking confirmation",
                "body", "Booking confirmed. Internal token: sk-live-AKIA1234567890ABCDEFG12345 \n" +
                        "Reference for tax: PAN ABCDE1234F"
        ));
    }
}
