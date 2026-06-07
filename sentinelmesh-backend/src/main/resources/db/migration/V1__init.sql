-- SentinelMesh — initial schema
-- All ids are UUIDv7 (time-ordered, btree-friendly).
-- JSONB columns use Hibernate's JdbcTypeCode(SqlTypes.JSON).

CREATE TABLE sessions (
    id                  UUID PRIMARY KEY,
    user_id             VARCHAR(128)  NOT NULL,
    goal                TEXT          NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    policy_bundle_id    VARCHAR(128)  NOT NULL,
    capability_token    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ   NOT NULL,
    ended_at            TIMESTAMPTZ
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_created ON sessions(created_at DESC);

CREATE TABLE threats (
    id          UUID PRIMARY KEY,
    session_id  UUID         NOT NULL,
    action_id   UUID,
    category    VARCHAR(64)  NOT NULL,
    severity    VARCHAR(16)  NOT NULL,
    score       NUMERIC(4,3) NOT NULL,
    evidence    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_threats_session  ON threats(session_id);
CREATE INDEX idx_threats_category ON threats(category);

CREATE TABLE approvals (
    id                 UUID PRIMARY KEY,
    session_id         UUID         NOT NULL,
    action_id          UUID,
    requested_payload  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    approver_id        VARCHAR(128),
    decision           VARCHAR(32),
    modified_payload   JSONB,
    blast_radius       NUMERIC(4,3),
    intent             TEXT,
    requested_at       TIMESTAMPTZ  NOT NULL,
    decided_at         TIMESTAMPTZ,
    ttl_at             TIMESTAMPTZ,
    status             VARCHAR(16)  NOT NULL
);
CREATE INDEX idx_approvals_status ON approvals(status);
CREATE INDEX idx_approvals_ttl    ON approvals(ttl_at);

CREATE TABLE audit_events (
    sequence    BIGSERIAL PRIMARY KEY,
    event_id    UUID         NOT NULL,
    session_id  UUID,
    ts          TIMESTAMPTZ  NOT NULL,
    kind        VARCHAR(64)  NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    payload     JSONB        NOT NULL,
    prev_hash   BYTEA        NOT NULL,
    hash        BYTEA        NOT NULL
);
CREATE INDEX idx_audit_session ON audit_events(session_id);
CREATE INDEX idx_audit_ts      ON audit_events(ts DESC);
