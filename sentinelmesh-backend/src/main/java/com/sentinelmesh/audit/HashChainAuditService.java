package com.sentinelmesh.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.common.error.SentinelMeshException;
import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.AuditEvent;
import com.sentinelmesh.domain.port.out.AuditEventSink;
import com.sentinelmesh.persistence.entity.AuditEventEntity;
import com.sentinelmesh.persistence.mapper.EntityMappers;
import com.sentinelmesh.persistence.repository.AuditEventJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log with SHA-256 hash chaining: each row hashes the
 * previous row's hash concatenated with the canonical JSON of the new payload.
 *
 * <p>This gives tamper-evidence without an external ledger — the chain can be
 * exported and verified by anyone in O(n).
 *
 * <p><b>Multi-writer correctness.</b> Every {@code append} acquires a
 * Postgres transaction-scoped advisory lock ({@code pg_advisory_xact_lock})
 * keyed off a stable chain identifier <em>before</em> reading the prev-hash
 * and inserting the new row. This means N backend instances can hammer the
 * chain in parallel and Postgres will serialize them at the lock, keeping
 * the chain unbroken. The lock auto-releases on {@code COMMIT} or
 * {@code ROLLBACK}, so a crashed appender can never leak it.
 *
 * <p>The in-process {@code synchronized} keyword stays as defense-in-depth
 * for the single-instance dev/test flow (and for non-Postgres dialects, e.g.
 * the legacy unit-test path that uses no DB at all). The advisory lock is
 * the primitive that actually buys horizontal scale.
 */
@Component
public class HashChainAuditService implements AuditEventSink {

    private static final Logger log = LoggerFactory.getLogger(HashChainAuditService.class);
    private static final byte[] GENESIS_HASH = new byte[32];

    /**
     * Stable advisory-lock key for THE audit chain. We have one global chain;
     * if we ever shard chains by tenant we'd derive one of these per chain.
     * The literal value doesn't matter — only that every backend instance
     * uses the same one.
     */
    private static final long AUDIT_CHAIN_LOCK_KEY = 0x53454e54494e4c01L; // "SENTINL\x01"

    private final AuditEventJpaRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean advisoryLockSupported;

    public HashChainAuditService(AuditEventJpaRepository repo, ObjectMapper mapper, Clock clock,
                                  Environment env) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
        // Only call the Postgres-specific advisory lock when we know we're
        // running on Postgres. Detection is cheap and conservative: if the
        // dialect string mentions postgres OR the JDBC URL is jdbc:postgresql,
        // we trust it. Anything else (H2 unit-test paths, embedded modes)
        // falls back to in-process synchronization only.
        String dialect = String.valueOf(env.getProperty("spring.jpa.properties.hibernate.dialect", ""));
        String url = String.valueOf(env.getProperty("spring.datasource.url", ""));
        this.advisoryLockSupported = dialect.toLowerCase().contains("postgres")
                || url.toLowerCase().contains("postgres");
        log.info("HashChainAuditService advisory_lock_supported={}", advisoryLockSupported);
    }

    @Override
    @Transactional
    public synchronized AuditEvent append(String kind, String actor, UUID sessionId,
                                          Map<String, Object> payload) {
        // Acquire the cross-instance lock FIRST. If two instances race here,
        // one will block on the lock until the other commits. Read-then-write
        // strictly inside the lock window keeps the prev-hash <-> insert
        // step atomic across writers.
        if (advisoryLockSupported) {
            repo.acquireChainLock(AUDIT_CHAIN_LOCK_KEY);
        }
        byte[] prev = repo.findLatest().map(AuditEventEntity::getHash).orElse(GENESIS_HASH);
        byte[] payloadBytes = canonical(payload);
        byte[] hash = sha256(prev, payloadBytes);
        AuditEventEntity e = new AuditEventEntity(
                UuidV7.next(), sessionId, clock.instant(), kind, actor, payload, prev, hash);
        AuditEventEntity saved = repo.save(e);
        return EntityMappers.toDomain(saved);
    }

    @Override
    public List<AuditEvent> exportAll() {
        return repo.findAll(org.springframework.data.domain.Sort.by("sequence")).stream()
                .map(EntityMappers::toDomain).toList();
    }

    @Override
    public List<AuditEvent> exportForSession(UUID sessionId) {
        if (sessionId == null) return List.of();
        return repo.findBySessionIdOrderBySequenceAsc(sessionId).stream()
                .map(EntityMappers::toDomain).toList();
    }

    @Override
    public boolean verifySession(UUID sessionId) {
        if (sessionId == null) return true;
        // Verify each session row individually: stored hash must equal
        // SHA-256(prev_hash || canonical(payload)). This detects payload
        // tampering even when the surrounding chain (other sessions) is
        // unchanged. The global chain link is checked by verifyChain().
        for (AuditEventEntity e : repo.findBySessionIdOrderBySequenceAsc(sessionId)) {
            byte[] expected = sha256(e.getPrevHash(), canonical(e.getPayload()));
            if (!java.util.Arrays.equals(e.getHash(), expected)) {
                log.error("Per-session chain break at seq={} session={}", e.getSequence(), sessionId);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean verifyChain() {
        byte[] prev = GENESIS_HASH;
        for (AuditEventEntity e : repo.findAll(org.springframework.data.domain.Sort.by("sequence"))) {
            if (!java.util.Arrays.equals(e.getPrevHash(), prev)) {
                log.error("Audit chain break at seq={} (prev_hash mismatch)", e.getSequence());
                return false;
            }
            byte[] expected = sha256(prev, canonical(e.getPayload()));
            if (!java.util.Arrays.equals(e.getHash(), expected)) {
                log.error("Audit chain break at seq={} (hash mismatch)", e.getSequence());
                return false;
            }
            prev = e.getHash();
        }
        return true;
    }

    private byte[] canonical(Map<String, Object> payload) {
        try {
            // Sorted keys → deterministic bytes regardless of map ordering.
            return mapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            throw new SentinelMeshException("audit payload serialization failed", ex);
        }
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new SentinelMeshException("SHA-256 unavailable", e);
        }
    }
}
