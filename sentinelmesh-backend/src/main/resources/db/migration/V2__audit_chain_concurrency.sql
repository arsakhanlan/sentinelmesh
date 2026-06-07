-- SentinelMesh — multi-writer audit chain support.
--
-- The append path now serializes across N backend instances by acquiring
-- pg_advisory_xact_lock(KEY) before reading the prev-hash and inserting a
-- new row. The KEY is hardcoded in HashChainAuditService.AUDIT_CHAIN_LOCK_KEY
-- (currently 0x53454e54494e4c01). This migration is a no-op on the schema
-- side (the lock primitive is built into Postgres), but is committed for
-- two reasons:
--
--   1. Documentation: anyone bringing up a fresh DB sees the contract.
--   2. Future-proofing: when we shard chains per tenant we'll record the
--      key↔chain mapping here.
--
-- We add a covering index that makes the prev-hash lookup (ORDER BY
-- sequence DESC LIMIT 1) constant-time even when the table grows. The
-- existing PRIMARY KEY on `sequence` already supports this, but Postgres
-- benefits from an explicit DESC index for the LIMIT 1 path.

CREATE INDEX IF NOT EXISTS idx_audit_sequence_desc ON audit_events(sequence DESC);
