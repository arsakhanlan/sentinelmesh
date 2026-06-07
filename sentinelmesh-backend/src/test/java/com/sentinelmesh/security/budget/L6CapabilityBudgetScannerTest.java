package com.sentinelmesh.security.budget;

import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.model.Session;
import com.sentinelmesh.domain.port.out.EventPublisher;
import com.sentinelmesh.domain.port.out.SessionRepository;
import com.sentinelmesh.domain.service.SessionService;
import com.sentinelmesh.persistence.repository.TenantJpaRepository;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavior tests for the L6 capability-budget scanner.
 *
 * <p>This is the prevention layer (vs. the detection layers L1..L5+DLP). The
 * point of these tests is to prove that:
 *
 * <ul>
 *   <li>A clean call stays at score 0.0 (won't trip BLOCK rules).</li>
 *   <li>Approaching the cap raises a warn-level score but doesn't BLOCK.</li>
 *   <li>Exceeding the cap raises score 1.0 with structured evidence
 *       (so the policy engine can match {@code over_budget == 1}).</li>
 *   <li>The spend cap is honored even if individual call counts are fine.</li>
 *   <li>The scanner only runs on outbound actions (no false positives on
 *       inbound DOM content).</li>
 * </ul>
 *
 * Hand-rolled in-memory repository keeps the test free of heavy Spring wiring.
 */
class L6CapabilityBudgetScannerTest {

    private static BudgetTracker budgetTracker(SessionService svc) {
        TenantJpaRepository tenants = mock(TenantJpaRepository.class);
        when(tenants.findById(any())).thenReturn(Optional.empty());
        return new BudgetTracker(svc, tenants);
    }

    private static SessionService sessionServiceWithToken(UUID id, Map<String, Object> token) {
        SessionRepository repo = new FakeSessionRepository();
        Session s = new Session(id, "u1", "g", Session.Status.EXECUTING,
                "default", token, Instant.EPOCH, null, null);
        repo.save(s);
        EventPublisher noop = ev -> {};
        return new SessionService(repo, noop, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void clean_call_within_budget_scores_zero() {
        UUID sid = UUID.randomUUID();
        SessionService svc = sessionServiceWithToken(sid, Map.of(
                "tool_caps", Map.of("email.send", 3),
                "spend_cap_inr", 10_000));
        BudgetTracker tracker = budgetTracker(svc);
        L6CapabilityBudgetScanner scanner = new L6CapabilityBudgetScanner(tracker);

        ScannerStage.Finding f = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "email.send", Map.of("to", "ops@example.com")));

        assertThat(f.score()).isEqualTo(0.0);
        assertThat(f.evidence()).containsEntry("over_budget", false);
        assertThat(f.evidence()).containsEntry("used", 1);
    }

    @Test
    void approaching_cap_emits_warn_score_but_doesnt_block() {
        UUID sid = UUID.randomUUID();
        SessionService svc = sessionServiceWithToken(sid, Map.of(
                "tool_caps", Map.of("http.get", 5),
                "spend_cap_inr", 10_000));
        BudgetTracker tracker = budgetTracker(svc);
        L6CapabilityBudgetScanner scanner = new L6CapabilityBudgetScanner(tracker);

        ScannerStage.Finding f = null;
        // Call 1..4 — under the 80% threshold so warn shouldn't fire yet.
        for (int i = 0; i < 4; i++) {
            f = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                    sid, "http.get", Map.of()));
        }
        // Now at 4/5 = 80% — should be a warn (0.25), not a block.
        assertThat(f).isNotNull();
        assertThat(f.score()).isEqualTo(0.25);
        assertThat(f.evidence()).containsEntry("over_budget", false);
    }

    @Test
    void exceeding_call_cap_returns_score_one_with_reason_evidence() {
        UUID sid = UUID.randomUUID();
        SessionService svc = sessionServiceWithToken(sid, Map.of(
                "tool_caps", Map.of("email.send", 1),
                "spend_cap_inr", 10_000));
        BudgetTracker tracker = budgetTracker(svc);
        L6CapabilityBudgetScanner scanner = new L6CapabilityBudgetScanner(tracker);

        // First send is within budget.
        ScannerStage.Finding first = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "email.send", Map.of()));
        assertThat(first.score()).isLessThan(1.0);

        // Second send would push used to 2 with cap 1 → BLOCK with score 1.0.
        ScannerStage.Finding second = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "email.send", Map.of()));
        assertThat(second.score()).isEqualTo(1.0);
        assertThat(second.evidence()).containsEntry("over_budget", true);
        assertThat(second.evidence()).containsEntry("tool", "email.send");
        assertThat(second.evidence()).containsEntry("cap", 1);
        assertThat(second.evidence()).containsEntry("used", 2);
        assertThat(second.reason()).contains("Capability budget exceeded");

        // Third attempt: still blocked at same "would-be" usage — ledger was
        // not incremented on the failed call (no phantom consumption).
        ScannerStage.Finding third = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "email.send", Map.of()));
        assertThat(third.score()).isEqualTo(1.0);
        assertThat(third.evidence()).containsEntry("used", 2);
    }

    @Test
    void exceeding_spend_cap_blocks_even_under_call_caps() {
        // Per-tool counts are fine, but the cumulative INR spend goes over.
        UUID sid = UUID.randomUUID();
        SessionService svc = sessionServiceWithToken(sid, Map.of(
                "tool_caps", Map.of("payments.charge", 10),  // generous cap
                "spend_cap_inr", 1_000));                     // tight spend cap
        BudgetTracker tracker = budgetTracker(svc);
        L6CapabilityBudgetScanner scanner = new L6CapabilityBudgetScanner(tracker);

        ScannerStage.Finding ok = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "payments.charge", Map.of("amount", 800)));
        assertThat(ok.score()).isEqualTo(0.0);

        ScannerStage.Finding over = scanner.scan(InspectionInput.OutboundAction.withoutProvenance(
                sid, "payments.charge", Map.of("amount", 800)));
        // 800 + 800 = 1600 > 1000 INR cap → BLOCK.
        assertThat(over.score()).isEqualTo(1.0);
        assertThat(over.evidence()).containsEntry("over_budget", true);
        assertThat(over.reason()).contains("spend cap exceeded");
    }

    @Test
    void inbound_content_is_ignored_by_l6() {
        UUID sid = UUID.randomUUID();
        SessionService svc = sessionServiceWithToken(sid, Map.of());
        BudgetTracker tracker = budgetTracker(svc);
        L6CapabilityBudgetScanner scanner = new L6CapabilityBudgetScanner(tracker);

        boolean shouldRun = scanner.shouldRun(
                new InspectionInput.InboundContent(sid, "browser.goto",
                        "Hotel deals in Bangalore.", Map.of()),
                Map.of());
        assertThat(shouldRun).isFalse();
    }

    // -------------------------------------------------------------------- //

    /** Trivial in-memory repository so the real {@link SessionService} can run. */
    private static final class FakeSessionRepository implements SessionRepository {
        private final Map<UUID, Session> store = new ConcurrentHashMap<>();

        @Override public Session save(Session s) { store.put(s.id(), s); return s; }
        @Override public Optional<Session> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
    }
}
