package com.sentinelmesh.api.rest;

import com.sentinelmesh.domain.port.out.ThreatRepository;
import com.sentinelmesh.persistence.repository.AuditEventJpaRepository;
import com.sentinelmesh.persistence.repository.SessionJpaRepository;
import com.sentinelmesh.policy.PolicyEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Dashboard summary counters")
public class MetricsSummaryController {

    private final ThreatRepository threats;
    private final PolicyEngine policy;
    private final MeterRegistry meterRegistry;
    private final AuditEventJpaRepository auditRepo;
    private final SessionJpaRepository sessionRepo;

    public MetricsSummaryController(ThreatRepository threats, PolicyEngine policy,
                                    MeterRegistry meterRegistry,
                                    AuditEventJpaRepository auditRepo,
                                    SessionJpaRepository sessionRepo) {
        this.threats = threats;
        this.policy = policy;
        this.meterRegistry = meterRegistry;
        this.auditRepo = auditRepo;
        this.sessionRepo = sessionRepo;
    }

    private static final java.util.List<String> CATEGORIES = java.util.List.of(
            "INDIRECT_PROMPT_INJECTION",
            "PROMPT_INJECTION",
            "CREDENTIAL_PHISHING",
            "DATA_EXFILTRATION",
            "BEHAVIORAL_ANOMALY",
            "CAPABILITY_BUDGET",
            "KNOWN_ATTACK",
            "AGENT_IDENTITY_VIOLATION",
            "CAPABILITY_ESCALATION_ATTEMPT");

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threats_total", threats.countAll());
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            byCategory.put(c, threats.countByCategory(c));
        }
        out.put("threats_by_category", byCategory);
        out.put("policy_rules_loaded", policy.ruleCount());
        out.put("detect_latency_ms", detectLatency());
        return out;
    }

    /** Real measured detect-and-decide latency from the {@code sentinel.pipeline.detect} timer. */
    private Map<String, Object> detectLatency() {
        Map<String, Object> m = new LinkedHashMap<>();
        Timer timer = meterRegistry.find("sentinel.pipeline.detect").timer();
        if (timer == null || timer.count() == 0) {
            m.put("samples", 0L);
            return m;
        }
        HistogramSnapshot snap = timer.takeSnapshot();
        m.put("samples", timer.count());
        m.put("mean", round(timer.mean(TimeUnit.MILLISECONDS)));
        m.put("max", round(timer.max(TimeUnit.MILLISECONDS)));
        m.put("p50", round(percentile(snap, 0.5)));
        m.put("p95", round(percentile(snap, 0.95)));
        m.put("p99", round(percentile(snap, 0.99)));
        return m;
    }

    private static double percentile(HistogramSnapshot snap, double p) {
        for (ValueAtPercentile v : snap.percentileValues()) {
            if (Math.abs(v.percentile() - p) < 1e-6) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return Double.NaN;
    }

    private static double round(double v) {
        return Double.isNaN(v) ? 0.0 : Math.round(v * 100.0) / 100.0;
    }

    /**
     * Live red-team stats computed from the audit_events table — ASR, block
     * rate, per-category threat counts, and session count. The "naked" column
     * is always zero here because SentinelMesh only sees protected traffic.
     */
    @GetMapping("/redteam")
    public Map<String, Object> redteam() {
        Map<String, Object> out = new LinkedHashMap<>();

        long totalDecisions = auditRepo.countSentinelDecisions();
        long blocked = auditRepo.countSentinelDecisionsBlocked();
        long allowed = totalDecisions - blocked;
        double asr = totalDecisions > 0 ? (double) allowed / totalDecisions : 0;

        // Sessions created in the last 24h
        long recentSessions = sessionRepo.countSessionsCreatedSince(
                java.time.Instant.now().minusSeconds(86400));

        out.put("total_attacks", totalDecisions);
        out.put("blocked", blocked);
        out.put("allowed", allowed);
        out.put("asr", round(asr));
        out.put("sessions_24h", recentSessions);

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            byCategory.put(c, threats.countByCategory(c));
        }
        out.put("threats_by_category", byCategory);
        out.put("policy_rules_loaded", policy.ruleCount());

        return out;
    }
}
