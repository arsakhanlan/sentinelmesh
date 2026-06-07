package com.sentinelmesh.security.budget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed view of a session's {@code capability_token}.
 *
 * <p>The capability token is set when the session is created and declares what
 * the agent is allowed to do over its lifetime: how many times each tool may
 * be invoked, and a single overall spend cap in INR. SentinelMesh enforces
 * these as a separate layer ({@link L6CapabilityBudgetScanner}) so an agent
 * that gets hijacked, drifts, or runs in a loop cannot exceed its grant —
 * even if every individual action looks innocuous.
 *
 * <p>The token format we read is the same one used by SessionService when it
 * creates the session, e.g.
 * <pre>{@code
 *   {
 *     "can_browse": "*",
 *     "spend_cap_inr": 7000,
 *     "tool_caps": {
 *       "email.send": 1,
 *       "payments.charge": 1,
 *       "http.get": 20,
 *       "browser.goto": 12
 *     }
 *   }
 * }</pre>
 *
 * <p>Missing fields fall back to {@link #defaults()} — never to "unlimited" —
 * so a malformed token fails closed.
 */
public record CapabilityBudget(Map<String, Integer> toolCaps, int spendCapInr) {

    /** Sensible defaults applied when a session's token doesn't override them. */
    public static CapabilityBudget defaults() {
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put("email.send", 1);
        caps.put("payments.charge", 1);
        caps.put("http.get", 20);
        caps.put("browser.goto", 12);
        caps.put("notes.append", 20);
        return new CapabilityBudget(caps, 7_000);
    }

    /**
     * Build from a session's capability token map. Unknown keys are tolerated;
     * unknown tool caps come from {@link #defaults()}; numeric coercion is
     * defensive so a string "1" or a Number both work.
     */
    @SuppressWarnings("unchecked")
    public static CapabilityBudget fromToken(Map<String, Object> token) {
        CapabilityBudget def = defaults();
        if (token == null || token.isEmpty()) return def;

        Map<String, Integer> caps = new LinkedHashMap<>(def.toolCaps);
        Object rawCaps = token.get("tool_caps");
        if (rawCaps instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Integer v = toInt(e.getValue());
                if (v != null && v >= 0) caps.put(String.valueOf(e.getKey()), v);
            }
        }
        int spend = def.spendCapInr;
        Integer rawSpend = toInt(token.get("spend_cap_inr"));
        if (rawSpend != null && rawSpend >= 0) spend = rawSpend;
        return new CapabilityBudget(caps, spend);
    }

    public int capFor(String tool) {
        if (tool == null) return Integer.MAX_VALUE;
        Integer c = toolCaps.get(tool);
        return c == null ? Integer.MAX_VALUE : c;
    }

    /** Parse loose JSON number fields from capability tokens and tenant caps. */
    public static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
