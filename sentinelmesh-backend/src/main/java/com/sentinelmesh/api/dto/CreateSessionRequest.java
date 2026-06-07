package com.sentinelmesh.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String userId,
        @NotBlank String goal,
        String policyBundleId
) {}
