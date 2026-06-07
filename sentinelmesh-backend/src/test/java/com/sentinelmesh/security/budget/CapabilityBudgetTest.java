package com.sentinelmesh.security.budget;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CapabilityBudget} — the parser that turns a session's
 * capability_token map into typed limits the L6 scanner enforces.
 *
 * <p>The fail-closed property is the headline one: a malformed token must not
 * silently mean "unlimited" — instead we fall back to {@link CapabilityBudget#defaults()},
 * which is a tight, opinionated set of limits.
 */
class CapabilityBudgetTest {

    @Test
    void defaults_have_sensible_tool_caps_and_spend_cap() {
        CapabilityBudget b = CapabilityBudget.defaults();
        assertThat(b.capFor("email.send")).isEqualTo(1);
        assertThat(b.capFor("payments.charge")).isEqualTo(1);
        assertThat(b.capFor("http.get")).isEqualTo(20);
        assertThat(b.capFor("browser.goto")).isEqualTo(12);
        assertThat(b.spendCapInr()).isEqualTo(7_000);
    }

    @Test
    void from_token_overrides_specified_caps_only() {
        Map<String, Object> token = Map.of(
                "tool_caps", Map.of("email.send", 5, "http.get", 100),
                "spend_cap_inr", 50_000
        );
        CapabilityBudget b = CapabilityBudget.fromToken(token);
        assertThat(b.capFor("email.send")).isEqualTo(5);
        assertThat(b.capFor("http.get")).isEqualTo(100);
        // Unspecified caps inherit defaults — important: not "unlimited"
        assertThat(b.capFor("payments.charge")).isEqualTo(1);
        assertThat(b.spendCapInr()).isEqualTo(50_000);
    }

    @Test
    void unknown_tool_returns_max_int_only_when_no_default_exists() {
        CapabilityBudget b = CapabilityBudget.defaults();
        // Tools we've never heard of can't be governed without a config —
        // they return MAX_VALUE, but their absence will be flagged in the SOC
        // budget snapshot as "unbounded" (see BudgetTracker.snapshot).
        assertThat(b.capFor("evil.tool")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void null_or_empty_token_falls_back_to_defaults_not_unlimited() {
        // The whole reason this code exists: a missing or empty capability
        // token must NEVER let the agent run unconstrained.
        assertThat(CapabilityBudget.fromToken(null).spendCapInr())
                .isEqualTo(CapabilityBudget.defaults().spendCapInr());
        assertThat(CapabilityBudget.fromToken(Map.of()).spendCapInr())
                .isEqualTo(CapabilityBudget.defaults().spendCapInr());
    }

    @Test
    void numeric_string_caps_are_coerced() {
        // Tokens often arrive as JSON-from-config where numbers may be strings.
        Map<String, Object> token = Map.of(
                "tool_caps", Map.of("email.send", "3"),
                "spend_cap_inr", "12345"
        );
        CapabilityBudget b = CapabilityBudget.fromToken(token);
        assertThat(b.capFor("email.send")).isEqualTo(3);
        assertThat(b.spendCapInr()).isEqualTo(12_345);
    }

    @Test
    void negative_caps_are_ignored_and_defaults_apply() {
        // A negative cap would mean "any call is over budget" — surprising
        // behaviour. Treat as misconfiguration: fall back to defaults rather
        // than block everything by accident.
        Map<String, Object> token = Map.of(
                "tool_caps", Map.of("email.send", -1),
                "spend_cap_inr", -10
        );
        CapabilityBudget b = CapabilityBudget.fromToken(token);
        assertThat(b.capFor("email.send")).isEqualTo(1);
        assertThat(b.spendCapInr()).isEqualTo(7_000);
    }
}
