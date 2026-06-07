package com.sentinelmesh.policy;

import com.sentinelmesh.domain.model.Decision;

/**
 * One compiled policy rule. Evaluated in priority order; first match wins.
 *
 * <p>{@code whenExpr} is intentionally a tiny DSL — a handful of variables
 * (risk, blast, tool, has_secret, has_pii) and the operators {@code >, <, >=, <=, ==, &&, ||, !}.
 * No reflection, no library — see {@link RuleExpressionEvaluator}.
 */
public record Rule(
        String name,
        int priority,
        String whenExpr,
        Decision then,
        String reason
) implements Comparable<Rule> {
    @Override public int compareTo(Rule o) { return Integer.compare(this.priority, o.priority); }
}
