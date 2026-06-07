package com.sentinelmesh.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record DecideApprovalRequest(
        @NotBlank String decision,           // "ALLOW" | "BLOCK"
        @NotBlank String approverId,
        Map<String, Object> modifiedPayload
) {}
