package com.sentinelmesh.api.rest;

import com.sentinelmesh.api.dto.InspectRequest;
import com.sentinelmesh.api.dto.InspectResponse;
import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.SentinelInspectionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * External Sentinel endpoint. Used by real agents (LangGraph Python service)
 * to ask whether an outbound action / inbound content is safe to proceed.
 *
 * <p>Delegates to {@link SentinelInspectionService} so the REST path, the MCP
 * adapter, and the in-process simulator all run identical logic.
 */
@RestController
@RequestMapping("/api/v1/sentinel")
@Tag(name = "Sentinel", description = "External inspection endpoint for non-Java agents")
public class SentinelInspectController {

    /**
     * Hard cap on inspected content. The LLM-judge scanner is the most expensive
     * stage; bounding inbound size prevents a hostile or buggy agent from running
     * up cost / denying service via gigantic payloads.
     */
    static final int MAX_CONTENT_CHARS = 64_000;

    private final SentinelInspectionService inspection;

    public SentinelInspectController(SentinelInspectionService inspection) {
        this.inspection = inspection;
    }

    @PostMapping("/inspect")
    public InspectResponse inspect(@Valid @RequestBody InspectRequest req) {
        UUID sessionId = req.sessionId() == null ? UuidV7.next() : req.sessionId();
        InspectRequest.Direction dir = req.direction() == null
                ? InspectRequest.Direction.OUTBOUND : req.direction();

        String content = req.content() == null ? "" : req.content();
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "content exceeds " + MAX_CONTENT_CHARS + " chars (got " + content.length() + ")");
        }

        InspectionInput input = (dir == InspectRequest.Direction.INBOUND)
                ? new InspectionInput.InboundContent(sessionId, req.tool(),
                        content,
                        req.meta() == null ? Map.of() : req.meta())
                : new InspectionInput.OutboundAction(sessionId, req.tool(),
                        req.args() == null ? Map.of() : req.args(),
                        blankToNull(req.originActor()),
                        blankToNull(req.currentActor()));

        SentinelInspectionService.Outcome outcome = inspection.inspect(input, req.actionId());
        return InspectResponse.from(sessionId, req.actionId(), outcome.result(), outcome.approvalId());
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
