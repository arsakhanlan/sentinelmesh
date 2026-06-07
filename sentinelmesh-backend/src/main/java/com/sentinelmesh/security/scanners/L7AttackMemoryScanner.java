package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.memory.AttackMemory;
import com.sentinelmesh.security.pipeline.ScannerStage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * L7 — Attack-memory: a learning layer that matches inputs against the
 * fingerprints of previously-blocked attacks.
 *
 * <p>The pipeline now reads:
 * <pre>
 *   L1 deterministic → L2 Azure CS → L3 Prompt Shields → L4 LLM judge
 *                    → L5 DLP → L6 capability-budget → L7 attack-memory
 * </pre>
 *
 * <p>L1..L4 are about <em>detecting</em> things that look like attacks.
 * L7 is about <em>remembering</em> things we already labelled as attacks
 * and rejecting close variants — even ones the upstream detectors might
 * pass on a slow day.
 *
 * <p>Why a separate layer? Because it has a different failure mode. The
 * regex layer has both false positives and false negatives. The LLM judge
 * is fuzzy. The attack-memory layer has a sharp threshold — at similarity
 * &gt;= 0.85 to a known attack we say BLOCK with high confidence; below
 * that we say "fine". This is the ML "k-nearest-neighbour" classifier
 * pattern, applied to security telemetry.
 *
 * <p>The memory is populated from two sources:
 * <ol>
 *   <li>A small hand-curated seed of known prompt-injection payloads
 *       (see {@link AttackMemory#seedDefaults()}).</li>
 *   <li>Runtime: whenever the policy engine BLOCKs or QUARANTINEs an
 *       input, we extract a content string from it and call
 *       {@link AttackMemory#remember}. This is hooked from the inspection
 *       service so the bank grows organically as the SOC operates.</li>
 * </ol>
 */
@Component
@Order(80) // after L6 budget (70) — last layer before the policy engine.
public class L7AttackMemoryScanner implements ScannerStage {

    private static final Logger log = LoggerFactory.getLogger(L7AttackMemoryScanner.class);

    /** Cosine threshold above which a payload is treated as a known attack. */
    private static final double MATCH_THRESHOLD = 0.85;

    /** Warn threshold: surface a soft signal in the timeline. */
    private static final double WARN_THRESHOLD = 0.65;

    /**
     * Tools whose payload never leaves the agent's trust boundary — there is
     * no recipient, no network destination, no money. Even a "match" against a
     * learned attack fingerprint cannot exfiltrate anything from a write to
     * one of these. Skipping them keeps benign happy-path scratchpad writes
     * (e.g. {@code notes.append} summaries like
     * "Compared Goa hotels under 6000 by rating") from cosine-overlapping with
     * runtime-learned poisoned-page text and producing a false BLOCK on a
     * route that was never an exfiltration channel to begin with.
     */
    private static final Set<String> SANDBOX_TOOLS = Set.of("notes.append");

    private final AttackMemory memory;

    public L7AttackMemoryScanner(AttackMemory memory) {
        this.memory = memory;
    }

    @PostConstruct
    void warmUp() {
        memory.seedDefaults();
    }

    @Override public String name() { return "L7"; }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        // Cheap to run — just a vector dot product against ≤256 entries.
        // Always evaluate so the bank can flag both inbound DOM and
        // outbound args (e.g. an exfil HTTP body that drifted past DLP).
        // Exception: sandbox-only outbound tools have no exfiltration path, so
        // a similarity match there is by construction a false positive.
        if (input instanceof InspectionInput.OutboundAction a
                && a.tool() != null
                && SANDBOX_TOOLS.contains(a.tool())) {
            return false;
        }
        return true;
    }

    @Override
    public Finding scan(InspectionInput input) {
        String text = textOf(input);
        if (text == null || text.isBlank()) return new Finding(0.0, "no content", Map.of());

        Map<String, Object> match = memory.bestMatch(text, WARN_THRESHOLD);
        if (match.isEmpty()) {
            return new Finding(0.0, "no match in attack memory",
                    Map.of("memory_size", memory.size()));
        }

        double sim = (double) match.get("similarity");
        Map<String, Object> ev = new LinkedHashMap<>(match);
        ev.put("memory_size", memory.size());

        if (sim >= MATCH_THRESHOLD) {
            log.info("L7 known-attack match: {} sim={}", match.get("known_attack"), sim);
            // Score 0.95 (not 1.0) so the policy engine still goes through
            // its risk-band rules but this layer effectively guarantees BLOCK
            // when the critical-risk threshold is configured at 0.85.
            return new Finding(0.95,
                    "matched known attack: " + match.get("known_attack") + " (sim=" + sim + ")",
                    ev);
        }
        // Soft signal — visible in the SOC, not blocking on its own.
        return new Finding(0.3,
                "near-miss known attack: " + match.get("known_attack") + " (sim=" + sim + ")",
                ev);
    }

    private static String textOf(InspectionInput input) {
        if (input instanceof InspectionInput.InboundContent c) {
            return c.content();
        }
        if (input instanceof InspectionInput.OutboundAction a) {
            // Serialize the args so we can fingerprint exfil-shaped payloads
            // ({"body": "...api_key..."}). Coarse but deterministic.
            if (a.args() == null || a.args().isEmpty()) return null;
            StringBuilder sb = new StringBuilder(128);
            for (Map.Entry<String, Object> e : a.args().entrySet()) {
                sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append(' ');
            }
            return sb.toString();
        }
        return null;
    }
}
