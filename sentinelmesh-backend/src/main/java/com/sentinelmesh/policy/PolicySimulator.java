package com.sentinelmesh.policy;

import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.persistence.entity.AuditEventEntity;
import com.sentinelmesh.persistence.repository.AuditEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What-if analysis for a candidate policy bundle.
 *
 * <p>Workflow: a security engineer drafts a new YAML bundle in the SOC editor.
 * Before deploying it, they call {@code POST /api/policies/simulate}. The
 * simulator:
 * <ol>
 *   <li>Compiles their candidate YAML to {@link Rule}s.</li>
 *   <li>Replays the last {@code windowHours} of {@code sentinel_decision}
 *       audit events through the candidate rules (using the boolean signals
 *       stored on each event — see {@code SentinelInspectionService}).</li>
 *   <li>Diffs the would-be decision against the actually-recorded decision
 *       and groups changes by direction (ALLOW→BLOCK, BLOCK→ALLOW, ...).</li>
 *   <li>Surfaces sample events for each category so the engineer can
 *       eyeball false positives / false negatives before deploying.</li>
 * </ol>
 *
 * <p>Why replay against the recorded signals (not re-run the scanners): the
 * scanners are deterministic but slow (some hit an LLM). Audit replay is
 * milliseconds — fast enough to iterate on a rule until it lands. The
 * obvious limitation: rules that depend on signals we never recorded can't
 * be simulated; the simulator flags those with a warning.
 *
 * <p>This is read-only. Nothing the simulator does is persisted; it never
 * mutates the active {@link PolicyEngine} bundle.
 */
@Component
public class PolicySimulator {

    private static final Logger log = LoggerFactory.getLogger(PolicySimulator.class);

    /** Knobs to keep a runaway simulation request from melting the DB. */
    private static final int MAX_EVENTS = 5_000;
    private static final int MAX_WINDOW_HOURS = 24 * 7;       // 1 week
    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int SAMPLES_PER_CATEGORY = 5;

    private final AuditEventJpaRepository auditRepo;
    private final Clock clock;
    private final RuleExpressionEvaluator eval = new RuleExpressionEvaluator();

    public PolicySimulator(AuditEventJpaRepository auditRepo, Clock clock) {
        this.auditRepo = auditRepo;
        this.clock = clock;
    }

    /** What the simulation produces. Designed to render directly in the UI. */
    public record Result(
            int eventsConsidered,
            int windowHours,
            Map<String, Integer> changeCounts,           // e.g. {"ALLOW->BLOCK": 12}
            Map<String, List<EventDiff>> samples,        // up to N per category
            int ruleCount,
            List<String> warnings
    ) {}

    /** One per audit event whose decision changed under the candidate bundle. */
    public record EventDiff(
            Long sequence,
            String tool,
            double risk,
            double blast,
            String oldDecision,
            String oldRule,
            String newDecision,
            String newRule,
            String newReason
    ) {}

    public Result simulate(String candidateYaml, Integer windowHoursOpt) {
        int windowHours = clamp(windowHoursOpt == null ? DEFAULT_WINDOW_HOURS : windowHoursOpt,
                                1, MAX_WINDOW_HOURS);
        List<Rule> rules;
        try (var in = new ByteArrayInputStream(candidateYaml.getBytes(StandardCharsets.UTF_8))) {
            rules = new PolicyCompiler().compile(in);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Candidate bundle failed to compile: " + rootMessage(e), e);
        }

        Instant since = clock.instant().minus(Duration.ofHours(windowHours));
        List<AuditEventEntity> events = auditRepo.findRecentByKind(
                "sentinel_decision", since, PageRequest.of(0, MAX_EVENTS));
        log.info("Simulating {} candidate rules across {} audit events (window={}h)",
                 rules.size(), events.size(), windowHours);

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<EventDiff>> samples = new LinkedHashMap<>();
        int missingSignalWarnings = 0;

        for (AuditEventEntity e : events) {
            Map<String, Object> p = e.getPayload();
            if (p == null) continue;

            String oldDecision = String.valueOf(p.getOrDefault("decision", "ALLOW"));
            String oldRule = String.valueOf(p.getOrDefault("rule", "default-allow"));

            Map<String, Object> ctx = contextFrom(p);
            // Older events (before the simulator landed) may be missing
            // has_secret / over_budget. We still simulate them, but flag.
            if (!p.containsKey("over_budget")) missingSignalWarnings++;

            PolicyDecision candidate = decide(rules, ctx);
            if (!candidate.decision().name().equals(oldDecision)) {
                String key = oldDecision + "->" + candidate.decision().name();
                counts.merge(key, 1, Integer::sum);
                samples.computeIfAbsent(key, k -> new ArrayList<>());
                List<EventDiff> bucket = samples.get(key);
                if (bucket.size() < SAMPLES_PER_CATEGORY) {
                    bucket.add(new EventDiff(
                            e.getSequence(),
                            String.valueOf(p.getOrDefault("tool", "")),
                            doubleOf(p.get("risk")),
                            doubleOf(p.get("blast")),
                            oldDecision, oldRule,
                            candidate.decision().name(), candidate.matchedRule(),
                            candidate.reason()));
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        if (missingSignalWarnings > 0) {
            warnings.add(missingSignalWarnings
                    + " older audit events lacked over_budget / has_secret signals; "
                    + "simulator treated them as false. Their replay may not exactly "
                    + "match a fresh inspection.");
        }
        if (events.size() == MAX_EVENTS) {
            warnings.add("Simulation capped at " + MAX_EVENTS
                    + " events; widen the window or narrow your bundle to see more.");
        }

        return new Result(events.size(), windowHours, counts, samples, rules.size(), warnings);
    }

    // -------------------------------------------------------------------- //

    private PolicyDecision decide(List<Rule> rules, Map<String, Object> ctx) {
        for (Rule r : rules) {
            try {
                if (eval.eval(r.whenExpr(), ctx)) {
                    return new PolicyDecision(r.then(), r.name(), r.reason());
                }
            } catch (Exception ex) {
                // Don't let a single bad rule abort the whole simulation.
                log.debug("Candidate rule {} threw during simulation: {}", r.name(), ex.toString());
            }
        }
        return PolicyDecision.allow("no rule matched");
    }

    /** Build the same context the live PolicyEngine builds — for fidelity. */
    private static Map<String, Object> contextFrom(Map<String, Object> payload) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("risk", doubleOf(payload.get("risk")));
        ctx.put("blast", doubleOf(payload.get("blast")));
        ctx.put("tool", String.valueOf(payload.getOrDefault("tool", "")));
        ctx.put("has_secret", boolOf(payload.get("has_secret")));
        ctx.put("has_pii", boolOf(payload.get("has_pii")));
        ctx.put("over_budget", boolOf(payload.get("over_budget")));
        ctx.put("tenant_over_budget", boolOf(payload.get("tenant_over_budget")));
        ctx.put("known_attack", boolOf(payload.get("known_attack")));
        ctx.put("capability_escalation", boolOf(payload.get("capability_escalation")));
        return ctx;
    }

    private static double doubleOf(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    private static boolean boolOf(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0.0;
        if (o instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return false;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
