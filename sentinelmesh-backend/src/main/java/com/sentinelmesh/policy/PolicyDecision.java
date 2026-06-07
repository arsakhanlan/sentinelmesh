package com.sentinelmesh.policy;

import com.sentinelmesh.domain.model.Decision;

public record PolicyDecision(Decision decision, String matchedRule, String reason) {
    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(Decision.ALLOW, "default-allow", reason);
    }
}
