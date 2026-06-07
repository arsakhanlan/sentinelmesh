package com.sentinelmesh.security.pipeline;

import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.RiskScore;

import java.util.Map;

/**
 * Outcome of one pipeline run.
 *
 * <p>{@code rewrittenArgs} / {@code rewrittenContent} are populated only when
 * {@link Decision#REWRITE} is returned — they carry the sanitized payload the
 * caller should forward in place of the original.
 */
public record PipelineResult(
        RiskScore risk,
        Map<String, ScannerStage.Finding> findings,
        Decision decision,
        String policyMatched,
        String reason,
        double blastRadius,
        long durationMs,
        Map<String, Object> rewrittenArgs,
        String rewrittenContent
) {
    public boolean wasRewritten() {
        return decision == Decision.REWRITE && (rewrittenArgs != null || rewrittenContent != null);
    }
}
