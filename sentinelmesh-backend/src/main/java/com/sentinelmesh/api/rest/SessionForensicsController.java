package com.sentinelmesh.api.rest;

import com.sentinelmesh.domain.model.Approval;
import com.sentinelmesh.domain.model.AuditEvent;
import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.model.Threat;
import com.sentinelmesh.domain.port.out.ApprovalRepository;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.domain.port.out.ThreatRepository;
import com.sentinelmesh.domain.service.SessionService;
import com.sentinelmesh.security.budget.BudgetTracker;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forensics endpoints scoped to a single session. The SOC drawer fetches a
 * full reconstruction in one round-trip:
 *
 * <ul>
 *   <li>{@code GET /api/v1/sessions/{id}/timeline} — audit chain entries, threats,
 *       approvals, and the live capability-budget snapshot, all on one wire.</li>
 *   <li>{@code GET /api/v1/sessions/{id}/verify} — runs SHA-256 re-derivation on
 *       every audit row for this session and reports chain integrity.</li>
 * </ul>
 *
 * <p>This is the read-only equivalent of "open the flight recorder" — what
 * happened, why we blocked / allowed it, and cryptographic proof the record
 * itself is intact.
 */
@RestController
@RequestMapping("/api/v1/sessions/{id}")
@Tag(name = "Forensics", description = "Per-session timeline + chain integrity for the SOC drawer")
public class SessionForensicsController {

    private final SessionService sessions;
    private final AuditEventSink audit;
    private final ThreatRepository threats;
    private final ApprovalRepository approvals;
    private final BudgetTracker budgets;

    public SessionForensicsController(SessionService sessions, AuditEventSink audit,
                                       ThreatRepository threats, ApprovalRepository approvals,
                                       BudgetTracker budgets) {
        this.sessions = sessions;
        this.audit = audit;
        this.threats = threats;
        this.approvals = approvals;
        this.budgets = budgets;
    }

    @GetMapping("/timeline")
    public Timeline timeline(@PathVariable UUID id) {
        Session session = sessions.get(id);

        List<AuditEvent> auditRows = audit.exportForSession(id);
        List<Map<String, Object>> auditOut = auditRows.stream().map(SessionForensicsController::toAuditMap).toList();

        List<Threat> threatRows = threats.findBySession(id);
        List<Map<String, Object>> threatsOut = threatRows.stream()
                .sorted(Comparator.comparing(Threat::createdAt))
                .map(SessionForensicsController::toThreatMap).toList();

        List<Approval> approvalRows = approvals.findBySession(id);
        List<Map<String, Object>> approvalsOut = approvalRows.stream()
                .map(SessionForensicsController::toApprovalMap).toList();

        BudgetTracker.Snapshot snap = budgets.snapshot(id);
        return new Timeline(
                SessionView.from(session),
                auditOut,
                threatsOut,
                approvalsOut,
                budgetView(snap));
    }

    @GetMapping("/verify")
    public Map<String, Object> verify(@PathVariable UUID id) {
        Session s = sessions.get(id);
        boolean ok = audit.verifySession(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", id);
        out.put("chain_intact", ok);
        out.put("status", s.status().name());
        return out;
    }

    // ---- response shapes ----

    /** Compact session view (avoids exposing internal fields like ended_at = null on live sessions awkwardly). */
    public record SessionView(UUID id, String userId, String goal, String status,
                              String policyBundleId, Map<String, Object> capabilityToken,
                              java.time.Instant createdAt, java.time.Instant endedAt) {
        static SessionView from(Session s) {
            return new SessionView(s.id(), s.userId(), s.goal(), s.status().name(),
                    s.policyBundleId(), s.capabilityToken(), s.createdAt(), s.endedAt());
        }
    }

    public record Timeline(SessionView session,
                            List<Map<String, Object>> audit,
                            List<Map<String, Object>> threats,
                            List<Map<String, Object>> approvals,
                            Map<String, Object> budget) {}

    private static Map<String, Object> toAuditMap(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence", e.sequence());
        m.put("eventId", e.eventId());
        m.put("ts", e.timestamp());
        m.put("kind", e.kind());
        m.put("actor", e.actor());
        m.put("payload", e.payload());
        m.put("prevHash", Base64.getEncoder().encodeToString(e.prevHash()));
        m.put("hash", Base64.getEncoder().encodeToString(e.hash()));
        return m;
    }

    private static Map<String, Object> toThreatMap(Threat t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("actionId", t.actionId());
        m.put("ts", t.createdAt());
        m.put("category", t.category());
        m.put("severity", t.severity().name());
        m.put("score", t.score());
        m.put("evidence", t.evidence());
        return m;
    }

    private static Map<String, Object> toApprovalMap(Approval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("actionId", a.actionId());
        m.put("intent", a.intent());
        m.put("status", a.status().name());
        m.put("decision", a.decision() == null ? null : a.decision().name());
        m.put("approverId", a.approverId());
        m.put("blastRadius", a.blastRadius());
        m.put("requestedAt", a.requestedAt());
        m.put("decidedAt", a.decidedAt());
        m.put("ttlAt", a.ttlAt());
        m.put("requestedPayload", a.requestedPayload());
        return m;
    }

    private static Map<String, Object> budgetView(BudgetTracker.Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> tools = new LinkedHashMap<>();
        for (Map.Entry<String, BudgetTracker.Snapshot.ToolUsage> e : s.tools().entrySet()) {
            BudgetTracker.Snapshot.ToolUsage u = e.getValue();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("used", u.used());
            view.put("cap", u.cap() == Integer.MAX_VALUE ? null : u.cap());
            tools.put(e.getKey(), view);
        }
        out.put("tools", tools);
        out.put("spendUsedInr", s.spendUsed());
        out.put("spendCapInr", s.spendCap());
        return out;
    }
}
