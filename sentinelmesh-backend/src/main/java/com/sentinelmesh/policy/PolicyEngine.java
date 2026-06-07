package com.sentinelmesh.policy;

import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the active policy bundle at startup and evaluates inspection results
 * against it. First-match-wins by priority.
 *
 * <p>Default policy lives at {@code classpath:policies/default-bundle.yml};
 * override with {@code sentinelmesh.policy.bundle} property.
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final ResourceLoader resourceLoader;
    private final String bundleLocation;
    private final AtomicReference<List<Rule>> rules = new AtomicReference<>(List.of());
    private final RuleExpressionEvaluator eval = new RuleExpressionEvaluator();

    public PolicyEngine(ResourceLoader resourceLoader,
                        @Value("${sentinelmesh.policy.bundle:classpath:policies/default-bundle.yml}") String bundleLocation) {
        this.resourceLoader = resourceLoader;
        this.bundleLocation = bundleLocation;
    }

    @PostConstruct
    public void load() {
        Resource r = resourceLoader.getResource(bundleLocation);
        try (InputStream is = r.getInputStream()) {
            List<Rule> compiled = new PolicyCompiler().compile(is);
            rules.set(compiled);
            log.info("Loaded policy bundle from {} with {} rules", bundleLocation, compiled.size());
        } catch (Exception e) {
            log.error("Failed to load policy bundle from {}, falling back to fail-closed defaults", bundleLocation, e);
            rules.set(List.of(
                    new Rule("fail-closed-block-high-risk", 1,
                            "risk >= 0.7", Decision.BLOCK,
                            "fallback: high risk score blocked")));
        }
    }

    public PolicyDecision decide(InspectionInput input, double risk, double blast,
                                 Map<String, ScannerStage.Finding> findings) {
        Map<String, Object> ctx = buildContext(input, risk, blast, findings);
        for (Rule r : rules.get()) {
            try {
                if (eval.eval(r.whenExpr(), ctx)) {
                    return new PolicyDecision(r.then(), r.name(), r.reason());
                }
            } catch (Exception ex) {
                log.warn("Rule {} threw: {}; skipping", r.name(), ex.getMessage());
            }
        }
        return PolicyDecision.allow("no rule matched");
    }

    private Map<String, Object> buildContext(InspectionInput input, double risk, double blast,
                                             Map<String, ScannerStage.Finding> findings) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("risk", risk);
        ctx.put("blast", blast);
        ctx.put("tool", input.tool() == null ? "" : input.tool());
        boolean hasSecret = false, hasPii = false;
        ScannerStage.Finding dlp = findings.get("DLP");
        if (dlp != null && dlp.evidence() != null) {
            hasSecret = dlp.evidence().containsKey("secrets");
            hasPii    = dlp.evidence().containsKey("pii");
        }
        ctx.put("has_secret", hasSecret);
        ctx.put("has_pii", hasPii);

        // L6 budget signal — true when the capability token would be exceeded
        // by this call. Wired here so YAML rules can match on `over_budget == 1`.
        boolean overBudget = false;
        boolean tenantOverBudget = false;
        ScannerStage.Finding l6 = findings.get("L6");
        if (l6 != null && l6.evidence() != null) {
            Object flag = l6.evidence().get("over_budget");
            overBudget = (flag instanceof Boolean b && b)
                    || (flag instanceof Number n && n.intValue() != 0);
            if (overBudget && "TENANT".equals(String.valueOf(l6.evidence().get("budget_scope")))) {
                tenantOverBudget = true;
            }
        }
        ctx.put("over_budget", overBudget);
        ctx.put("tenant_over_budget", tenantOverBudget);

        // L7 attack-memory signal — true when this payload matches a known
        // attack at high similarity. Exposed so YAML rules can pin a
        // BLOCK on `known_attack == 1` even when composite risk is borderline.
        boolean knownAttack = false;
        ScannerStage.Finding l7 = findings.get("L7");
        if (l7 != null && l7.evidence() != null && l7.evidence().containsKey("attack_id")) {
            // L7 uses score 0.95 for at-threshold matches; warn-tier near-misses stay low.
            knownAttack = l7.score() >= 0.9;
        }
        ctx.put("known_attack", knownAttack);

        boolean capabilityEscalation = false;
        ScannerStage.Finding cap = findings.get("CAP");
        if (cap != null && cap.score() >= 0.9) {
            capabilityEscalation = true;
        }
        ctx.put("capability_escalation", capabilityEscalation);
        return ctx;
    }

    public int ruleCount() { return rules.get().size(); }
    public List<Rule> rules() { return rules.get(); }
}
