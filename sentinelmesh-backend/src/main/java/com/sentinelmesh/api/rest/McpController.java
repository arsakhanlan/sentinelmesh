package com.sentinelmesh.api.rest;

import com.sentinelmesh.common.util.UuidV7;
import com.sentinelmesh.domain.model.Decision;
import com.sentinelmesh.domain.model.InspectionInput;
import com.sentinelmesh.security.SentinelInspectionService;
import com.sentinelmesh.security.pipeline.PipelineResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model Context Protocol (MCP) adapter — exposes SentinelMesh as an MCP tool so
 * any MCP-speaking agent host (Copilot Studio, Azure AI Foundry, Claude Desktop,
 * Cursor, ...) can put the Sentinel inline with a single tool registration.
 *
 * <p>Implements the JSON-RPC 2.0 surface of MCP over HTTP: {@code initialize},
 * {@code tools/list}, {@code tools/call}. The single tool, {@code sentinel.inspect},
 * runs the full security pipeline (with all side effects) and returns the decision.
 *
 * <p>This turns the project from "our demo" into a drop-in primitive: the same
 * detection depth, reachable by the broader Microsoft agent ecosystem.
 */
@RestController
@RequestMapping("/api/v1/mcp")
@Tag(name = "MCP", description = "Model Context Protocol adapter exposing sentinel.inspect")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String TOOL_NAME = "sentinel.inspect";

    private final SentinelInspectionService inspection;

    public McpController(SentinelInspectionService inspection) {
        this.inspection = inspection;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> rpc(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        try {
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "tools/list" -> ok(id, Map.of("tools", List.of(toolDescriptor())));
                case "tools/call" -> ok(id, callTool(params));
                case "ping" -> ok(id, Map.of());
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException bad) {
            return error(id, -32602, "Invalid params: " + bad.getMessage());
        } catch (Exception ex) {
            log.error("MCP call failed for method={}", method, ex);
            return error(id, -32603, "Internal error: " + ex.getMessage());
        }
    }

    // ---- method handlers ----

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "sentinelmesh", "version", "0.1.0"));
    }

    private Map<String, Object> toolDescriptor() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("direction", Map.of("type", "string", "enum", List.of("OUTBOUND", "INBOUND"),
                "description", "OUTBOUND for a tool call, INBOUND for a tool result / fetched content"));
        props.put("tool", Map.of("type", "string", "description", "Tool name, e.g. email.send or browser.goto"));
        props.put("args", Map.of("type", "object", "description", "Tool arguments (OUTBOUND)"));
        props.put("content", Map.of("type", "string", "description", "Content to scan (INBOUND)"));
        props.put("sessionId", Map.of("type", "string", "description", "Optional session UUID for correlation"));
        props.put("originActor", Map.of("type", "string", "description", "Logical actor that originated the intent (e.g. planner) — enables CAP confused-deputy detection"));
        props.put("currentActor", Map.of("type", "string", "description", "Actor performing the tool call (e.g. executor) — paired with originActor"));
        return Map.of(
                "name", TOOL_NAME,
                "description", "Inspect an agent action or tool result; returns allow/rewrite/"
                        + "require_approval/block/quarantine with a composite risk score.",
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", props,
                        "required", List.of("tool")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        if (!TOOL_NAME.equals(name)) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a
                ? (Map<String, Object>) a : Map.of();

        String tool = args.get("tool") == null ? "" : String.valueOf(args.get("tool"));
        if (tool.isBlank()) throw new IllegalArgumentException("'tool' is required");

        UUID sessionId = args.get("sessionId") != null
                ? UUID.fromString(String.valueOf(args.get("sessionId"))) : UuidV7.next();
        UUID actionId = UuidV7.next();

        boolean inbound = "INBOUND".equalsIgnoreCase(String.valueOf(args.getOrDefault("direction", "OUTBOUND")));
        String originActor = stringOrNull(args.get("originActor"));
        String currentActor = stringOrNull(args.get("currentActor"));
        InspectionInput input = inbound
                ? new InspectionInput.InboundContent(sessionId, tool,
                        args.get("content") == null ? "" : String.valueOf(args.get("content")), Map.of())
                : new InspectionInput.OutboundAction(sessionId, tool,
                        args.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                        originActor, currentActor);

        SentinelInspectionService.Outcome outcome = inspection.inspect(input, actionId);
        PipelineResult r = outcome.result();

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("sessionId", sessionId.toString());
        structured.put("actionId", actionId.toString());
        structured.put("decision", r.decision().name());
        structured.put("compositeRisk", r.risk().composite());
        structured.put("blastRadius", r.blastRadius());
        structured.put("reason", r.reason());
        structured.put("policyMatched", r.policyMatched());
        structured.put("scores", r.risk().scores());
        if (r.rewrittenArgs() != null) structured.put("rewrittenArgs", r.rewrittenArgs());
        if (r.rewrittenContent() != null) structured.put("rewrittenContent", r.rewrittenContent());
        if (outcome.approvalId() != null) structured.put("approvalId", outcome.approvalId().toString());

        boolean isError = r.decision() == Decision.BLOCK || r.decision() == Decision.QUARANTINE;
        String summary = String.format("%s (risk=%.2f) — %s",
                r.decision().name(), r.risk().composite(), r.reason());

        return Map.of(
                "content", List.of(Map.of("type", "text", "text", summary)),
                "structuredContent", structured,
                "isError", isError);
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    // ---- JSON-RPC envelopes ----

    private static Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }
}
