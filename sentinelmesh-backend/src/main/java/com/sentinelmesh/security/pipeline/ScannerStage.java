package com.sentinelmesh.security.pipeline;

import com.sentinelmesh.domain.model.InspectionInput;

import java.util.Map;

/** One layer of the Sentinel pipeline. Stages are composed in fixed order. */
public interface ScannerStage {
    String name();

    /** Should this stage run given the accumulated scores from earlier stages? */
    default boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) { return true; }

    Finding scan(InspectionInput input);

    /** Score in [0,1] plus a short explanation and structured evidence. */
    record Finding(double score, String reason, Map<String, Object> evidence) {
        public static Finding clean(String reason) { return new Finding(0.0, reason, Map.of()); }
    }
}
