"""OpenTelemetry / Foundry tracing emission for SentinelMesh decisions.

Microsoft Agent Framework, Foundry Agent Service, and Foundry's Trace
viewer all use the **`gen_ai`** OpenTelemetry semantic conventions: every
agent run produces an ``invoke_agent`` span with ``execute_tool`` child
spans for each tool call, with attributes named ``gen_ai.system``,
``gen_ai.tool.name``, ``gen_ai.request.model``, etc.

When SentinelMesh decisions are emitted as **child spans** of the same
``execute_tool`` span, they show up natively in the Foundry trace
explorer next to every governed call — no custom dashboard required.

This module is a thin OTel layer:

* ``configure_tracing(service_name=..., otlp_endpoint=...)`` — one-shot
  initialisation. Reads ``OTEL_EXPORTER_OTLP_ENDPOINT`` from env if no
  endpoint is supplied. When OTel isn't installed, it logs a hint and
  no-ops so production code can call it unconditionally.

* ``InstrumentedSentinelClient`` — wraps an existing ``SentinelClient`` so
  every ``inspect_outbound`` / ``inspect_inbound`` call produces a span
  with the canonical attribute names plus SentinelMesh-specific ones
  (``sentinelmesh.decision``, ``sentinelmesh.composite_risk``,
  ``sentinelmesh.scanner.<name>``, ...). Inherits from a host-supplied
  client so callers can swap it in without changing their existing code.

The OTel imports are **lazy** — the module file imports cleanly without
``opentelemetry-sdk`` installed, and ``configure_tracing`` will tell the
operator how to install it. This matches the SentinelMesh policy of
"every Microsoft integration is opt-in".

Reference:
* https://learn.microsoft.com/agent-framework/agents/observability
* https://opentelemetry.io/docs/specs/semconv/gen-ai/
"""

from __future__ import annotations

import logging
import os
from typing import Any, TYPE_CHECKING
from uuid import UUID

from sentinelmesh_agents.sentinel.client import InspectResult, SentinelClient

if TYPE_CHECKING:  # pragma: no cover — typing only
    from opentelemetry.trace import Tracer  # type: ignore

log = logging.getLogger(__name__)

_TRACER: "Tracer | None" = None
_SERVICE_NAME = "sentinelmesh-agents"


def configure_tracing(
    *,
    service_name: str = _SERVICE_NAME,
    otlp_endpoint: str | None = None,
    insecure: bool = True,
) -> bool:
    """Initialise OpenTelemetry with an OTLP gRPC exporter.

    When ``otlp_endpoint`` is None, falls back to the standard
    ``OTEL_EXPORTER_OTLP_ENDPOINT`` environment variable. Returns ``True``
    when tracing was configured, ``False`` (with a one-time log line) when
    the SDK isn't installed or the endpoint is missing — callers should
    treat tracing as best-effort and continue regardless.

    The exporter targets gRPC by default; set ``otlp_endpoint`` to an
    HTTP URL plus ``insecure=False`` for HTTPS transport. For Foundry,
    point the endpoint at the project's tracing collector (see the
    Foundry Trace integration docs).
    """
    global _TRACER

    try:
        from opentelemetry import trace  # type: ignore
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # type: ignore
            OTLPSpanExporter,
        )
        from opentelemetry.sdk.resources import SERVICE_NAME, Resource  # type: ignore
        from opentelemetry.sdk.trace import TracerProvider  # type: ignore
        from opentelemetry.sdk.trace.export import BatchSpanProcessor  # type: ignore
    except ImportError:
        log.info(
            "OpenTelemetry SDK not installed; skipping tracing configuration. "
            "Install with `pip install -e .[otel]` to enable Foundry trace emission."
        )
        return False

    endpoint = otlp_endpoint or os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not endpoint:
        log.info(
            "No OTLP endpoint configured (set OTEL_EXPORTER_OTLP_ENDPOINT or pass "
            "otlp_endpoint=). Skipping tracing — Sentinel decisions will not be "
            "emitted as spans."
        )
        return False

    resource = Resource.create({SERVICE_NAME: service_name})
    provider = TracerProvider(resource=resource)
    exporter = OTLPSpanExporter(endpoint=endpoint, insecure=insecure)
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    _TRACER = trace.get_tracer("sentinelmesh.security")
    log.info("OTel tracing configured (service=%s, endpoint=%s)", service_name, endpoint)
    return True


def _tracer():
    """Resolve the configured tracer. Returns ``None`` until
    ``configure_tracing`` has been called *and* succeeded."""
    return _TRACER


# ---------- semantic-convention attribute names ----------

# Standard gen_ai conventions: https://opentelemetry.io/docs/specs/semconv/gen-ai/
GEN_AI_SYSTEM = "gen_ai.system"
GEN_AI_OPERATION_NAME = "gen_ai.operation.name"
GEN_AI_TOOL_NAME = "gen_ai.tool.name"
GEN_AI_TOOL_TYPE = "gen_ai.tool.type"

# SentinelMesh-specific. Prefixed with `sentinelmesh.` so they're easy to
# filter / colour in the Foundry trace explorer.
SM_DECISION = "sentinelmesh.decision"
SM_REASON = "sentinelmesh.reason"
SM_COMPOSITE_RISK = "sentinelmesh.composite_risk"
SM_BLAST_RADIUS = "sentinelmesh.blast_radius"
SM_SESSION_ID = "sentinelmesh.session_id"
SM_ACTION_ID = "sentinelmesh.action_id"
SM_DIRECTION = "sentinelmesh.direction"
SM_SCANNER_PREFIX = "sentinelmesh.scanner."  # appended with layer name (L1, L2, ...)


class InstrumentedSentinelClient(SentinelClient):
    """A drop-in replacement for ``SentinelClient`` that emits OTel spans.

    Every ``inspect_outbound`` / ``inspect_inbound`` becomes one span tagged
    with the gen_ai.execute_tool semantic convention plus SentinelMesh's
    decision metadata. Span status is set to ERROR when the policy engine
    BLOCKs / QUARANTINEs so the Foundry trace viewer flags it red.

    All other methods inherit from ``SentinelClient`` unchanged.
    """

    async def inspect_outbound(  # type: ignore[override]
        self, session_id: UUID, action_id: UUID, tool: str, args: dict[str, Any],
        *, origin_actor: str | None = None, current_actor: str | None = None,
    ) -> InspectResult:
        tracer = _tracer()
        if tracer is None:
            return await super().inspect_outbound(
                session_id, action_id, tool, args,
                origin_actor=origin_actor, current_actor=current_actor,
            )

        with tracer.start_as_current_span(f"sentinel.inspect.execute_tool {tool}") as span:
            _set_common_attrs(span, session_id, action_id, tool, "OUTBOUND")
            verdict = await super().inspect_outbound(
                session_id, action_id, tool, args,
                origin_actor=origin_actor, current_actor=current_actor,
            )
            _set_verdict_attrs(span, verdict)
            return verdict

    async def inspect_inbound(  # type: ignore[override]
        self, session_id: UUID, action_id: UUID, tool: str, content: str,
        meta: dict[str, Any] | None = None,
    ) -> InspectResult:
        tracer = _tracer()
        if tracer is None:
            return await super().inspect_inbound(
                session_id, action_id, tool, content, meta,
            )
        with tracer.start_as_current_span(f"sentinel.inspect.tool_result {tool}") as span:
            _set_common_attrs(span, session_id, action_id, tool, "INBOUND")
            verdict = await super().inspect_inbound(
                session_id, action_id, tool, content, meta,
            )
            _set_verdict_attrs(span, verdict)
            return verdict


def _set_common_attrs(span, session_id: UUID, action_id: UUID,
                      tool: str, direction: str) -> None:
    span.set_attribute(GEN_AI_SYSTEM, "sentinelmesh")
    span.set_attribute(GEN_AI_OPERATION_NAME, "execute_tool")
    span.set_attribute(GEN_AI_TOOL_NAME, tool)
    span.set_attribute(GEN_AI_TOOL_TYPE, "function")
    span.set_attribute(SM_SESSION_ID, str(session_id))
    span.set_attribute(SM_ACTION_ID, str(action_id))
    span.set_attribute(SM_DIRECTION, direction)


def _set_verdict_attrs(span, verdict: InspectResult) -> None:
    """Attach the policy-engine decision and per-scanner scores as span
    attributes. Sets the OTel span status to ERROR on BLOCK/QUARANTINE so
    the trace viewer renders it red."""
    span.set_attribute(SM_DECISION, verdict.decision)
    if verdict.reason:
        span.set_attribute(SM_REASON, verdict.reason[:200])
    span.set_attribute(SM_COMPOSITE_RISK, float(verdict.composite_risk))
    span.set_attribute(SM_BLAST_RADIUS, float(verdict.blast_radius))
    for layer, score in (verdict.scores or {}).items():
        try:
            span.set_attribute(SM_SCANNER_PREFIX + str(layer), float(score))
        except (TypeError, ValueError):  # pragma: no cover — defensive
            continue
    if verdict.blocked:
        try:
            from opentelemetry.trace.status import Status, StatusCode  # type: ignore
            span.set_status(Status(StatusCode.ERROR, verdict.reason or verdict.decision))
        except ImportError:  # pragma: no cover
            pass


__all__ = [
    "configure_tracing", "InstrumentedSentinelClient",
    "GEN_AI_SYSTEM", "GEN_AI_OPERATION_NAME", "GEN_AI_TOOL_NAME",
    "SM_DECISION", "SM_COMPOSITE_RISK", "SM_BLAST_RADIUS", "SM_SCANNER_PREFIX",
]
