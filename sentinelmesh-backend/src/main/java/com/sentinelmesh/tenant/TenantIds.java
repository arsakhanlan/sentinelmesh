package com.sentinelmesh.tenant;

import java.util.UUID;

/** Fixed UUIDs for seed tenants (must match Flyway V4). */
public final class TenantIds {
    public static final UUID GLOBEX = UUID.fromString("a0000001-0000-4000-8000-000000000001");
    public static final UUID ACME = UUID.fromString("a0000001-0000-4000-8000-000000000002");

    private TenantIds() {}
}
