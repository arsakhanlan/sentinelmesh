package com.sentinelmesh.api.rest;

import com.sentinelmesh.api.dto.ApprovalResponse;
import com.sentinelmesh.api.dto.DecideApprovalRequest;
import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.port.in.DecideApprovalUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Human-in-the-loop approval queue")
public class ApprovalController {

    private final DecideApprovalUseCase approvals;

    public ApprovalController(DecideApprovalUseCase approvals) { this.approvals = approvals; }

    @GetMapping
    public List<ApprovalResponse> list(
            @org.springframework.web.bind.annotation.RequestParam(value = "sessionId", required = false)
            UUID sessionId) {
        var stream = approvals.pending().stream();
        if (sessionId != null) {
            stream = stream.filter(a -> sessionId.equals(a.sessionId()));
        }
        return stream.map(ApprovalResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ApprovalResponse get(@PathVariable UUID id) {
        return ApprovalResponse.from(approvals.get(id));
    }

    @PostMapping("/{id}/decide")
    public ApprovalResponse decide(@PathVariable UUID id,
                                    @Valid @RequestBody DecideApprovalRequest req) {
        Decision d = parseDecision(req.decision());
        return ApprovalResponse.from(
                approvals.decide(id, d, req.approverId(), req.modifiedPayload()));
    }

    /**
     * Accept human-friendly synonyms — APPROVED / APPROVE / DENIED / DENY /
     * MODIFY — alongside the canonical Decision enum names so SOC operators,
     * scripts and the dashboard can all use the verb that reads naturally.
     */
    static Decision parseDecision(String raw) {
        if (raw == null) throw new IllegalArgumentException("decision required");
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "APPROVE", "APPROVED", "ACCEPT", "ACCEPTED", "OK"      -> Decision.ALLOW;
            case "DENY", "DENIED", "REJECT", "REJECTED"                  -> Decision.BLOCK;
            case "MODIFY", "MODIFIED", "AMEND", "AMENDED"                 -> Decision.REWRITE;
            default                                                       -> Decision.valueOf(s);
        };
    }
}
