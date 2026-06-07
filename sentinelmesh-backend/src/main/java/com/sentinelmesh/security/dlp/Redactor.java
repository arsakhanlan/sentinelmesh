package com.sentinelmesh.security.dlp;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a sanitized copy of an outbound payload with secrets and PII redacted.
 *
 * <p>This is what gives the {@code REWRITE} decision teeth: the policy engine
 * decides <em>to</em> rewrite; this class performs the actual redaction so the
 * agent (and the audit/event stream) never sees the raw secret. The original
 * value is never mutated — a deep, redacted copy is returned.
 */
@Component
public class Redactor {

    private final SecretPatternRegistry secrets;
    private final PiiPatternRegistry pii;

    public Redactor(SecretPatternRegistry secrets, PiiPatternRegistry pii) {
        this.secrets = secrets;
        this.pii = pii;
    }

    /** Redact secrets first (more specific), then PII, from a single string. */
    public String redactText(String text) {
        if (text == null || text.isEmpty()) return text;
        return pii.redactAll(secrets.redactAll(text));
    }

    /** Deep-copy {@code args}, redacting every string value (nested maps/lists included). */
    public Map<String, Object> redactArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return args;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            copy.put(e.getKey(), redactValue(e.getValue()));
        }
        return copy;
    }

    private Object redactValue(Object value) {
        if (value instanceof String s) {
            return redactText(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), redactValue(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(redactValue(o));
            return out;
        }
        return value;
    }
}
