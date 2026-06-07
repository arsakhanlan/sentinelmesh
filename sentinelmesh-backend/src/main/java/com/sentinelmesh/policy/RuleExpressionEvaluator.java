package com.sentinelmesh.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny boolean expression evaluator for policy rules.
 *
 * <p>Grammar (no parens beyond the outermost; flat boolean groups):
 * <pre>
 *   expr  := and ('||' and)*
 *   and   := atom ('&&' atom)*
 *   atom  := '!'? (compare | bool_var)
 *   compare := var op number
 *   op    := '>' | '<' | '>=' | '<=' | '==' | '!='
 * </pre>
 *
 * <p>Variables available: {@code risk}, {@code blast}, {@code tool},
 * {@code has_secret}, {@code has_pii}. Strings supported only for {@code tool == 'name'}.
 *
 * <p>This is deliberately tiny — for v2 we'd plug in Spring SpEL or MVEL.
 * For the hackathon, 80 lines of code beats a 5 MB dependency.
 */
final class RuleExpressionEvaluator {

    private static final Pattern COMPARE = Pattern.compile(
            "\\s*([a-z_]+)\\s*(>=|<=|==|!=|>|<)\\s*('[^']*'|-?\\d+(?:\\.\\d+)?)\\s*");

    boolean eval(String expr, Map<String, Object> ctx) {
        if (expr == null || expr.isBlank()) return true;
        return evalOr(expr.trim(), ctx);
    }

    private boolean evalOr(String s, Map<String, Object> ctx) {
        String[] parts = splitTop(s, "||");
        for (String p : parts) if (evalAnd(p.trim(), ctx)) return true;
        return false;
    }

    private boolean evalAnd(String s, Map<String, Object> ctx) {
        String[] parts = splitTop(s, "&&");
        for (String p : parts) if (!evalAtom(p.trim(), ctx)) return false;
        return true;
    }

    private boolean evalAtom(String s, Map<String, Object> ctx) {
        boolean negate = false;
        if (s.startsWith("!")) { negate = true; s = s.substring(1).trim(); }
        Matcher m = COMPARE.matcher(s);
        boolean result;
        if (m.matches()) {
            String var = m.group(1);
            String op  = m.group(2);
            String rhs = m.group(3);
            Object lhs = ctx.get(var);
            if (rhs.startsWith("'")) {
                String rhsStr = rhs.substring(1, rhs.length() - 1);
                String lhsStr = String.valueOf(lhs);
                result = switch (op) {
                    case "==" -> lhsStr.equals(rhsStr);
                    case "!=" -> !lhsStr.equals(rhsStr);
                    default -> throw new IllegalArgumentException("op " + op + " not supported for strings");
                };
            } else {
                double rhsNum = Double.parseDouble(rhs);
                double lhsNum = toDouble(lhs);
                result = switch (op) {
                    case ">"  -> lhsNum >  rhsNum;
                    case "<"  -> lhsNum <  rhsNum;
                    case ">=" -> lhsNum >= rhsNum;
                    case "<=" -> lhsNum <= rhsNum;
                    case "==" -> lhsNum == rhsNum;
                    case "!=" -> lhsNum != rhsNum;
                    default -> false;
                };
            }
        } else {
            // bare boolean variable
            result = toBool(ctx.get(s));
        }
        return negate != result;
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1.0 : 0.0;
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private static boolean toBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0.0;
        return Boolean.parseBoolean(o.toString());
    }

    /** Split on a top-level delimiter (ignoring nested parens / single quotes). */
    private static String[] splitTop(String s, String delim) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (depth == 0 && i + delim.length() <= s.length()
                        && s.regionMatches(i, delim, 0, delim.length())) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    i += delim.length() - 1;
                    continue;
                }
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static Map<String, Object> emptyCtx() { return new HashMap<>(); }
}
