"""LangGraph state. All agent nodes read/write this single TypedDict so the
state is fully serialisable and checkpointable."""

from __future__ import annotations

from typing import Any, Literal, TypedDict


class StepRecord(TypedDict, total=False):
    intent: str
    tool: str
    args: dict[str, Any]
    sentinel_decision: str         # ALLOW | REWRITE | REQUIRE_APPROVAL | BLOCK | QUARANTINE
    sentinel_reason: str
    inbound_decision: str
    tool_result: dict[str, Any]
    error: str


class AgentState(TypedDict, total=False):
    # ----- public, carried for the whole run -----
    session_id: str                # UUID as str (LangGraph friendlier)
    goal: str
    plan: list[dict[str, Any]]
    cursor: int                    # index into plan
    history: list[StepRecord]
    status: Literal["planning", "executing", "awaiting_approval",
                     "completed", "blocked", "failed"]
    last_error: str
    replan_count: int

    # ----- scratchpad: set by one node and consumed by the next -----
    # Declared explicitly so LangGraph's strict update validation allows the
    # writes. Underscored to mark them as short-lived between adjacent nodes.
    _proposed_tool: str
    _proposed_args: dict[str, Any]
    _proposed_intent: str
    _action_id: str
    _outbound_decision: str
    _outbound_reason: str
    _rewritten_args: dict[str, Any]    # Sentinel-redacted args on REWRITE
    _effective_args: dict[str, Any]    # args actually sent to the tool
    _approval_id: str
    _approval_outcome: str             # approved | denied | expired | timeout | missing
    _tool_result: dict[str, Any]
    _tool_error: str
    _inbound_decision: str
    _inbound_reason: str
