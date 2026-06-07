package com.sentinelmesh.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sentinelmesh.common.error.SentinelMeshException;
import com.sentinelmesh.domain.model.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Reads YAML policy bundle files into compiled {@link Rule}s. */
public final class PolicyCompiler {

    private static final Logger log = LoggerFactory.getLogger(PolicyCompiler.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public List<Rule> compile(InputStream yaml) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = YAML.readValue(yaml, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) doc.getOrDefault("rules", List.of());
            List<Rule> rules = new ArrayList<>(raw.size());
            for (Map<String, Object> r : raw) {
                String name = required(r, "name");
                int priority = ((Number) r.getOrDefault("priority", 100)).intValue();
                String when = required(r, "when");
                String thenStr = required(r, "then").toUpperCase();
                Decision then = Decision.valueOf(thenStr);
                String reason = String.valueOf(r.getOrDefault("reason", name));
                rules.add(new Rule(name, priority, when, then, reason));
            }
            Collections.sort(rules);
            log.info("Compiled {} policy rules", rules.size());
            return rules;
        } catch (IOException e) {
            throw new SentinelMeshException("Failed to parse policy YAML", e);
        }
    }

    private static String required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new SentinelMeshException("Missing field: " + key);
        return v.toString();
    }
}
