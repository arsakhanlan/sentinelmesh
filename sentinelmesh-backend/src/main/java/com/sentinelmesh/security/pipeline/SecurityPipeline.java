package com.sentinelmesh.security.pipeline;

import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.domain.model.RiskScore;
import com.sentinelmesh.policy.PolicyDecision;
import com.sentinelmesh.policy.PolicyEngine;
import com.sentinelmesh.security.blast.BlastRadiusEstimator;
import com.sentinelmesh.security.dlp.Redactor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chain-of-responsibility composition of scanner stages with early-exit.
 *
 * <p>Order is intentional: cheapest first, most expensive last. Stages may opt
 * out via {@link ScannerStage#shouldRun}. We always run every stage that opts
 * in — we never short-circuit on a low score — but a saturating high score
 * (>= 0.95) ends the loop because the decision can't get worse.
 *
 * <p><b>Fail-closed.</b> Any exception thrown by a scanner, the aggregator, or
 * the policy engine results in a {@link Decision#BLOCK} — never a silent allow.
 * This enforces the design's core security invariant (§4, invariant #5).
 *
 * <p><b>Observability.</b> Every run is timed into the {@code sentinel.pipeline.detect}
 * Micrometer timer (p50/p95/p99 + histogram), which is what backs the dashboard's
 * detect-latency numbers and the Prometheus/Azure Monitor export.
 */
public final class SecurityPipeline {

    private static final Logger log = LoggerFactory.getLogger(SecurityPipeline.class);

    private final List<ScannerStage> stages;
    private final RiskAggregator aggregator;
    private final PolicyEngine policy;
    private final BlastRadiusEstimator blast;
    private final Redactor redactor;
    private final Timer detectTimer;

    public SecurityPipeline(List<ScannerStage> stages, RiskAggregator aggregator,
                             PolicyEngine policy, BlastRadiusEstimator blast,
                             Redactor redactor, MeterRegistry meterRegistry) {
        this.stages = List.copyOf(stages);
        this.aggregator = aggregator;
        this.policy = policy;
        this.blast = blast;
        this.redactor = redactor;
        this.detectTimer = Timer.builder("sentinel.pipeline.detect")
                .description("Sentinel detect-and-decide latency (scan + aggregate + policy)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public PipelineResult inspect(InspectionInput input) {
        Timer.Sample sample = Timer.start();
        long t0 = System.nanoTime();
        try {
            Map<String, Double> scores = new LinkedHashMap<>();
            Map<String, ScannerStage.Finding> findings = new LinkedHashMap<>();
            for (ScannerStage stage : stages) {
                if (!stage.shouldRun(input, scores)) continue;
                ScannerStage.Finding f;
                try {
                    f = stage.scan(input);
                } catch (Exception ex) {
                    log.error("Scanner {} threw; failing closed (BLOCK): {}", stage.name(), ex.toString());
                    return failClosed(input, t0, "scanner " + stage.name() + " error");
                }
                scores.put(stage.name(), f.score());
                findings.put(stage.name(), f);
                if (f.score() >= 0.95) break;
            }
            double composite = aggregator.aggregate(scores);
            double blastRadius = blast.estimate(input);
            PolicyDecision pd = policy.decide(input, composite, blastRadius, findings);
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Object> rewrittenArgs = null;
            String rewrittenContent = null;
            if (pd.decision() == Decision.REWRITE) {
                if (input instanceof InspectionInput.OutboundAction a && a.args() != null) {
                    rewrittenArgs = redactor.redactArgs(a.args());
                } else if (input instanceof InspectionInput.InboundContent c) {
                    rewrittenContent = redactor.redactText(c.content());
                }
            }
            return new PipelineResult(
                    RiskScore.of(scores, composite), findings,
                    pd.decision(), pd.matchedRule(), pd.reason(),
                    blastRadius, durationMs, rewrittenArgs, rewrittenContent);
        } catch (Exception fatal) {
            log.error("Security pipeline internal error; failing closed (BLOCK).", fatal);
            return failClosed(input, t0, "pipeline internal error");
        } finally {
            sample.stop(detectTimer);
        }
    }

    /** Build a conservative BLOCK result when any part of the pipeline errors. */
    private PipelineResult failClosed(InspectionInput input, long t0, String reason) {
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        double blastRadius;
        try {
            blastRadius = blast.estimate(input);
        } catch (Exception e) {
            blastRadius = 1.0;
        }
        return new PipelineResult(
                RiskScore.of(Map.of("FAILCLOSED", 1.0), 1.0),
                Map.of(), Decision.BLOCK, "fail-closed",
                "Sentinel failed closed: " + reason, blastRadius, durationMs, null, null);
    }
}
