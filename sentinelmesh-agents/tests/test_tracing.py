"""Tests for the OpenTelemetry / Foundry tracing emission.

Uses OTel's in-memory span exporter so assertions can read the emitted
span attributes directly without spinning up a collector.

The ``InstrumentedSentinelClient`` wraps ``SentinelClient`` whose HTTP
calls go to the backend; for unit testing we patch out the underlying
``inspect_outbound`` / ``inspect_inbound`` methods (since ``SentinelClient``
is a normal class, ``object.__setattr__`` works, but we use a thin subclass
that hard-codes the verdict so the test reads obviously).
"""

from __future__ import annotations

from typing import Any
from uuid import uuid4

import pytest

# Skip the whole file gracefully if opentelemetry isn't installed (CI variant
# without the [otel] extra).
otel = pytest.importorskip("opentelemetry")

# Imports below are intentionally deferred until after the importorskip
# guard so the test file imports cleanly without the OTel SDK present.
# noqa: E402 markers tell ruff this is intentional.
from opentelemetry import trace  # noqa: E402
from opentelemetry.sdk.resources import SERVICE_NAME, Resource  # noqa: E402
from opentelemetry.sdk.trace import TracerProvider  # noqa: E402
from opentelemetry.sdk.trace.export import SimpleSpanProcessor  # noqa: E402
from opentelemetry.sdk.trace.export.in_memory_span_exporter import (  # noqa: E402
    InMemorySpanExporter,
)

from sentinelmesh_agents.microsoft import tracing as sm_tracing  # noqa: E402
from sentinelmesh_agents.microsoft.tracing import (  # noqa: E402
    GEN_AI_SYSTEM, GEN_AI_TOOL_NAME, InstrumentedSentinelClient,
    SM_COMPOSITE_RISK, SM_DECISION, SM_DIRECTION, SM_SCANNER_PREFIX,
)
from sentinelmesh_agents.sentinel.client import InspectResult  # noqa: E402


# ---------- in-memory exporter wiring ----------

# OTel's global TracerProvider can only be set *once* per process. Set up a
# single shared provider for the test session; each test gets its own fresh
# in-memory exporter attached, then removed at teardown so spans don't leak
# across tests.
_PROVIDER: TracerProvider | None = None


def _ensure_provider() -> TracerProvider:
    global _PROVIDER
    if _PROVIDER is None:
        _PROVIDER = TracerProvider(resource=Resource.create({SERVICE_NAME: "sm-test"}))
        try:
            trace.set_tracer_provider(_PROVIDER)
        except Exception:  # noqa: BLE001 — already set; reuse the existing one
            existing = trace.get_tracer_provider()
            if isinstance(existing, TracerProvider):
                _PROVIDER = existing
    return _PROVIDER


@pytest.fixture
def memory_exporter():
    provider = _ensure_provider()
    exporter = InMemorySpanExporter()
    processor = SimpleSpanProcessor(exporter)
    provider.add_span_processor(processor)
    sm_tracing._TRACER = trace.get_tracer("sentinelmesh.security")  # noqa: SLF001
    yield exporter
    # Drain any in-flight spans, then drop this exporter so the next test
    # starts fresh.
    processor.shutdown()
    sm_tracing._TRACER = None  # noqa: SLF001


# ---------- a Sentinel client that doesn't talk to HTTP ----------

class _FakeClient(InstrumentedSentinelClient):
    """Subclasses the *instrumented* client and overrides the underlying
    HTTP calls with a canned verdict. The OTel span path is the same as
    the real one — only the network leg is replaced."""

    def __init__(self, verdict: InspectResult):
        # Skip the real __init__ (which would open an httpx.AsyncClient and
        # require a live backend); we don't need it for tracing unit tests.
        self._verdict = verdict

    async def _inspect(self, body):  # noqa: D401 — override stub
        return self._verdict


def _verdict(decision: str, scores: dict[str, float] | None = None) -> InspectResult:
    return InspectResult(
        decision=decision,
        reason="test reason",
        composite_risk=0.42,
        blast_radius=0.7,
        scores=scores or {"L1": 0.30, "L4": 0.55, "DLP": 0.85},
        findings={},
        approval_id=None,
        raw={},
        rewritten_args=None,
        rewritten_content=None,
    )


# ---------- behaviour ----------

@pytest.mark.asyncio
async def test_inspect_outbound_emits_one_span_with_gen_ai_attrs(memory_exporter) -> None:
    client = _FakeClient(_verdict("ALLOW"))
    sid, aid = uuid4(), uuid4()
    await client.inspect_outbound(sid, aid, "email.send", {"to": "x@y.com"})

    spans = memory_exporter.get_finished_spans()
    assert len(spans) == 1
    s = spans[0]
    # gen_ai semantic-convention attributes are set so Foundry's trace viewer
    # picks the span up automatically as a tool execution.
    assert s.attributes[GEN_AI_SYSTEM] == "sentinelmesh"
    assert s.attributes[GEN_AI_TOOL_NAME] == "email.send"
    assert s.attributes[SM_DIRECTION] == "OUTBOUND"
    assert s.attributes[SM_DECISION] == "ALLOW"
    assert s.attributes[SM_COMPOSITE_RISK] == pytest.approx(0.42)


@pytest.mark.asyncio
async def test_block_decision_sets_error_status(memory_exporter) -> None:
    client = _FakeClient(_verdict("BLOCK"))
    await client.inspect_outbound(uuid4(), uuid4(), "payments.charge", {"amount": 50000})

    spans = memory_exporter.get_finished_spans()
    assert len(spans) == 1
    # OTel records ERROR status when blast radius / risk crosses the bar so
    # Foundry renders the span red.
    from opentelemetry.trace.status import StatusCode
    assert spans[0].status.status_code == StatusCode.ERROR
    assert spans[0].attributes[SM_DECISION] == "BLOCK"


@pytest.mark.asyncio
async def test_per_scanner_scores_emitted_as_span_attributes(memory_exporter) -> None:
    client = _FakeClient(_verdict("ALLOW", {"L1": 0.10, "L2": 0.30, "DLP": 0.85}))
    await client.inspect_outbound(uuid4(), uuid4(), "http.get", {"url": "https://example.com"})

    spans = memory_exporter.get_finished_spans()
    attrs: dict[str, Any] = dict(spans[0].attributes)
    assert attrs[SM_SCANNER_PREFIX + "L1"] == pytest.approx(0.10)
    assert attrs[SM_SCANNER_PREFIX + "L2"] == pytest.approx(0.30)
    assert attrs[SM_SCANNER_PREFIX + "DLP"] == pytest.approx(0.85)


@pytest.mark.asyncio
async def test_inbound_inspect_emits_separate_span(memory_exporter) -> None:
    client = _FakeClient(_verdict("REWRITE"))
    sid, aid = uuid4(), uuid4()
    await client.inspect_inbound(sid, aid, "browser.goto", "<html>...</html>")

    spans = memory_exporter.get_finished_spans()
    assert len(spans) == 1
    assert spans[0].attributes[SM_DIRECTION] == "INBOUND"
    # gen_ai tool name still set so the inbound span is on the same axis as
    # the outbound one in the Foundry trace viewer.
    assert spans[0].attributes[GEN_AI_TOOL_NAME] == "browser.goto"
    assert spans[0].attributes[SM_DECISION] == "REWRITE"


@pytest.mark.asyncio
async def test_no_tracer_means_pass_through_no_spans(memory_exporter) -> None:
    """When tracing isn't configured, the wrapper degrades to plain
    pass-through without emitting any spans."""
    sm_tracing._TRACER = None  # noqa: SLF001 — explicitly disable
    client = _FakeClient(_verdict("ALLOW"))
    await client.inspect_outbound(uuid4(), uuid4(), "tool", {})
    assert memory_exporter.get_finished_spans() == ()


def test_configure_tracing_without_endpoint_returns_false(monkeypatch) -> None:
    """When neither an explicit endpoint nor the env var is set, configure
    is a no-op and returns False (so the operator gets a clear log line)."""
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    assert sm_tracing.configure_tracing() is False
