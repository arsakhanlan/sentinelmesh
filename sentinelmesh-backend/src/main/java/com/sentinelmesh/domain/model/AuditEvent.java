package com.sentinelmesh.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only ledger entry. Each row carries a SHA-256 hash chaining back to
 * the previous row, providing tamper-evidence without external infrastructure.
 */
public record AuditEvent(
        long sequence,
        UUID eventId,
        UUID sessionId,
        Instant timestamp,
        String kind,
        String actor,
        Map<String, Object> payload,
        byte[] prevHash,
        byte[] hash
) {}
