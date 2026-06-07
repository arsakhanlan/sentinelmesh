package com.sentinelmesh.api.dto;

import com.sentinelmesh.security.pipeline.PipelineResult;
import com.sentinelmesh.security.pipeline.ScannerStage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record InspectResponse(
        UUID sessionId,
        UUID actionId,
        String decision,
        String reason,
        String policyMatched,
        double compositeRisk,
        double blastRadius,
        Map<String, Double> scores,
        Map<String, Object> findings,
        UUID approvalId,
        long durationMs,
        Map<String, Object> rewrittenArgs,
        String rewrittenContent
) {
    public static InspectResponse from(UUID sessionId, UUID actionId,
                                        PipelineResult r, UUID approvalId) {
        Map<String, Object> findings = new LinkedHashMap<>();
        for (var e : r.findings().entrySet()) {
            ScannerStage.Finding f = e.getValue();
            findings.put(e.getKey(), Map.of(
                    "score", f.score(),
                    "reason", f.reason(),
                    "evidence", f.evidence() == null ? Map.of() : f.evidence()));
        }
        return new InspectResponse(
                sessionId, actionId,
                r.decision().name(), r.reason(), r.policyMatched(),
                r.risk().composite(), r.blastRadius(),
                r.risk().scores(), findings, approvalId, r.durationMs(),
                r.rewrittenArgs(), r.rewrittenContent());
    }
}
