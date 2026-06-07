package com.sentinelmesh.policy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the tiny boolean-expression evaluator that powers YAML rule bundles.
 *
 * <p>This DSL is small but it's load-bearing — every BLOCK / WARN decision in
 * the policy bundle is parsed by it. A bug here would silently let bad
 * actions through. We therefore exercise:
 *
 * <ul>
 *   <li>Each numeric comparison operator.</li>
 *   <li>String equality on the {@code tool} variable.</li>
 *   <li>{@code &&} and {@code ||} precedence (AND binds tighter).</li>
 *   <li>The {@code !} negation operator.</li>
 *   <li>Bare boolean variables (used for {@code has_secret}, {@code over_budget}).</li>
 *   <li>Missing variables: must default to falsy without throwing.</li>
 * </ul>
 */
class RuleExpressionEvaluatorTest {

    private final RuleExpressionEvaluator eval = new RuleExpressionEvaluator();

    @Test
    void numeric_compare_each_operator() {
        Map<String, Object> ctx = Map.of("risk", 0.7, "blast", 0.5);
        assertThat(eval.eval("risk >= 0.7", ctx)).isTrue();
        assertThat(eval.eval("risk >  0.7", ctx)).isFalse();
        assertThat(eval.eval("risk <  1.0", ctx)).isTrue();
        assertThat(eval.eval("risk == 0.7", ctx)).isTrue();
        assertThat(eval.eval("blast != 0.5", ctx)).isFalse();
    }

    @Test
    void string_equality_on_tool_name() {
        Map<String, Object> ctx = Map.of("tool", "email.send", "risk", 0.0);
        assertThat(eval.eval("tool == 'email.send'", ctx)).isTrue();
        assertThat(eval.eval("tool == 'http.get'", ctx)).isFalse();
        assertThat(eval.eval("tool != 'http.get'", ctx)).isTrue();
    }

    @Test
    void and_binds_tighter_than_or() {
        // a && b || c  parses as  (a && b) || c
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("risk", 0.5);
        ctx.put("blast", 0.9);
        ctx.put("has_secret", true);
        // risk >= 0.9 (false) && blast >= 0.8 (true)  → false
        // OR has_secret (true)                        → true
        assertThat(eval.eval("risk >= 0.9 && blast >= 0.8 || has_secret", ctx)).isTrue();
        // risk >= 0.9 (false) && (blast >= 0.8 || has_secret) — but our grammar
        // is flat; without parens this MUST evaluate the way described above.
        // Same expression with has_secret=false should be false.
        ctx.put("has_secret", false);
        assertThat(eval.eval("risk >= 0.9 && blast >= 0.8 || has_secret", ctx)).isFalse();
    }

    @Test
    void negation_operator_flips_boolean_atoms() {
        // Grammar is flat — `!` only attaches to a single atom (a compare or
        // a bare bool var), not to a parenthesised group.
        assertThat(eval.eval("!has_pii", Map.of("has_pii", true))).isFalse();
        assertThat(eval.eval("!has_pii", Map.of("has_pii", false))).isTrue();
        // Numeric truthiness: 0.0 → false, any other → true.
        assertThat(eval.eval("!risk", Map.of("risk", 0.1))).isFalse();
        assertThat(eval.eval("!risk", Map.of("risk", 0.0))).isTrue();
    }

    @Test
    void bare_boolean_variable_works_for_over_budget() {
        // The L6 rule in the default bundle relies on this — it matches
        // `over_budget` as a naked boolean. If this regresses, the budget
        // BLOCK rule stops working.
        assertThat(eval.eval("over_budget", Map.of("over_budget", true))).isTrue();
        assertThat(eval.eval("over_budget", Map.of("over_budget", false))).isFalse();
    }

    @Test
    void missing_variables_default_to_falsy_without_throwing() {
        // A YAML rule referencing a context variable that the engine never
        // populated should evaluate to false, not throw — so a partial
        // pipeline doesn't crash the engine.
        Map<String, Object> ctx = new HashMap<>();
        assertThat(eval.eval("nope >= 1", ctx)).isFalse();
        assertThat(eval.eval("nope", ctx)).isFalse();
    }

    @Test
    void blank_expression_matches_everything() {
        // Bundle authors sometimes leave a catch-all rule with `when: ""` —
        // it must always match (the last-resort decision).
        assertThat(eval.eval("", Map.of())).isTrue();
        assertThat(eval.eval(null, Map.of())).isTrue();
    }
}
