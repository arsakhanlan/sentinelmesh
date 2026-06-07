package com.sentinelmesh.persistence.repository;

import com.sentinelmesh.persistence.entity.AuditEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, Long> {

    /** Most recent (highest-sequence) entry. */
    Optional<AuditEventEntity> findTopByOrderBySequenceDesc();

    /** All audit entries for one session, ordered by sequence (== insertion order). */
    List<AuditEventEntity> findBySessionIdOrderBySequenceAsc(UUID sessionId);

    /** Bridge to keep the audit service's call site clean. */
    default Optional<AuditEventEntity> findLatest() { return findTopByOrderBySequenceDesc(); }

    /**
     * Pull audit events of a given kind whose timestamp falls inside a window.
     * Used by the policy simulator to replay recent {@code sentinel_decision}
     * events through a candidate bundle. Capped via a Pageable to avoid
     * accidentally reading a multi-day table into memory.
     */
    @Query("SELECT a FROM AuditEventEntity a WHERE a.kind = :kind AND a.ts >= :since ORDER BY a.sequence DESC")
    List<AuditEventEntity> findRecentByKind(@Param("kind") String kind,
                                            @Param("since") Instant since,
                                            Pageable pageable);

    /**
     * Acquire a transaction-scoped advisory lock for the audit chain.
     *
     * <p>This is the load-bearing primitive that lets multiple backend
     * instances append to the same chain without breaking it. Every appender
     * acquires the same {@code chainKey} before reading the prev-hash and
     * inserting; Postgres serializes them on the lock. The lock is
     * automatically released on {@code COMMIT} or {@code ROLLBACK} — no
     * cleanup leak risk on crash.
     *
     * <p>Why advisory locks vs. {@code SELECT FOR UPDATE} on a head row:
     * advisory locks don't touch a row (no MVCC bloat), don't bloat WAL,
     * and don't require a "head" row to exist. Why advisory locks vs.
     * {@code SERIALIZABLE} isolation: advisory locks give us a single
     * predictable serialization point for *one* operation (chain append)
     * without forcing every other transaction in the app to pay the cost.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:chainKey)", nativeQuery = true)
    void acquireChainLock(@Param("chainKey") long chainKey);

    /** Count all sentinel_decision events for live ASR computation. */
    @Query(value = """
            SELECT COUNT(*) FROM audit_events
            WHERE kind = 'sentinel_decision'
            """, nativeQuery = true)
    long countSentinelDecisions();

    /** Count sentinel_decision events where the outcome was not ALLOW. */
    @Query(value = """
            SELECT COUNT(*) FROM audit_events
            WHERE kind = 'sentinel_decision'
              AND payload->>'decision' != 'ALLOW'
            """, nativeQuery = true)
    long countSentinelDecisionsBlocked();

}
