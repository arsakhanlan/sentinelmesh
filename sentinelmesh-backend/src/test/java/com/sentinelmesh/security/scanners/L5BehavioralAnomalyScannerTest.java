package com.sentinelmesh.security.scanners;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the L5 behavioural-anomaly scanner.
 *
 * <p>The scanner historically produced a {@code BEHAVIORAL_ANOMALY MEDIUM}
 * threat (score 0.4) on the second outbound call of every fresh session
 * because every transition is "novel" until the agent repeats one. That
 * lit up every benign happy-path agent run with a yellow row in the SOC.
 *
 * <p>These tests guarantee:
 * <ul>
 *   <li>A clean 2-step plan ({@code http.get} → {@code notes.append}) scores
 *       0 on both calls. (The bug.)</li>
 *   <li>A clean 3-step plan stays clean.</li>
 *   <li>A 6-step burst on a single tool still fires the malicious-workflow
 *       signal — we did not weaken detection.</li>
 *   <li>First use of a high-risk tool ({@code email.send},
 *       {@code payments.charge}) still surfaces a soft signal so the SOC
 *       sees the first money-movement / first message.</li>
 * </ul>
 */
class L5BehavioralAnomalyScannerTest {

    private static InspectionInput.OutboundAction call(UUID sid, String tool) {
        return InspectionInput.OutboundAction.withoutProvenance(sid, tool, Map.of());
    }

    @Test
    void clean_two_step_plan_does_not_fire_anomaly() {
        L5BehavioralAnomalyScanner s = new L5BehavioralAnomalyScanner();
        UUID sid = UUID.randomUUID();

        ScannerStage.Finding f1 = s.scan(call(sid, "http.get"));
        ScannerStage.Finding f2 = s.scan(call(sid, "notes.append"));

        assertThat(f1.score())
                .as("opening tool of a fresh session must score zero — it's "
                    + "always novel by definition")
                .isEqualTo(0.0);
        assertThat(f2.score())
                .as("a 2-step happy-path plan (http.get -> notes.append) "
                    + "must NOT emit a behavioural anomaly: the session "
                    + "has not established a pattern yet, so 'novel' has "
                    + "no meaning")
                .isEqualTo(0.0);
    }

    @Test
    void clean_three_step_plan_stays_clean() {
        L5BehavioralAnomalyScanner s = new L5BehavioralAnomalyScanner();
        UUID sid = UUID.randomUUID();

        ScannerStage.Finding f1 = s.scan(call(sid, "browser.goto"));
        ScannerStage.Finding f2 = s.scan(call(sid, "http.get"));
        ScannerStage.Finding f3 = s.scan(call(sid, "notes.append"));

        assertThat(f1.score()).isEqualTo(0.0);
        assertThat(f2.score()).isEqualTo(0.0);
        assertThat(f3.score()).isEqualTo(0.0);
    }

    @Test
    void burst_on_single_tool_still_fires_high() {
        // Malicious-workflow scenario: the agent calls the same tool over
        // and over inside one session. The "burst" rule (toolInSession >= 3)
        // must keep firing — we did not weaken detection.
        L5BehavioralAnomalyScanner s = new L5BehavioralAnomalyScanner();
        UUID sid = UUID.randomUUID();

        ScannerStage.Finding last = null;
        for (int i = 0; i < 6; i++) {
            last = s.scan(call(sid, "browser.goto"));
        }
        assertThat(last).isNotNull();
        assertThat(last.score())
                .as("6 calls to the same tool in one session must score "
                    + "in the high anomaly band")
                .isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void first_use_of_high_risk_tool_still_surfaces_soft_signal() {
        // Even on call #1 of a session, email.send and payments.charge
        // should generate a soft (~0.3) signal so the SOC sees them.
        // This signal stays well below the BLOCK / approval threshold.
        L5BehavioralAnomalyScanner s = new L5BehavioralAnomalyScanner();

        ScannerStage.Finding email = s.scan(call(UUID.randomUUID(), "email.send"));
        ScannerStage.Finding pay   = s.scan(call(UUID.randomUUID(), "payments.charge"));

        assertThat(email.score()).isBetween(0.25, 0.45);
        assertThat(pay.score()).isBetween(0.25, 0.45);
    }

    @Test
    void mid_workflow_drift_still_caught_after_pattern_established() {
        // After the agent has shown a stable pattern, a brand-new
        // transition (drift) should still surface as anomaly. This proves
        // the fix didn't blind L5 to genuine novelty — it just requires
        // a baseline first.
        L5BehavioralAnomalyScanner s = new L5BehavioralAnomalyScanner();
        UUID sid = UUID.randomUUID();

        // Establish a stable 4-call pattern (browser.goto / http.get loop).
        s.scan(call(sid, "browser.goto"));
        s.scan(call(sid, "http.get"));
        s.scan(call(sid, "browser.goto"));
        s.scan(call(sid, "http.get"));

        // Now drift: never-before-seen tool in this session.
        ScannerStage.Finding drift = s.scan(call(sid, "payments.charge"));

        // payments.charge is also a high-risk tool (independently triggers
        // the soft 0.30 signal), so we just assert the drift score is at
        // least the novelty threshold.
        assertThat(drift.score())
                .as("genuine novelty after an established 4-call pattern "
                    + "must still fire the L5 transition signal")
                .isGreaterThanOrEqualTo(0.4);
    }
}
