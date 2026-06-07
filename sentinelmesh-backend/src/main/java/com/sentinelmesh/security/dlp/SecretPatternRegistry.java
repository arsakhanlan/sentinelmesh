package com.sentinelmesh.security.dlp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Common secret formats. Patterns sourced from OWASP + cloud-provider docs;
 * intentionally conservative (a few false positives is preferable to a missed
 * leak for the demo audience).
 */
@Component
public class SecretPatternRegistry {

    public record Match(String type, String redacted, int start, int end) {}

    private record Rule(String type, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("aws_access_key",     Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("aws_secret_key",     Pattern.compile("(?i)aws.{0,20}?[\"' ][0-9a-zA-Z/+]{40}[\"' ]")),
            new Rule("openai_key",         Pattern.compile("\\bsk-(live|proj|test)-[A-Za-z0-9_-]{20,}\\b")),
            new Rule("openai_key_short",   Pattern.compile("\\bsk-[A-Za-z0-9]{32,}\\b")),
            new Rule("github_token",       Pattern.compile("\\bghp_[A-Za-z0-9]{36,}\\b")),
            new Rule("github_oauth",       Pattern.compile("\\bgho_[A-Za-z0-9]{36,}\\b")),
            new Rule("azure_sas",          Pattern.compile("(?i)sig=[A-Za-z0-9%/+]{20,}&se=")),
            new Rule("slack_token",        Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
            new Rule("bearer_jwt",         Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
            new Rule("generic_secret",     Pattern.compile("(?i)(secret|password|token|api[_-]?key)\\s*[:=]\\s*[\"']?[A-Za-z0-9_/+=-]{16,}[\"']?"))
    );

    public List<Match> find(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Match> matches = new java.util.ArrayList<>();
        for (Rule r : RULES) {
            var m = r.pattern.matcher(text);
            while (m.find()) {
                matches.add(new Match(r.type, redact(m.group()), m.start(), m.end()));
            }
        }
        return matches;
    }

    /** Replace every matched secret in {@code text} with its redacted form. */
    public String redactAll(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) {
            java.util.regex.Matcher m = r.pattern.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(redact(m.group())));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static String redact(String s) {
        if (s.length() <= 6) return "***";
        return s.substring(0, 3) + "***" + s.substring(s.length() - 3);
    }
}
