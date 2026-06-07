package com.sentinelmesh.domain.model;

/** Outcome of the Sentinel pipeline for a single action or content blob. */
public enum Decision {
    /** Action proceeds untouched. */
    ALLOW,
    /** Action proceeds but the payload was sanitized (e.g. secrets redacted). */
    REWRITE,
    /** Action paused; human approval required to resume. */
    REQUIRE_APPROVAL,
    /** Action blocked; agent receives a structured rejection. */
    BLOCK,
    /** Session frozen for forensic inspection. */
    QUARANTINE
}
