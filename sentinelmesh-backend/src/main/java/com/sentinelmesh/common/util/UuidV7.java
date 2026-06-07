package com.sentinelmesh.common.util;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/** Time-ordered UUIDv7 generator. Btree-friendly, naturally sortable. */
public final class UuidV7 {
    private UuidV7() {}
    public static UUID next() { return UuidCreator.getTimeOrderedEpoch(); }
}
