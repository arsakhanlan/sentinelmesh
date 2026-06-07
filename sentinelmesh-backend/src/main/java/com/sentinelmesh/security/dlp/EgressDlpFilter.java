package com.sentinelmesh.security.dlp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.pipeline.ScannerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Egress data-loss filter. Treats every outbound action argument as a
 * potential leak vector and scores it for secret + PII content.
 *
 * <p>Note: this only scores; the policy engine decides whether to BLOCK,
 * REWRITE (redacted payload), or ALLOW.
 */
@Component
@Order(60)
public class EgressDlpFilter implements ScannerStage {

    private static final Logger log = LoggerFactory.getLogger(EgressDlpFilter.class);

    private final SecretPatternRegistry secrets;
    private final PiiPatternRegistry pii;
    private final ObjectMapper mapper;

    public EgressDlpFilter(SecretPatternRegistry secrets, PiiPatternRegistry pii, ObjectMapper mapper) {
        this.secrets = secrets;
        this.pii = pii;
        this.mapper = mapper;
    }

    @Override public String name() { return "DLP"; }

    /**
     * Argument keys whose value is an *addressing* field (recipient, sender,
     * vendor) — present by design on every transactional outbound action and
     * therefore not a leak signal in itself. Counting "user@example.com" in
     * {@code email.send.to} as PII makes every legitimate confirmation email
     * trip the egress filter, which then masks real exfil signal in the
     * dashboards. Body / subject / memo are still scanned in full.
     */
    private static final java.util.Set<String> ADDRESS_KEYS = java.util.Set.of(
            "to", "from", "cc", "bcc", "recipient", "recipients", "sender", "vendor");

    @Override
    public boolean shouldRun(InspectionInput input, Map<String, Double> scoresSoFar) {
        return input instanceof InspectionInput.OutboundAction;
    }

    @Override
    public Finding scan(InspectionInput input) {
        InspectionInput.OutboundAction a = (InspectionInput.OutboundAction) input;
        if (a.args() == null || a.args().isEmpty()) return Finding.clean("no args");
        // JSON-serialise only the *content* fields. We intentionally drop the
        // addressing fields so a legitimate booking email doesn't get marked
        // PII-bearing for carrying its own recipient address.
        String flat = flattenContentOnly(a.args());
        List<SecretPatternRegistry.Match> secretsHit = secrets.find(flat);
        List<PiiPatternRegistry.Match> piiHit = pii.find(flat);
        if (secretsHit.isEmpty() && piiHit.isEmpty()) return Finding.clean("no leaks");

        double score = 0.0;
        if (!secretsHit.isEmpty()) score = Math.max(score, 0.85);
        if (!piiHit.isEmpty()) score = Math.max(score, 0.45);

        Map<String, Object> ev = new HashMap<>();
        if (!secretsHit.isEmpty()) ev.put("secrets", secretsHit);
        if (!piiHit.isEmpty())     ev.put("pii", piiHit);
        return new Finding(score, "DLP egress: secrets=" + secretsHit.size()
                + " pii=" + piiHit.size(), ev);
    }

    private String flattenContentOnly(Map<String, Object> args) {
        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            if (ADDRESS_KEYS.contains(key)) continue;
            filtered.put(e.getKey(), e.getValue());
        }
        try {
            return mapper.writeValueAsString(filtered);
        } catch (JsonProcessingException ex) {
            log.warn("DLP JSON flatten failed; falling back to toString: {}", ex.toString());
            return filtered.toString();
        }
    }
}
