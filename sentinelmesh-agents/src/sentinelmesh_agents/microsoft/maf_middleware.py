"""Microsoft Agent Framework `function_middleware` adapter for SentinelMesh.

Microsoft Agent Framework (MAF) is the unified, stable Python+.NET SDK that
replaces Semantic Kernel + AutoGen for agent authoring. It exposes three
middleware extension points on every Agent: `Agent run`, `Function calling`,
and `Chat client` middleware. Each is a callback `(context, next)` chain in
the same shape as ASGI / Express / Spring Boot filters.

The hook we want is **function middleware** — it fires on *every* tool call
the agent makes (auto-invoked or otherwise) and gives us:

* the tool name (`context.function.name`)
* the arguments (`context.arguments`)
* `context.terminate` to refuse the call short of running it
* `context.result` to substitute a refusal message
* the post-call result (`context.result` after `await next(context)`)

That's a one-to-one mapping onto SentinelMesh's `inspect_outbound` →
policy-engine decision → either ALLOW (call through), REWRITE (call through
with redacted args), REQUIRE_APPROVAL (suspend + ask SOC; for the demo we
treat as "blocked-pending-approval"), or BLOCK / QUARANTINE (refuse).

Usage in the host application::

    from agent_framework import Agent
    from agent_framework.openai import OpenAIChatClient
    from sentinelmesh_agents.microsoft.maf_middleware import attach_sentinel

    agent = Agent(
        name="booking_agent",
        client=OpenAIChatClient(model="gpt-4o-mini"),
        instructions="You book hotels for travellers.",
        tools=[book_hotel, send_email],
        middleware=[await attach_sentinel(goal="book a hotel in Bangalore")],
    )

That's the *whole* integration — three lines on top of any existing MAF
agent, and every tool call is now governed by SentinelMesh's full policy
engine, with audit-chain entries and SOC dashboard visibility.

Design notes
------------
* The middleware is **duck-typed** against MAF's `FunctionInvocationContext`
  rather than statically typed. That keeps `agent-framework` as an *optional*
  install — the module imports cleanly without it, and unit tests can use a
  three-attribute fake instead of a real MAF context.
* The middleware also works as a **Semantic Kernel** filter without changes:
  SK's `FunctionInvocationContext` exposes the same `function.name`,
  `arguments`, and `result` shape. (Microsoft is steering toward MAF, but
  legacy SK users get the same governance for free.)
* SentinelClient session lifecycle is owned by the middleware *only* when it
  was constructed via `attach_sentinel(...)` (the convenience factory).
  When the host app passes its own `SentinelClient` + `session_id` into
  `SentinelMiddleware(...)` directly, ownership stays with the host.
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable, TYPE_CHECKING
from uuid import UUID

from sentinelmesh_agents.sentinel.client import (
    InspectResult, SentinelClient, new_action_id,
)

if TYPE_CHECKING:
    # Type-only import — avoids pulling agent-framework at runtime.
    from agent_framework import FunctionInvocationContext  # type: ignore[import-not-found]

log = logging.getLogger(__name__)

# Decisions that *terminate* the function call short of running it. The
# middleware sets `context.terminate = True` and writes a refusal string to
# `context.result`. Anything else flows through to `next(context)`.
_TERMINATING_DECISIONS = {"BLOCK", "QUARANTINE", "REQUIRE_APPROVAL"}


def _summarize_findings(scores: dict[str, float], reason: str) -> str:
    """One-line description of which scanners fired, for the refusal text."""
    if not scores:
        return reason or "no scanner findings"
    top = sorted(scores.items(), key=lambda kv: -kv[1])[:3]
    bits = ", ".join(f"{layer}={score:.2f}" for layer, score in top if score > 0)
    return f"{reason} ({bits})" if bits else reason


class SentinelMiddleware:
    """A `function_middleware`-shaped callable that routes every MAF tool
    call through SentinelMesh's inspect → policy → audit pipeline.

    The middleware is a class (not a bare async function) because it owns a
    little bit of state: the bound session id, the policy-engine `SentinelClient`,
    and a marker for whether it owns the client's lifecycle (so `aclose()`
    can be safely called more than once without freeing a host-owned client).

    Parameters
    ----------
    client:
        An open `SentinelClient`. Reused across every middleware invocation.
    session_id:
        Session this agent run belongs to. Created up-front (typically by
        ``attach_sentinel(...)``) so audit / firehose events correlate.
    origin_actor / current_actor:
        Capability-escalation provenance. For a single-actor MAF agent this
        is fine to leave at the default ``"executor"``. For planner→executor
        delegation, set ``origin_actor="planner"`` on the planner-driven
        middleware and ``current_actor="executor"`` on the executor-driven
        one — the CAP scanner will then catch confused-deputy abuses.
    tool_name_resolver:
        Optional override that maps a MAF function to its canonical tool
        name. Defaults to ``f"{plugin_name}.{function_name}"``, falling back
        to the bare function name. Use this when your MAF function names
        don't match the policy engine's registered tool catalogue.
    _owns_client:
        Internal — used only by ``attach_sentinel`` to indicate the client
        was constructed inside this middleware and should be closed by
        ``aclose()``. Host-constructed clients are left alone.
    """

    def __init__(
        self,
        client: SentinelClient,
        session_id: UUID,
        *,
        origin_actor: str = "executor",
        current_actor: str = "executor",
        tool_name_resolver: Callable[[Any], str] | None = None,
        _owns_client: bool = False,
    ) -> None:
        self._client = client
        self._session_id = session_id
        self._origin = origin_actor
        self._current = current_actor
        self._resolve_tool = tool_name_resolver or _default_tool_name
        self._owns_client = _owns_client

    @property
    def session_id(self) -> UUID:
        return self._session_id

    async def aclose(self) -> None:
        """Close the underlying client iff this middleware owns it."""
        if self._owns_client:
            await self._client.close()
            self._owns_client = False  # idempotent

    async def __call__(
        self,
        context: "FunctionInvocationContext",
        next: Callable[["FunctionInvocationContext"], Awaitable[None]],
    ) -> None:
        """The MAF middleware contract: inspect, then either short-circuit or
        delegate to ``next(context)``. After the inner call we re-inspect the
        result on its way back so inbound payloads (HTTP responses, tool
        outputs) get the same scanner pipeline."""
        tool = self._resolve_tool(context)
        args = _arguments_to_dict(getattr(context, "arguments", None))
        action_id = new_action_id()

        try:
            verdict = await self._client.inspect_outbound(
                self._session_id, action_id, tool, args,
                origin_actor=self._origin, current_actor=self._current,
            )
        except Exception as exc:  # noqa: BLE001 — log and fail open with a warning
            log.warning("SentinelMesh inspect failed for %s: %s — failing open.", tool, exc)
            await next(context)
            return

        if verdict.decision in _TERMINATING_DECISIONS:
            self._refuse(context, tool, verdict)
            return

        # REWRITE → swap in the redacted arguments before the call runs.
        if verdict.decision == "REWRITE" and verdict.rewritten_args is not None:
            try:
                _apply_rewritten_args(context, verdict.rewritten_args)
            except Exception as exc:  # noqa: BLE001
                log.warning(
                    "Could not apply rewritten args for %s (%s) — falling back to BLOCK.",
                    tool, exc,
                )
                self._refuse(context, tool, verdict)
                return

        await next(context)

        # Re-inspect the *result* as inbound content. Most MAF tools return
        # a string; richer return types are coerced to repr for the scan.
        await self._inspect_result(action_id, tool, context)

    def _refuse(self, context: Any, tool: str, verdict: InspectResult) -> None:
        """Mark the function call as terminated and set the refusal text on
        the context so the LLM sees a structured deny rather than an empty
        return value."""
        evidence = _summarize_findings(verdict.scores, verdict.reason)
        msg = (
            f"[SentinelMesh] {tool} {verdict.decision}: {evidence}. "
            "The action was not executed; please retry with a less-privileged "
            "or non-sensitive request, or seek human approval."
        )
        # MAF: setting result + terminate is the documented way to skip the
        # underlying tool call. SK uses the same shape.
        context.result = msg
        context.terminate = True

    async def _inspect_result(self, action_id: UUID, tool: str, context: Any) -> None:
        result = getattr(context, "result", None)
        if result is None:
            return
        text = _coerce_to_text(result)
        if not text:
            return
        try:
            await self._client.inspect_inbound(
                self._session_id, action_id, tool, text,
                meta={"phase": "tool_result"},
            )
        except Exception as exc:  # noqa: BLE001
            log.debug("Inbound result inspect failed for %s: %s", tool, exc)


async def attach_sentinel(
    *,
    goal: str,
    user_id: str = "user@example.com",
    policy_bundle_id: str = "default",
    origin_actor: str = "executor",
    current_actor: str = "executor",
    client: SentinelClient | None = None,
) -> SentinelMiddleware:
    """Convenience factory: open a session and return a ready middleware.

    Pass the result into your MAF Agent's ``middleware=[...]`` list. The
    returned middleware will close its own SentinelClient on ``aclose()``.
    """
    owns = client is None
    if client is None:
        client = SentinelClient()
    session_id = await client.create_session(user_id, goal, policy_bundle_id=policy_bundle_id)
    return SentinelMiddleware(
        client, session_id,
        origin_actor=origin_actor,
        current_actor=current_actor,
        _owns_client=owns,
    )


# ---------- helpers ----------

def _default_tool_name(context: Any) -> str:
    """Best-effort canonical name for the function under invocation.

    MAF: ``context.function.name`` and ``context.function.plugin_name``.
    SK:  ``context.function.name`` and ``context.function.plugin_name``.
    Bare callables: just the ``__name__``.
    """
    fn = getattr(context, "function", None)
    if fn is None:
        return "<unknown>"
    name = getattr(fn, "name", None) or getattr(fn, "__name__", None) or "<unknown>"
    plugin = getattr(fn, "plugin_name", None)
    return f"{plugin}.{name}" if plugin else str(name)


def _arguments_to_dict(arguments: Any) -> dict[str, Any]:
    """MAF and SK both expose ``arguments`` as a mapping-shaped object that
    duck-types as a dict but isn't always a vanilla one (KernelArguments,
    pydantic models, dataclasses)."""
    if arguments is None:
        return {}
    if isinstance(arguments, dict):
        return dict(arguments)
    # KernelArguments / pydantic-like: try the public ``model_dump`` first.
    if hasattr(arguments, "model_dump"):
        try:
            return dict(arguments.model_dump())
        except Exception:  # noqa: BLE001
            pass
    # Fallback: __dict__ / iter()
    try:
        return {k: arguments[k] for k in arguments}  # type: ignore[index, attr-defined]
    except Exception:  # noqa: BLE001
        pass
    return {"_repr": repr(arguments)}


def _apply_rewritten_args(context: Any, rewritten: dict[str, Any]) -> None:
    """Replace ``context.arguments`` with the policy engine's redacted args.

    For MAF / SK the arguments object supports item-assignment, so we update
    in place when possible (preserving any extra metadata the framework
    attached) and reassign as a dict otherwise.
    """
    args = getattr(context, "arguments", None)
    if args is None:
        context.arguments = rewritten
        return
    try:
        for k in list(args):  # type: ignore[arg-type]
            if k not in rewritten:
                del args[k]  # type: ignore[index]
        for k, v in rewritten.items():
            args[k] = v  # type: ignore[index]
    except Exception:  # noqa: BLE001 — assign and move on
        context.arguments = rewritten


def _coerce_to_text(result: Any) -> str:
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    if isinstance(result, (bytes, bytearray)):
        try:
            return result.decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            return ""
    return repr(result)
