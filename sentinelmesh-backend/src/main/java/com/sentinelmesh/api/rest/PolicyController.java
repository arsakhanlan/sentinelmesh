package com.sentinelmesh.api.rest;

import com.sentinelmesh.policy.PolicyEngine;
import com.sentinelmesh.policy.PolicySimulator;
import com.sentinelmesh.policy.Rule;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "Read-only access + what-if simulation against the policy bundle")
public class PolicyController {

    /** Cap on size of a candidate bundle we'll accept — generous, but bounded. */
    private static final int MAX_BUNDLE_BYTES = 64 * 1024;

    private final PolicyEngine policy;
    private final PolicySimulator simulator;
    private final ResourceLoader resourceLoader;

    public PolicyController(PolicyEngine policy, PolicySimulator simulator,
                            ResourceLoader resourceLoader) {
        this.policy = policy;
        this.simulator = simulator;
        this.resourceLoader = resourceLoader;
    }

    @GetMapping("/current")
    public List<Rule> current() { return policy.rules(); }

    /**
     * Return the active bundle's raw YAML source. The SOC editor preloads
     * this so an engineer is always editing the *current* policy, not
     * starting from a blank slate.
     */
    @GetMapping(value = "/current/source", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> currentSource() {
        Resource r = resourceLoader.getResource("classpath:policies/default-bundle.yml");
        try (InputStream is = r.getInputStream()) {
            String yaml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(yaml);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("# Could not read default bundle: " + e.getMessage());
        }
    }

    /**
     * What-if endpoint. Body shape:
     * <pre>{@code
     *   { "bundle_yaml": "rules: ...", "window_hours": 24 }
     * }</pre>
     *
     * Returns a diff of decisions that would have changed under the candidate
     * bundle against the last {@code window_hours} of audited inspections.
     * 100% read-only — the active bundle is never touched.
     */
    @PostMapping("/simulate")
    public ResponseEntity<?> simulate(@RequestBody Map<String, Object> body) {
        Object yamlObj = body.get("bundle_yaml");
        if (!(yamlObj instanceof String yaml) || yaml.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "message", "bundle_yaml is required and must be a non-empty string"));
        }
        if (yaml.length() > MAX_BUNDLE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bundle_too_large",
                    "message", "bundle_yaml exceeds " + MAX_BUNDLE_BYTES + " bytes"));
        }
        Integer windowHours = null;
        Object w = body.get("window_hours");
        if (w instanceof Number n) windowHours = n.intValue();
        try {
            PolicySimulator.Result result = simulator.simulate(yaml, windowHours);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "compile_failed",
                    "message", e.getMessage()));
        }
    }
}
