package com.sentinelmesh.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Policy(
        UUID id,
        String name,
        int version,
        String yamlSource,
        List<String> ruleSummaries,
        String createdBy,
        Instant createdAt
) {}
