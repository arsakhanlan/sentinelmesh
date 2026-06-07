-- SentinelMesh — persistent L7 attack-memory bank.
--
-- Today the bank is in-memory only and reseeds on every boot. This table
-- gives the bank durability: every BLOCKed/QUARANTINEd payload's fingerprint
-- is written here as it's learned, and a backend on startup hydrates the
-- in-memory index from this table.
--
-- Storage choices:
--   * `embedding` is a JSON array of floats. We deliberately avoid pgvector:
--     the cosine math runs in the JVM (the bank is small, ~256 entries cap),
--     and JSON-array storage works on every Postgres install with no
--     extension to manage.
--   * `id` is a UUIDv7 from the application — time-ordered, btree-friendly,
--     also gives us an easy LRU when we evict.
--   * `reason` is the policy rule / category the entry came from
--     ("credential-exfil", "system_role_impersonation", ...).

CREATE TABLE attack_memory (
    id          UUID PRIMARY KEY,
    reason      VARCHAR(128) NOT NULL,
    preview     VARCHAR(256) NOT NULL,
    embedding   TEXT          NOT NULL,
    added_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Hydration walks the table newest-first to load the most recent N up to
-- the in-memory cap; an explicit DESC index keeps that O(N) scan cheap
-- even as the table grows past the cap.
CREATE INDEX idx_attack_memory_added_at_desc ON attack_memory(added_at DESC);
