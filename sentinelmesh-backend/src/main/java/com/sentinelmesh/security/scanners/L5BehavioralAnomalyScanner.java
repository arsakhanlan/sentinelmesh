package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * L5 — Behavioral anomaly, now per-session and sequence-aware.
 *
 * <p>Maintains two models:
 * <ul>
 *   <li>a <b>global</b> per-tool frequency prior (what tools agents normally use), and</li>
 *   <li>a <b>per-session</b> tool histogram + tool-transition (bigram) set.</li>
 * </ul>
 *
 * <p>A call is anomalous when it is (a) globally rare but locally frequent
 * (rarity), (b) a burst of repeated calls within one session, (c) a never-seen
 * tool transition for that session, or (d) first use of a high-blast tool.
 * This is what lets the "malicious workflow" scenario (a 6-step burst on a
 * rarely-used tool) actually surface as a behavioral signal.
 *
 * <p>Hackathon-grade: in-memory, crudely bounded. Good enough for the demo.
 */
@Component
@Order(50)
public class L5BehavioralAnomalyScanner implements ScannerStage {

    private static final int MAX_TRACKED_SESSIONS = 10_000;

    private final Map<String, LongAdder> globalToolCounts = new ConcurrentHashMap<>();
    private final LongAdder globalTotal = new LongAdder();
    private final Map<UUID, SessionStats> perSession = new ConcurrentHashMap<>();

    @Override public String name() { return "L5"; }

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        return input instanceof InspectionInput.OutboundAction;
    }

    @Override
    public Finding scan(InspectionInput input) {
        InspectionInput.OutboundAction a = (InspectionInput.OutboundAction) input;
        String tool = a.tool() == null ? "unknown" : a.tool();

        globalToolCounts.computeIfAbsent(tool, k -> new LongAdder()).increment();
        globalTotal.increment();

        SessionStats st = sessionStats(a.sessionId());
        long toolInSession;
        long sessionTotal;
        boolean novelTransition;
        long establishedTransitions;
        synchronized (st) {
            // Snapshot how many transitions were ALREADY known before this call;
            // we need this to tell "the agent has settled into a pattern" from
            // "we are still building one for the first time".
            establishedTransitions = st.transitions.size();
            toolInSession = st.bump(tool);
            sessionTotal = st.total;
            novelTransition = st.markTransition(tool);
        }

        double globalShare = (double) globalToolCounts.get(tool).sum() / Math.max(1, globalTotal.sum());
        double sessionShare = (double) toolInSession / Math.max(1, sessionTotal);

        // (a) Rarity: a tool that is globally uncommon but dominates this session.
        // Only meaningful once a session has a little history — otherwise the
        // first call of every session looks "rare" (sessionShare is always 1.0
        // on call #1), which would false-positive on benign opening actions.
        double rarity = sessionTotal >= 3 ? Math.max(0.0, sessionShare - globalShare) : 0.0;
        double score = Math.min(1.0, rarity * 1.5);

        // (b) Burst: many calls to the same tool inside one session.
        if (toolInSession >= 3) {
            score = Math.max(score, Math.min(0.9, 0.18 * toolInSession));
        }

        // (c) Novel tool transition. The naive form ("any transition we
        // haven't seen yet") false-positives on every fresh session: a 2-step
        // plan like {http.get -> notes.append} has *both* of its transitions
        // (START->http.get, http.get->notes.append) novel by definition, and
        // the second one fires a MEDIUM threat on every benign happy path.
        // Real novelty needs a baseline pattern to deviate from. We require
        // (i) the session has at least one prior transition on the books AND
        // (ii) at least 4 calls in the session before we treat a new sequence
        // as anomalous. This still catches genuine drift mid-workflow but
        // leaves clean openings alone.
        if (novelTransition && establishedTransitions >= 2 && sessionTotal >= 4) {
            score = Math.max(score, 0.4);
        }

        // (d) High-risk tools are their own signal on first use. We surface
        // payments.* and email.send as soft anomalies so the SOC sees the
        // first money-movement / first message in any session — but the
        // signal stays well below the approval / block thresholds. Earlier
        // we used 0.55 here; that drowned every first legitimate booking
        // confirmation email in approval-gate noise (composite ≈ 0.39 was
        // enough to lose the clean-email exemption to high-blast). 0.30
        // keeps the row on the SOC timeline without misclassifying it.
        boolean highRiskTool = tool.startsWith("payments.") || tool.equals("email.send");
        if (highRiskTool && toolInSession <= 2) {
            score = Math.max(score, 0.30);
        }

        if (score < 0.15) return Finding.clean("baseline");
        Map<String, Object> ev = new HashMap<>();
        ev.put("tool", tool);
        ev.put("session_invocations", toolInSession);
        ev.put("session_total", sessionTotal);
        ev.put("session_share", String.format("%.2f", sessionShare));
        ev.put("global_share", String.format("%.2f", globalShare));
        ev.put("novel_transition", novelTransition);
        return new Finding(score,
                "L5 anomaly: tool=" + tool + (novelTransition ? " (novel sequence)" : " (burst/rarity)"),
                ev);
    }

    private SessionStats sessionStats(UUID sessionId) {
        if (sessionId == null) return new SessionStats(); // ephemeral, unkeyed
        if (perSession.size() > MAX_TRACKED_SESSIONS) perSession.clear();
        return perSession.computeIfAbsent(sessionId, k -> new SessionStats());
    }

    /** Per-session behavioral state. Guard all access with the instance monitor. */
    private static final class SessionStats {
        private final Map<String, Long> counts = new HashMap<>();
        private final Set<String> transitions = new HashSet<>();
        private String prevTool = null;
        private long total = 0;

        long bump(String tool) {
            total++;
            return counts.merge(tool, 1L, Long::sum);
        }

        /** Record the prev→tool transition; return true if it is new for this session. */
        boolean markTransition(String tool) {
            String key = (prevTool == null ? "START" : prevTool) + "->" + tool;
            boolean novel = transitions.add(key);
            prevTool = tool;
            return novel;
        }
    }
}
