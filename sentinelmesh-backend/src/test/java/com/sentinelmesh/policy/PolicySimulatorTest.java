package com.sentinelmesh.policy;

import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.persistence.entity.AuditEventEntity;
import com.sentinelmesh.persistence.repository.AuditEventJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the load-bearing properties of the policy simulator:
 *
 * <ul>
 *   <li>It compiles a candidate YAML and replays a list of historical
 *       events without throwing.</li>
 *   <li>It correctly identifies a "stricter" bundle as raising more
 *       BLOCKs vs. the recorded ALLOWs.</li>
 *   <li>It identifies a "laxer" bundle as raising more ALLOWs vs. the
 *       recorded BLOCKs.</li>
 *   <li>It collects sample events per change category for the UI.</li>
 *   <li>Invalid YAML produces a clean {@code IllegalArgumentException}
 *       (so the controller maps it to a 400, not a 500).</li>
 * </ul>
 *
 * Uses a hand-rolled in-memory {@link AuditEventJpaRepository} so the test
 * runs without Spring or Postgres.
 */
class PolicySimulatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

    private static AuditEventEntity decisionEvent(long seq, String decision, double risk,
                                                  String tool, boolean overBudget) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("risk", risk);
        payload.put("blast", 0.4);
        payload.put("tool", tool);
        payload.put("has_secret", false);
        payload.put("has_pii", false);
        payload.put("over_budget", overBudget);
        payload.put("rule", "(recorded)");
        AuditEventEntity e = new AuditEventEntity(
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(FIXED),
                "sentinel_decision", "sentinel", payload, new byte[32], new byte[32]);
        try {
            var f = AuditEventEntity.class.getDeclaredField("sequence");
            f.setAccessible(true);
            f.set(e, seq);
        } catch (ReflectiveOperationException ignored) { }
        return e;
    }

    @Test
    void stricter_bundle_flips_some_allows_to_blocks() {
        // 5 historical ALLOWs at risk 0.5, one BLOCK at risk 0.9.
        FakeRepo repo = new FakeRepo(List.of(
                decisionEvent(1, "ALLOW", 0.5, "browser.goto", false),
                decisionEvent(2, "ALLOW", 0.5, "http.get", false),
                decisionEvent(3, "ALLOW", 0.55, "browser.goto", false),
                decisionEvent(4, "ALLOW", 0.55, "http.get", false),
                decisionEvent(5, "ALLOW", 0.6, "browser.goto", false),
                decisionEvent(6, "BLOCK", 0.9, "browser.goto", false)
        ));
        PolicySimulator sim = new PolicySimulator(repo, FIXED);

        // Candidate bundle blocks anything risk >= 0.5 (strict).
        String yaml = """
                name: strict-test
                rules:
                  - name: block-half-plus
                    priority: 1
                    when: "risk >= 0.5"
                    then: BLOCK
                    reason: "test: half-plus is bad"
                  - name: default-allow
                    priority: 100
                    when: "risk < 0.5"
                    then: ALLOW
                    reason: ""
                """;
        PolicySimulator.Result r = sim.simulate(yaml, 24);

        assertThat(r.eventsConsidered()).isEqualTo(6);
        // All 5 ALLOWs flip to BLOCK; the existing BLOCK stays BLOCK.
        assertThat(r.changeCounts()).containsEntry("ALLOW->BLOCK", 5);
        assertThat(r.changeCounts()).doesNotContainKey("BLOCK->ALLOW");
        // Samples bucket exists and is non-empty for the changed category.
        assertThat(r.samples().get("ALLOW->BLOCK")).isNotEmpty();
        assertThat(r.samples().get("ALLOW->BLOCK").get(0).newDecision()).isEqualTo("BLOCK");
    }

    @Test
    void laxer_bundle_flips_some_blocks_to_allows() {
        FakeRepo repo = new FakeRepo(List.of(
                decisionEvent(1, "BLOCK", 0.9, "browser.goto", false),
                decisionEvent(2, "BLOCK", 0.85, "http.get", false),
                decisionEvent(3, "ALLOW", 0.2, "browser.goto", false)
        ));
        PolicySimulator sim = new PolicySimulator(repo, FIXED);
        // Candidate: allow everything (no rule matches → default allow).
        String yaml = """
                name: lax-test
                rules:
                  - name: open-door
                    priority: 1
                    when: "risk >= 0.0"
                    then: ALLOW
                    reason: "test: everything allowed"
                """;
        PolicySimulator.Result r = sim.simulate(yaml, 24);
        assertThat(r.changeCounts()).containsEntry("BLOCK->ALLOW", 2);
    }

    @Test
    void invalid_yaml_throws_illegal_argument() {
        FakeRepo repo = new FakeRepo(List.of());
        PolicySimulator sim = new PolicySimulator(repo, FIXED);
        // Not actually YAML — should fail to parse cleanly.
        assertThatThrownBy(() -> sim.simulate("not yaml: [unclosed", 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Candidate bundle failed to compile");
    }

    @Test
    void over_budget_signal_can_be_simulated_against() {
        // Two events: one within budget (allowed), one over budget (allowed too,
        // simulating "I forgot to add the L6 BLOCK rule").
        // A candidate bundle that adds the L6 BLOCK rule should flip the
        // second event ALLOW → BLOCK.
        FakeRepo repo = new FakeRepo(List.of(
                decisionEvent(1, "ALLOW", 0.1, "email.send", false),
                decisionEvent(2, "ALLOW", 0.1, "email.send", true)
        ));
        PolicySimulator sim = new PolicySimulator(repo, FIXED);
        String yaml = """
                name: l6-fix
                rules:
                  - name: capability-budget
                    priority: 0
                    when: "over_budget == 1"
                    then: BLOCK
                    reason: "test: capability budget"
                  - name: default-allow
                    priority: 100
                    when: "risk < 0.5"
                    then: ALLOW
                    reason: ""
                """;
        PolicySimulator.Result r = sim.simulate(yaml, 24);
        assertThat(r.changeCounts()).containsEntry("ALLOW->BLOCK", 1);
        assertThat(r.samples().get("ALLOW->BLOCK").get(0).newRule()).isEqualTo("capability-budget");
    }

    @Test
    void window_hours_is_clamped_to_safe_range() {
        FakeRepo repo = new FakeRepo(List.of());
        PolicySimulator sim = new PolicySimulator(repo, FIXED);
        String yaml = "name: t\nrules: []\n";
        // Absurd window — should be clamped to the simulator's max.
        PolicySimulator.Result r = sim.simulate(yaml, 100_000);
        assertThat(r.windowHours()).isLessThanOrEqualTo(24 * 7);
    }

    // -------------------------------------------------------------------- //

    /** Repo stub that simply returns whatever we hand it for the recent-by-kind query. */
    private static final class FakeRepo implements AuditEventJpaRepository {
        private final List<AuditEventEntity> rows;
        FakeRepo(List<AuditEventEntity> rows) { this.rows = rows; }

        @Override public List<AuditEventEntity> findRecentByKind(String kind, Instant since, Pageable pageable) {
            return rows;
        }
        @Override public Optional<AuditEventEntity> findTopByOrderBySequenceDesc() { return Optional.empty(); }
        @Override public List<AuditEventEntity> findBySessionIdOrderBySequenceAsc(UUID sessionId) { return List.of(); }
        @Override public void acquireChainLock(long chainKey) { /* no-op for unit tests */ }

        // Unused JpaRepository surface — explicit minimal stubs (cheaper than spinning Spring).
        @Override public List<AuditEventEntity> findAll() { return rows; }
        @Override public List<AuditEventEntity> findAll(Sort sort) { return rows; }
        @Override public Page<AuditEventEntity> findAll(Pageable pageable) { return new PageImpl<>(rows); }
        @Override public List<AuditEventEntity> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public <S extends AuditEventEntity> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>(); entities.forEach(out::add); return out;
        }
        @Override public Optional<AuditEventEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(AuditEventEntity entity) { }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public void deleteAll(Iterable<? extends AuditEventEntity> entities) { }
        @Override public void deleteAll() { }
        @Override public <S extends AuditEventEntity> S save(S entity) { return entity; }
        @Override public void flush() { }
        @Override public <S extends AuditEventEntity> S saveAndFlush(S entity) { return entity; }
        @Override public <S extends AuditEventEntity> List<S> saveAllAndFlush(Iterable<S> entities) {
            return saveAll(entities);
        }
        @Override public void deleteAllInBatch(Iterable<AuditEventEntity> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public void deleteAllInBatch() { }
        @Override public AuditEventEntity getOne(Long id) { return null; }
        @Override public AuditEventEntity getById(Long id) { return null; }
        @Override public AuditEventEntity getReferenceById(Long id) { return null; }
        @Override public <S extends AuditEventEntity> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends AuditEventEntity> List<S> findAll(Example<S> example) { return List.of(); }
        @Override public <S extends AuditEventEntity> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override public <S extends AuditEventEntity> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends AuditEventEntity> long count(Example<S> example) { return 0; }
        @Override public <S extends AuditEventEntity> boolean exists(Example<S> example) { return false; }
        @Override public <S extends AuditEventEntity, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
    }
}
