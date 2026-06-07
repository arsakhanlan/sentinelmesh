package com.sentinelmesh.api.rest;

import com.sentinelmesh.domain.port.out.AuditEventSink;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Hash-chained audit log — exportable + verifiable")
public class AuditController {

    private final AuditEventSink audit;

    public AuditController(AuditEventSink audit) { this.audit = audit; }

    @GetMapping("/verify")
    public Map<String, Object> verify() {
        boolean ok = audit.verifyChain();
        return Map.of("chain_intact", ok);
    }

    @GetMapping("/export")
    public List<Map<String, Object>> export() {
        return audit.exportAll().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sequence", e.sequence());
            m.put("event_id", e.eventId());
            m.put("session_id", e.sessionId());
            m.put("ts", e.timestamp());
            m.put("kind", e.kind());
            m.put("actor", e.actor());
            m.put("payload", e.payload());
            m.put("prev_hash", Base64.getEncoder().encodeToString(e.prevHash()));
            m.put("hash", Base64.getEncoder().encodeToString(e.hash()));
            return m;
        }).toList();
    }
}
