"""Unit tests for the MAF / Semantic Kernel function-middleware adapter.

The middleware is duck-typed against MAF's ``FunctionInvocationContext`` so
we can drive it from a tiny fake instead of pulling in `agent-framework`.
This keeps the test suite hermetic and fast.

Each test uses a `FakeSentinelClient` that records inspect calls and returns
canned verdicts, plus a `FakeContext` that mimics MAF's contract just
enough (`function.name`, `function.plugin_name`, `arguments`, `result`,
`terminate`).
"""

from __future__ import annotations

from typing import Any
from uuid import UUID, uuid4

import pytest

from sentinelmesh_agents.microsoft.maf_middleware import SentinelMiddleware
from sentinelmesh_agents.sentinel.client import InspectResult


# ---------- fakes ----------

class _FakeFn:
    def __init__(self, name: str, plugin: str | None = None) -> None:
        self.name = name
        self.plugin_name = plugin


class FakeContext:
    """Mimics MAF / SK's ``FunctionInvocationContext`` with the attributes
    the middleware actually touches: ``function``, ``arguments``, ``result``,
    ``terminate``."""

    def __init__(self, name: str, args: dict[str, Any], plugin: str | None = None) -> None:
        self.function = _FakeFn(name, plugin)
        self.arguments: dict[str, Any] = dict(args)
        self.result: Any = None
        self.terminate: bool = False


class FakeSentinelClient:
    def __init__(self, outbound_verdict: InspectResult,
                 inbound_verdict: InspectResult | None = None) -> None:
        self.outbound_verdict = outbound_verdict
        self.inbound_verdict = inbound_verdict
        self.outbound_calls: list[tuple[str, dict[str, Any]]] = []
        self.inbound_calls: list[tuple[str, str]] = []
        self.closed = False

    async def inspect_outbound(
        self, session_id, action_id, tool, args,
        *, origin_actor=None, current_actor=None,
    ) -> InspectResult:
        self.outbound_calls.append((tool, dict(args)))
        return self.outbound_verdict

    async def inspect_inbound(self, session_id, action_id, tool, content, meta=None) -> InspectResult:
        self.inbound_calls.append((tool, content))
        if self.inbound_verdict is None:
            return _verdict("ALLOW")
        return self.inbound_verdict

    async def close(self) -> None:
        self.closed = True


def _verdict(decision: str, *, rewritten_args: dict[str, Any] | None = None,
             reason: str = "", scores: dict[str, float] | None = None) -> InspectResult:
    return InspectResult(
        decision=decision,
        reason=reason,
        composite_risk=0.5,
        blast_radius=0.5,
        scores=scores or {},
        findings={},
        approval_id=None,
        raw={},
        rewritten_args=rewritten_args,
        rewritten_content=None,
    )


@pytest.fixture
def session_id() -> UUID:
    return uuid4()


# ---------- behaviour: ALLOW → calls through ----------

@pytest.mark.asyncio
async def test_allow_calls_through_to_next(session_id) -> None:
    client = FakeSentinelClient(_verdict("ALLOW"))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type]
    ctx = FakeContext("send_email", {"to": "user@example.com", "body": "hi"})

    next_called = False

    async def _next(c) -> None:
        nonlocal next_called
        next_called = True
        c.result = "ok"

    await mw(ctx, _next)

    assert next_called is True
    assert ctx.terminate is False
    assert ctx.result == "ok"
    # And the outbound inspection saw the right tool/args.
    assert client.outbound_calls == [("send_email", {"to": "user@example.com", "body": "hi"})]
    # Result was re-inspected as inbound on the way back.
    assert client.inbound_calls == [("send_email", "ok")]


# ---------- behaviour: BLOCK / QUARANTINE / REQUIRE_APPROVAL terminate ----------

@pytest.mark.parametrize("decision", ["BLOCK", "QUARANTINE", "REQUIRE_APPROVAL"])
@pytest.mark.asyncio
async def test_terminating_decisions_set_refusal_and_skip_next(session_id, decision) -> None:
    client = FakeSentinelClient(_verdict(decision, reason="composite risk too high",
                                         scores={"L1": 0.9, "DLP": 0.85}))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type]
    ctx = FakeContext("payments_charge", {"amount": 50000, "vendor": "evil-hotel.local"})

    next_called = False

    async def _next(c) -> None:
        nonlocal next_called
        next_called = True

    await mw(ctx, _next)

    assert next_called is False, f"{decision} must short-circuit before next()"
    assert ctx.terminate is True
    assert isinstance(ctx.result, str)
    assert "[SentinelMesh]" in ctx.result
    assert decision in ctx.result
    assert "L1=0.90" in ctx.result or "DLP=0.85" in ctx.result
    # Inbound inspect should *not* fire for terminated calls.
    assert client.inbound_calls == []


# ---------- behaviour: REWRITE swaps args before next() ----------

@pytest.mark.asyncio
async def test_rewrite_replaces_arguments_in_place(session_id) -> None:
    client = FakeSentinelClient(_verdict(
        "REWRITE",
        rewritten_args={"to": "user@example.com",
                        "body": "Booking confirmed. <REDACTED-SECRET>"},
    ))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type]
    seen_args: dict[str, Any] = {}

    async def _next(c) -> None:
        seen_args.update(c.arguments)
        c.result = "sent"

    ctx = FakeContext(
        "send_email",
        {"to": "user@example.com",
         "body": "Booking confirmed. AKIAIOSFODNN7EXAMPLE"},
    )
    await mw(ctx, _next)

    assert ctx.terminate is False
    # The downstream tool saw redacted args, not the original.
    assert "AKIA" not in seen_args["body"]
    assert seen_args["body"] == "Booking confirmed. <REDACTED-SECRET>"


# ---------- behaviour: inspect failure fails open with a warning ----------

class FailingClient(FakeSentinelClient):
    async def inspect_outbound(self, *a, **kw):  # type: ignore[override]
        raise RuntimeError("backend down")


@pytest.mark.asyncio
async def test_inspect_failure_fails_open(session_id, caplog) -> None:
    client = FailingClient(_verdict("ALLOW"))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type]
    ctx = FakeContext("http_get", {"url": "https://example.com"})
    next_called = False

    async def _next(c) -> None:
        nonlocal next_called
        next_called = True

    with caplog.at_level("WARNING"):
        await mw(ctx, _next)

    assert next_called is True
    assert any("inspect failed" in rec.message for rec in caplog.records)


# ---------- behaviour: aclose only frees its own client ----------

@pytest.mark.asyncio
async def test_aclose_does_not_close_host_owned_client(session_id) -> None:
    client = FakeSentinelClient(_verdict("ALLOW"))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type] — host-owned
    await mw.aclose()
    assert client.closed is False


@pytest.mark.asyncio
async def test_aclose_closes_owned_client(session_id) -> None:
    client = FakeSentinelClient(_verdict("ALLOW"))
    mw = SentinelMiddleware(client, session_id, _owns_client=True)  # type: ignore[arg-type]
    await mw.aclose()
    assert client.closed is True
    # And calling twice is a no-op.
    await mw.aclose()
    assert client.closed is True


# ---------- behaviour: tool name resolver honours plugin_name ----------

@pytest.mark.asyncio
async def test_canonical_tool_name_uses_plugin_dot_name(session_id) -> None:
    client = FakeSentinelClient(_verdict("ALLOW"))
    mw = SentinelMiddleware(client, session_id)  # type: ignore[arg-type]
    ctx = FakeContext("send", {"to": "x"}, plugin="email")

    async def _next(c) -> None:
        c.result = "ok"

    await mw(ctx, _next)
    # MAF surfaces "<plugin>.<name>" — that's the same shape the policy
    # bundle expects for its tool catalogue.
    assert client.outbound_calls[0][0] == "email.send"
