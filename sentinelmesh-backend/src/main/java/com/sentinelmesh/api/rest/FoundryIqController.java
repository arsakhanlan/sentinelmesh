package com.sentinelmesh.api.rest;

import com.sentinelmesh.domain.port.out.ThreatRepository;
import com.sentinelmesh.policy.PolicyEngine;
import com.sentinelmesh.policy.Rule;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foundry IQ knowledge-base export endpoints.
 *
 * <p>Microsoft Foundry IQ is the agent-side knowledge plane: it ingests
 * structured documents (markdown, JSONL, PDF, etc.) and lets any
 * Foundry-hosted or external agent query them with the same Responses API
 * call that handles file_search and web_search. Exposing SentinelMesh's
 * <em>policy bundle</em> and <em>threat board</em> as IQ-ingestible
 * documents lets a Microsoft-native agent fetch SentinelMesh's stance on
 * a question ("What does the policy say about external vendor charges?")
 * with no SentinelMesh-specific client code.
 *
 * <p>Two endpoints, both <strong>read-only</strong> and both producing
 * markdown so they round-trip cleanly through the IQ markdown ingester:
 *
 * <ul>
 *   <li>{@code GET /api/v1/foundry-iq/policies} — the current policy bundle
 *       rendered as a markdown document (one section per rule).</li>
 *   <li>{@code GET /api/v1/foundry-iq/threats} — a rollup of the threat
 *       board (counts by category, severity ladder, top-line stance)
 *       suitable for the agent to cite when discussing past incidents.</li>
 * </ul>
 *
 * <p>Both also expose a {@code ?format=jsonl} query parameter producing the
 * <em>same</em> content in JSON-Lines form, which is the format Foundry IQ
 * prefers for batch ingestion.
 */
@RestController
@RequestMapping("/api/v1/foundry-iq")
@Tag(name = "Foundry IQ", description = "Markdown / JSONL exports of policies + threats for ingestion into Microsoft Foundry IQ knowledge bases.")
public class FoundryIqController {

    private final PolicyEngine policy;
    private final ThreatRepository threats;
    private final ResourceLoader resourceLoader;

    public FoundryIqController(PolicyEngine policy, ThreatRepository threats,
                               ResourceLoader resourceLoader) {
        this.policy = policy;
        this.threats = threats;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Render the current policy bundle as a markdown knowledge document.
     * Foundry IQ ingests this with its built-in markdown loader; chunks land
     * at "rule" granularity because each rule becomes a {@code ##} heading.
     */
    @GetMapping(value = "/policies", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> policiesMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SentinelMesh — active policy bundle\n\n")
          .append("> Source of truth for what the policy engine BLOCKs, REWRITEs,\n")
          .append("> requires approval for, or ALLOWs. First match wins by ascending\n")
          .append("> priority. This document is generated from the live bundle and\n")
          .append("> is safe to ingest into Microsoft Foundry IQ.\n\n");

        sb.append("## Bundle source (raw YAML)\n\n");
        sb.append("```yaml\n").append(loadActiveBundleYaml()).append("\n```\n\n");

        sb.append("## Compiled rules\n\n");
        for (Rule r : policy.rules()) {
            sb.append("### `").append(r.name()).append("`  (priority ")
              .append(r.priority()).append(")\n\n");
            sb.append("- **Decision:** `").append(r.then()).append("`\n");
            sb.append("- **When:** `").append(r.whenExpr()).append("`\n");
            if (r.reason() != null && !r.reason().isBlank()) {
                sb.append("- **Reason cited to operators:** ").append(r.reason()).append("\n");
            }
            sb.append("\n");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_MARKDOWN).body(sb.toString());
    }

    /**
     * Same data, JSONL shape — one row per rule. Foundry IQ's batch ingest
     * accepts JSONL with {@code {"title":..., "content":..., "metadata":...}}
     * envelopes, which is what we emit.
     */
    @GetMapping(value = "/policies.jsonl", produces = "application/jsonl")
    public ResponseEntity<String> policiesJsonl() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : policy.rules()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("title", "SentinelMesh policy rule: " + r.name());
            doc.put("content",
                    "Rule `" + r.name() + "` (priority " + r.priority() + "): "
                            + "when `" + r.whenExpr() + "` then **" + r.then() + "**. "
                            + (r.reason() == null ? "" : r.reason()));
            doc.put("metadata", Map.of(
                    "source", "sentinelmesh.policy",
                    "rule_name", r.name(),
                    "priority", r.priority(),
                    "decision", r.then()));
            sb.append(toJson(doc)).append("\n");
        }
        return ResponseEntity.ok().header("Content-Type", "application/jsonl").body(sb.toString());
    }

    /**
     * Threat board rollup as markdown. Counts come from
     * {@link ThreatRepository#countByCategory} so the document stays
     * tenant-scoped without leaking individual session payloads.
     */
    @GetMapping(value = "/threats", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> threatsMarkdown() {
        long total = threats.countAll();
        StringBuilder sb = new StringBuilder();
        sb.append("# SentinelMesh — threat board\n\n")
          .append("> Generated at ").append(Instant.now())
          .append(". Counts are aggregate across all sessions.\n\n");
        sb.append("- **Total recorded threats:** ").append(total).append("\n\n");

        sb.append("## By category\n\n");
        sb.append("| Category | Count |\n|---|---|\n");
        for (String cat : KNOWN_CATEGORIES) {
            long c = threats.countByCategory(cat);
            sb.append("| ").append(cat).append(" | ").append(c).append(" |\n");
        }

        sb.append("\n## What this means for your agent\n\n");
        sb.append("If an action you're about to take resembles one of the categories\n");
        sb.append("above and the count is non-zero, expect SentinelMesh to flag it\n");
        sb.append("at inspect time. Pre-emptively shape your tool arguments to\n");
        sb.append("avoid the BLOCK / REQUIRE_APPROVAL bands when possible —\n");
        sb.append("e.g. omit secrets from `email.send` body, scope payments to\n");
        sb.append("known vendors, never paste hidden DOM into a tool argument.\n");

        return ResponseEntity.ok().contentType(MediaType.TEXT_MARKDOWN).body(sb.toString());
    }

    /** JSONL twin of the threat board for Foundry IQ batch ingest. */
    @GetMapping(value = "/threats.jsonl", produces = "application/jsonl")
    public ResponseEntity<String> threatsJsonl() {
        StringBuilder sb = new StringBuilder();
        long total = threats.countAll();
        for (String cat : KNOWN_CATEGORIES) {
            long c = threats.countByCategory(cat);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("title", "SentinelMesh threat category: " + cat);
            doc.put("content",
                    "Category `" + cat + "` has been triggered " + c + " time(s) "
                            + "across all sessions (total threats: " + total + "). "
                            + "Agents should treat actions resembling this category as "
                            + "policy-engine-flagged.");
            doc.put("metadata", Map.of(
                    "source", "sentinelmesh.threats",
                    "category", cat,
                    "count", c,
                    "total_threats", total));
            sb.append(toJson(doc)).append("\n");
        }
        return ResponseEntity.ok().header("Content-Type", "application/jsonl").body(sb.toString());
    }

    // ---------- helpers ----------

    /**
     * Threat categories surfaced to Foundry IQ. Kept in code (not pulled
     * from the database) so the export is stable across empty databases —
     * an agent ingesting this on day one still gets the canonical category
     * set.
     */
    private static final List<String> KNOWN_CATEGORIES = List.of(
            "PROMPT_INJECTION", "DATA_LOSS", "PII_LEAK",
            "SECRET_EXFILTRATION", "CAPABILITY_ESCALATION",
            "BUDGET_EXHAUSTION", "KNOWN_ATTACK", "BEHAVIORAL_ANOMALY",
            "CONTENT_SAFETY_VIOLATION", "POLICY_VIOLATION"
    );

    private String loadActiveBundleYaml() {
        Resource r = resourceLoader.getResource("classpath:policies/default-bundle.yml");
        try (InputStream is = r.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "# Could not read bundle: " + e.getMessage();
        }
    }

    /**
     * Tiny JSON serializer — kept inline rather than pulling Jackson here
     * because the doc shape is fixed and we need stable key ordering for
     * golden-file diffing.
     */
    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(jsonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                sb.append(jsonValue(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
