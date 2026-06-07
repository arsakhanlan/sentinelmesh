-- Multi-tenant model: orgs, API keys, session stamping.

CREATE TABLE tenants (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(128) NOT NULL UNIQUE,
    daily_tool_caps       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    daily_spend_cap_inr   NUMERIC      NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE tenant_api_keys (
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_hash   VARCHAR(64)  NOT NULL UNIQUE,
    label          VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_api_keys_tenant ON tenant_api_keys(tenant_id);

ALTER TABLE sessions ADD COLUMN tenant_id UUID REFERENCES tenants(id);
CREATE INDEX idx_sessions_tenant ON sessions(tenant_id);

-- Seed tenants (caps tuned for demo: acme tight, globex generous).
INSERT INTO tenants (id, name, daily_tool_caps, daily_spend_cap_inr, created_at) VALUES
(
    'a0000001-0000-4000-8000-000000000001',
    'globex-bookings',
    '{"email.send": 50, "payments.charge": 20, "http.get": 500, "browser.goto": 200, "notes.append": 200}'::jsonb,
    500000,
    NOW()
),
(
    'a0000001-0000-4000-8000-000000000002',
    'acme-travel',
    '{"email.send": 1, "payments.charge": 1, "http.get": 20, "browser.goto": 12, "notes.append": 20}'::jsonb,
    10000,
    NOW()
)
ON CONFLICT (id) DO NOTHING;
