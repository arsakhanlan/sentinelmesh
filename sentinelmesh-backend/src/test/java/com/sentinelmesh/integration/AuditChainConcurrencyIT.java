package com.sentinelmesh.integration;

import com.sentinelmesh.domain.model.AuditEvent;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.persistence.repository.AuditEventJpaRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-writer audit chain integration test.
 *
 * <p><b>Contract under test:</b> N concurrent threads (simulating N backend
 * instances) can call {@link AuditEventSink#append} in any interleaving and
 * the resulting hash chain stays unbroken.
 *
 * <p>How we test it:
 * <ol>
 *   <li>Spin up <b>two independent fixed thread pools</b> against the same
 *       Postgres-backed audit sink. Each pool acts as a separate "instance"
 *       — each thread holds its own JDBC connection (Hikari sized so one
 *       thread = one connection during the test) and runs its append in
 *       its own transaction.</li>
 *   <li>Each pool fires {@link #APPENDS_PER_POOL} appends across
 *       {@link #POOL_SIZE} threads. Total = 2 × POOL_SIZE × APPENDS.</li>
 *   <li>Afterwards we call {@code verifyChain()}: every row's
 *       {@code hash == sha256(prev_hash || canonical(payload))} and every
 *       row's {@code prev_hash} equals its predecessor's {@code hash}.</li>
 *   <li>We assert no duplicate event-ids and the row count went up by
 *       exactly the number of appends.</li>
 * </ol>
 *
 * <p>Without the {@code pg_advisory_xact_lock} primitive in
 * {@code HashChainAuditService.append}, two concurrent appenders would both
 * read the same {@code prev_hash} and write rows with conflicting hash
 * pointers — the test would fail at {@code verifyChain}.
 *
 * <p><b>Environment.</b> This test targets a Postgres at
 * {@code localhost:5432} with the credentials in
 * {@code application-concurrency.yml}. The dev compose stack provides
 * exactly this. If Postgres isn't reachable on that port, the test is
 * <em>skipped</em> (Assumption fail) so it never red-lights other suites.
 */
@SpringBootTest
@ActiveProfiles("concurrency")
class AuditChainConcurrencyIT {

    /** Per-pool thread count — each thread is a concurrent appender. */
    private static final int POOL_SIZE = 6;
    /** Per pool, how many appends to fire. */
    private static final int APPENDS_PER_POOL = 100;
    /** Two pools => simulates two backend instances racing on one chain. */
    private static final int POOLS = 2;
    private static final int TOTAL_APPENDS = POOLS * POOL_SIZE * APPENDS_PER_POOL;

    @BeforeAll
    static void requirePostgresOnLocalhost() {
        // Skip cleanly when the dev Postgres isn't up. We don't want this
        // test to flake on a developer machine where compose isn't running.
        try (Socket s = new Socket("localhost", 5432)) {
            assumeTrue(s.isConnected(), "Postgres on localhost:5432 not reachable");
        } catch (Exception e) {
            assumeTrue(false, "Postgres on localhost:5432 not reachable: " + e.getMessage());
        }
    }

    @Autowired private AuditEventSink audit;
    @Autowired private AuditEventJpaRepository repo;

    @Test
    void chain_stays_intact_under_concurrent_multi_instance_appends() throws Exception {
        long sizeBefore = repo.count();

        AtomicInteger totalAppended = new AtomicInteger();
        ExecutorService pool1 = Executors.newFixedThreadPool(POOL_SIZE);
        ExecutorService pool2 = Executors.newFixedThreadPool(POOL_SIZE);

        try {
            for (int p = 0; p < POOLS; p++) {
                ExecutorService pool = (p == 0) ? pool1 : pool2;
                final String instance = "instance-" + p;
                for (int i = 0; i < POOL_SIZE * APPENDS_PER_POOL; i++) {
                    final int n = i;
                    pool.submit(() -> {
                        // Random nonce makes every payload bytewise unique, so
                        // identical prev_hashes can't accidentally yield identical
                        // rows — any chain break is a real chain break.
                        Map<String, Object> payload = Map.of(
                                "instance", instance,
                                "n", n,
                                "nonce", UUID.randomUUID().toString());
                        audit.append("test_concurrency", instance, null, payload);
                        totalAppended.incrementAndGet();
                    });
                }
            }
        } finally {
            pool1.shutdown();
            pool2.shutdown();
            assertThat(pool1.awaitTermination(180, TimeUnit.SECONDS))
                    .as("pool1 finished within 3 minutes").isTrue();
            assertThat(pool2.awaitTermination(180, TimeUnit.SECONDS))
                    .as("pool2 finished within 3 minutes").isTrue();
        }

        assertThat(totalAppended.get()).isEqualTo(TOTAL_APPENDS);

        // Property 1: hash chain integrity (THE load-bearing assertion).
        assertThat(audit.verifyChain())
                .as("verifyChain() must return true after %d concurrent appends", TOTAL_APPENDS)
                .isTrue();

        // Property 2: row count grew by exactly the number we appended.
        assertThat(repo.count() - sizeBefore).isEqualTo(TOTAL_APPENDS);

        // Property 3: no duplicate event ids (every append got its own row).
        List<AuditEvent> chain = audit.exportAll();
        Set<UUID> eventIds = new HashSet<>();
        for (AuditEvent e : chain) eventIds.add(e.eventId());
        assertThat(eventIds).hasSize(chain.size());
    }
}
