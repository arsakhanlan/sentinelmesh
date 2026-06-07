package com.sentinelmesh.config;

import com.sentinelmesh.policy.PolicyEngine;
import com.sentinelmesh.security.blast.BlastRadiusEstimator;
import com.sentinelmesh.security.dlp.Redactor;
import com.sentinelmesh.security.pipeline.RiskAggregator;
import com.sentinelmesh.security.pipeline.ScannerStage;
import com.sentinelmesh.security.pipeline.SecurityPipeline;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.time.Clock;
import java.util.List;

@Configuration
public class CoreConfig {

    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public RiskAggregator riskAggregator() { return RiskAggregator.defaults(); }

    @Bean
    public SecurityPipeline securityPipeline(List<ScannerStage> stages,
                                              RiskAggregator aggregator,
                                              PolicyEngine policy,
                                              BlastRadiusEstimator blast,
                                              Redactor redactor,
                                              MeterRegistry meterRegistry) {
        // Sort stages by @Order so the pipeline runs cheap → expensive.
        List<ScannerStage> ordered = stages.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        return new SecurityPipeline(ordered, aggregator, policy, blast, redactor, meterRegistry);
    }
}
