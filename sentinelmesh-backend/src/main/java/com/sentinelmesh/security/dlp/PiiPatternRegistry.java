package com.sentinelmesh.security.dlp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Common PII patterns (Indian + global, hackathon scope). */
@Component
public class PiiPatternRegistry {

    public record Match(String type, String redacted) {}

    private record Rule(String type, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("email",  Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")),
            new Rule("pan",    Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")),                       // India PAN
            new Rule("aadhaar",Pattern.compile("\\b[2-9]\\d{3}\\s?\\d{4}\\s?\\d{4}\\b")),              // India Aadhaar
            new Rule("cc",     Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b")),                         // credit card-ish
            new Rule("phone",  Pattern.compile("\\b\\+?\\d{1,3}[ -]?\\d{3,4}[ -]?\\d{3,4}[ -]?\\d{3,4}\\b"))
    );

    public List<Match> find(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Match> matches = new java.util.ArrayList<>();
        for (Rule r : RULES) {
            var m = r.pattern.matcher(text);
            while (m.find()) {
                matches.add(new Match(r.type, "[" + r.type + "_redacted]"));
            }
        }
        return matches;
    }

    /** Replace every matched PII span in {@code text} with a typed redaction marker. */
    public String redactAll(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) {
            java.util.regex.Matcher m = r.pattern.matcher(out);
            StringBuilder sb = new StringBuilder();
            String replacement = "[" + r.type + "_redacted]";
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
