package com.sentinelmesh.security;

import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.model.RiskScore;
import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.model.Threat;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.domain.port.out.EventPublisher;
import com.sentinelmesh.domain.port.out.ThreatRepository;
import com.sentinelmesh.domain.service.ApprovalService;
import com.sentinelmesh.domain.service.SessionService;
import com.sentinelmesh.security.memory.AttackMemory;
import com.sentinelmesh.security.pipeline.PipelineResult;
import com.sentinelmesh.security.pipeline.ScannerStage;
import com.sentinelmesh.security.pipeline.SecurityPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single entry point for running an inspection and applying all of its side
 * effects: publishing the decision + threats, appending to the audit chain,
 * raising approvals, and freezing sessions on quarantine.
 *
 * <p>Both the REST {@code /sentinel/inspect} endpoint and the MCP adapter call
 * this, so every integration path produces identical, demoable behaviour.
 *
 * <p>Security note: when the decision is {@code REWRITE}, the <em>redacted</em>
 * payload — never the raw secret — is what gets published to the event stream
 * and recorded. Audit entries store decision metadata only, not raw payloads.
 */
@Service
public class SentinelInspectionService {

    private static final Logger log = LoggerFactory.getLogger(SentinelInspectionService.class);

    /**
     * Sandbox-only outbound tools — agent-local writes with no recipient and
     * no network exit. We never feed their args into the attack-memory bank
     * because the args are not predictive of any future real attack and
     * happen to share a lot of common-English shingles with poisoned-page
     * camouflage text, which slowly poisons L7 against happy-path note
     * summaries (e.g. "Compared Goa hotels under 6000 by rating").
     */
    private static final Set<String> NO_LEARN_TOOLS = Set.of("notes.append");

    /**
     * Scanner names whose score, when meaningful, indicates the BLOCK was
     * driven by the input's <em>content</em>. If none of these contributed,
     * the BLOCK was structural (CAP unknown_capability, L6 budget exhaustion)
     * and the args themselves are not a useful fingerprint to remember.
     */
    private static final Set<String> CONTENT_DRIVEN_SCANNERS = Set.of(
            "L1", "L2", "L3", "L4", "DLP");

    /** Minimum content-scanner score that counts as "content drove this block". */
    private static final double CONTENT_SIGNAL_THRESHOLD = 0.3;

    private final SecurityPipeline pipeline;
    private final EventPublisher publisher;
    private final ApprovalService approvals;
    private final ThreatRepository threats;
    private final AuditEventSink audit;
    private final SessionService sessions;
    private final AttackMemory attackMemory;
    private final Clock clock;

    public SentinelInspectionService(SecurityPipeline pipeline, EventPublisher publisher,
                                     ApprovalService approvals, ThreatRepository threats,
                                     AuditEventSink audit, SessionService sessions,
                                     AttackMemory attackMemory, Clock clock) {
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.approvals = approvals;
        this.threats = threats;
        this.audit = audit;
        this.sessions = sessions;
        this.attackMemory = attackMemory;
        this.clock = clock;
    }

    /** Result of an inspection plus the approval id, if one was raised. */
    public record Outcome(PipelineResult result, UUID approvalId) {}

    public Outcome inspect(InspectionInput input, UUID actionId) {
        UUID sessionId = input.sessionId();

        // Fail-closed: a quarantined session is frozen — its agent is no longer
        // trusted to act. Subsequent inspect calls must be rejected without
        // running scanners, regardless of how innocuous the next action looks.
        if (sessionId != null) {
            Session existing = sessions.find(sessionId).orElse(null);
            if (existing != null && existing.status() == Session.Status.QUARANTINED) {
                PipelineResult blocked = new PipelineResult(
                        RiskScore.safe(), Map.of(), Decision.BLOCK,
                        "quarantine_freeze", "session quarantined", 1.0, 0L, null, null);
                log.info("Blocking inspect on quarantined session {} (tool={})",
                        sessionId, input.tool());
                publisher.publish(new AgentEvent.SentinelDecision(
                        UuidV7.next(), sessionId, clock.instant(), "sentinel",
                        blocked.decision(), blocked.risk(), blocked.reason(),
                        Map.of("tool", input.tool() == null ? "" : input.tool())));
                return new Outcome(blocked, null);
            }
        }

        PipelineResult result = pipeline.inspect(input);
        UUID approvalId = applySideEffects(sessionId, actionId, input, result);
        return new Outcome(result, approvalId);
    }

    private UUID applySideEffects(UUID sessionId, UUID actionId,
                                  InspectionInput input, PipelineResult result) {
        Instant now = clock.instant();
        Map<String, Object> intercepted = interceptedPayload(input, result);

        publisher.publish(new AgentEvent.SentinelDecision(
                UuidV7.next(), sessionId, now, "sentinel",
                result.decision(), result.risk(), result.reason(), intercepted));

        Map<String, Object> auditPayload = new java.util.LinkedHashMap<>();
        auditPayload.put("decision", result.decision().name());
        auditPayload.put("risk", result.risk().composite());
        auditPayload.put("scores", result.risk().scores());
        auditPayload.put("blast", result.blastRadius());
        auditPayload.put("rule", result.policyMatched() == null ? "" : result.policyMatched());
        auditPayload.put("reason", result.reason() == null ? "" : result.reason());
        auditPayload.put("tool", input.tool() == null ? "" : input.tool());
        // Stash the boolean signals the policy DSL can match on, so the
        // policy simulator can replay rules without re-running scanners.
        // (has_secret/has_pii from DLP, over_budget from L6.)
        ScannerStage.Finding dlpFinding = result.findings().get("DLP");
        if (dlpFinding != null && dlpFinding.evidence() != null) {
            auditPayload.put("has_secret", dlpFinding.evidence().containsKey("secrets"));
            auditPayload.put("has_pii", dlpFinding.evidence().containsKey("pii"));
        } else {
            auditPayload.put("has_secret", false);
            auditPayload.put("has_pii", false);
        }
        ScannerStage.Finding l6Finding = result.findings().get("L6");
        boolean overBudget = false;
        boolean tenantOverBudget = false;
        if (l6Finding != null && l6Finding.evidence() != null) {
            Object flag = l6Finding.evidence().get("over_budget");
            overBudget = (flag instanceof Boolean b && b)
                    || (flag instanceof Number n && n.intValue() != 0);
            Object scope = l6Finding.evidence().get("budget_scope");
            tenantOverBudget = overBudget && "TENANT".equals(String.valueOf(scope));
        }
        auditPayload.put("over_budget", overBudget);
        auditPayload.put("tenant_over_budget", tenantOverBudget);
        // L7 attack-memory match — surfaced on the audit row so the drawer
        // can render a "matched known attack" badge without a second query.
        ScannerStage.Finding l7Finding = result.findings().get("L7");
        if (l7Finding != null && l7Finding.evidence() != null
                && l7Finding.evidence().containsKey("known_attack")) {
            auditPayload.put("known_attack", l7Finding.evidence().get("known_attack"));
            auditPayload.put("attack_similarity", l7Finding.evidence().get("similarity"));
        }
        boolean capabilityEscalation = false;
        ScannerStage.Finding capFinding = result.findings().get("CAP");
        if (capFinding != null && capFinding.score() >= 0.9) {
            capabilityEscalation = true;
        }
        auditPayload.put("capability_escalation", capabilityEscalation);
        audit.append("sentinel_decision", "sentinel", sessionId, auditPayload);

        for (Map.Entry<String, ScannerStage.Finding> e : result.findings().entrySet()) {
            ScannerStage.Finding f = e.getValue();
            if (f.score() < 0.2) continue;
            String category = categorize(e.getKey(), f);
            Threat.Severity sev = Threat.severityFromScore(f.score());
            Threat t = new Threat(UuidV7.next(), sessionId, actionId, category, sev, f.score(),
                    new HashMap<>(f.evidence() == null ? Map.of() : f.evidence()), now);
            try {
                threats.save(t);
            } catch (Exception ex) {
                log.warn("Threat persist failed: {}", ex.toString());
            }
            publisher.publish(new AgentEvent.ThreatDetected(
                    UuidV7.next(), sessionId, now, "sentinel",
                    category, sev.name(), f.score(), t.evidence()));
        }

        if (capFinding != null && capFinding.score() >= 0.9 && capFinding.evidence() != null) {
            Map<String, Object> capAudit = new java.util.LinkedHashMap<>();
            capAudit.put("session_id", sessionId == null ? "" : sessionId.toString());
            capAudit.put("origin_actor", capFinding.evidence().getOrDefault("origin_actor", ""));
            capAudit.put("current_actor", capFinding.evidence().getOrDefault("current_actor", ""));
            capAudit.put("capability", capFinding.evidence().getOrDefault("requested_capability", ""));
            capAudit.put("action", capFinding.evidence().getOrDefault("action_name", ""));
            capAudit.put("timestamp", now.toString());
            capAudit.put("reason", capFinding.evidence().getOrDefault("reason", capFinding.reason()));
            audit.append("capability_escalation_detected", "sentinel", sessionId, capAudit);
        }

        if (result.decision() == Decision.QUARANTINE) {
            sessions.markQuarantined(sessionId);
        }

        // Learn from this verdict: a content-driven BLOCK or QUARANTINE feeds
        // the attack memory bank so future near-duplicates are caught by L7
        // even if L1..L6 would have let them through. We deliberately do NOT
        // remember:
        //   1. Inputs L7 already matched at high confidence — no new info.
        //   2. Sandbox-only outbound tools (e.g. notes.append) — their args
        //      are not predictive of real attacks; remembering them poisons
        //      the bank against happy-path scratchpad writes whose surface
        //      text overlaps with previously learned camouflage content.
        //   3. Structural-only BLOCKs (CAP confused-deputy, L6 budget) where
        //      no content scanner had a meaningful score — the args are
        //      incidental, the block was driven by who-asked-whom or by
        //      having spent the cap, not by what the payload says.
        if (result.decision() == Decision.BLOCK || result.decision() == Decision.QUARANTINE) {
            if (shouldRememberFor(input, result)) {
                String text = learnableText(input);
                if (text != null && !text.isBlank()) {
                    try {
                        attackMemory.remember(text,
                                result.policyMatched() == null ? "blocked" : result.policyMatched());
                    } catch (Exception ex) {
                        log.warn("AttackMemory.remember failed: {}", ex.toString());
                    }
                }
            }
        }

        if (result.decision() == Decision.REQUIRE_APPROVAL) {
            Map<String, Object> payload = (input instanceof InspectionInput.OutboundAction a)
                    ? Map.of("tool", a.tool(), "args", a.args())
                    : Map.of("tool", input.tool());
            String intent = "Agent wants to invoke " + input.tool();
            return approvals.request(sessionId, actionId, intent, payload, result.blastRadius()).id();
        }
        return null;
    }

    /**
     * Decide whether the just-blocked verdict should feed the attack memory
     * bank. See the call site for the full rationale; in short, only learn
     * when the block was driven by the payload's <em>content</em> on a
     * non-sandbox channel.
     */
    private static boolean shouldRememberFor(InspectionInput input, PipelineResult result) {
        ScannerStage.Finding l7match = result.findings().get("L7");
        if (l7match != null && l7match.score() >= 0.9) {
            return false;
        }
        if (input instanceof InspectionInput.OutboundAction a
                && a.tool() != null
                && NO_LEARN_TOOLS.contains(a.tool())) {
            return false;
        }
        // Inbound content is always content-driven by definition.
        if (input instanceof InspectionInput.InboundContent) {
            return true;
        }
        // Outbound: require at least one content scanner to have a real say.
        for (String name : CONTENT_DRIVEN_SCANNERS) {
            ScannerStage.Finding f = result.findings().get(name);
            if (f != null && f.score() >= CONTENT_SIGNAL_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** Pluck a representative string from the input so the attack-memory bank
     *  can fingerprint it. Returns null for inputs where there's nothing
     *  meaningful to remember (e.g. an empty outbound action). */
    private static String learnableText(InspectionInput input) {
        if (input instanceof InspectionInput.InboundContent c) {
            return c.content();
        }
        if (input instanceof InspectionInput.OutboundAction a) {
            if (a.args() == null || a.args().isEmpty()) return null;
            StringBuilder sb = new StringBuilder(128);
            for (Map.Entry<String, Object> e : a.args().entrySet()) {
                sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append(' ');
            }
            return sb.toString();
        }
        return null;
    }

    /** Build the payload broadcast with the decision, redacting on REWRITE. */
    private Map<String, Object> interceptedPayload(InspectionInput input, PipelineResult result) {
        if (input instanceof InspectionInput.OutboundAction a) {
            Map<String, Object> args = result.rewrittenArgs() != null ? result.rewrittenArgs() : a.args();
            return Map.of("tool", a.tool(), "args", args == null ? Map.of() : args);
        }
        InspectionInput.InboundContent c = (InspectionInput.InboundContent) input;
        String sample = result.rewrittenContent() != null ? result.rewrittenContent() : c.content();
        return Map.of("tool", c.tool() == null ? "" : c.tool(), "sample", truncate(sample, 160));
    }

    /**
     * Maps scanner stage to SOC threat category. L3/L4 align with README:
     * Azure Prompt Shields (L3) → indirect-injection taxonomy; LLM judge (L4)
     * → broader prompt-injection / semantic grey-zone signal.
     */
    private static String categorize(String scannerName, ScannerStage.Finding f) {
        return switch (scannerName) {
            case "L1" -> {
                Map<String, Object> ev = f.evidence() == null ? Map.of() : f.evidence();
                if (ev.containsKey("credential_exfil_intent_hits")) yield "CREDENTIAL_EXFILTRATION_INTENT";
                if (ev.containsKey("hidden_dom_hits")) yield "INDIRECT_PROMPT_INJECTION";
                if (ev.containsKey("credential_phishing_hits")) yield "CREDENTIAL_PHISHING";
                yield "PROMPT_INJECTION";
            }
            case "L3" -> "INDIRECT_PROMPT_INJECTION";
            case "L4" -> "PROMPT_INJECTION";
            case "L5" -> "BEHAVIORAL_ANOMALY";
            case "L6" -> "CAPABILITY_BUDGET";
            case "L7" -> "KNOWN_ATTACK";
            case "CAP" -> "CAPABILITY_ESCALATION_ATTEMPT";
            case "DLP" -> "DATA_EXFILTRATION";
            default -> "UNKNOWN";
        };
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
