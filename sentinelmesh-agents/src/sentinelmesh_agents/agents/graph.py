"""LangGraph wiring.

Topology:

    [planner] → [executor] → [sentinel_out] →┬→ [run_tool] → [sentinel_in] → [advance] → executor
                                              ├→ (BLOCK)  → [replanner]      ↘
                                              └→ (APPROVE)→ [awaiting_approval]
                                                                              ↘
                                                                               [complete]

Sentinel decisions:
  ALLOW   / REWRITE          → run tool, scan result, advance
  BLOCK   / QUARANTINE       → re-plan if budget left, else mark blocked
  REQUIRE_APPROVAL           → mark awaiting_approval, surface in /goals/{id}
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any
from uuid import UUID

from langgraph.graph import StateGraph, START, END

from sentinelmesh_agents.agents.prompts import EXECUTOR_SYSTEM, PLANNER_SYSTEM
from sentinelmesh_agents.agents.state import AgentState, StepRecord
from sentinelmesh_agents.config import get_settings
from sentinelmesh_agents.llm.base import LLM
from sentinelmesh_agents.llm.openai_compat import LLMRefusalError, LLMTimeoutError
from sentinelmesh_agents.sentinel.client import SentinelClient, new_action_id
from sentinelmesh_agents.tools.registry import ToolRegistry

log = logging.getLogger(__name__)

MAX_REPLANS = 2

# How long the agent will wait for a human to decide an approval in the SOC
# before giving up (and skipping the gated step). Polled, not busy-waited.
# 30 s is enough for an attentive demo operator to click "Approve" / "Deny";
# a longer wait stalls live demos on every gated chip.
APPROVAL_POLL_INTERVAL_S = 1.5
APPROVAL_MAX_WAIT_S = 30.0


# --------- helpers ---------

def _interpolate(value: Any, vars: dict[str, str]) -> Any:
    """Replace {DEMO_SITE} etc. inside string args."""
    if isinstance(value, str):
        for k, v in vars.items():
            value = value.replace(f"{{{k}}}", v)
        return value
    if isinstance(value, dict):
        return {k: _interpolate(v, vars) for k, v in value.items()}
    if isinstance(value, list):
        return [_interpolate(v, vars) for v in value]
    return value


def _arg_vars() -> dict[str, str]:
    s = get_settings()
    return {"DEMO_SITE": s.demo_site_base_url.rstrip("/")}


# --------- node factories (closure over deps) ---------

def build_graph(llm: LLM, sentinel: SentinelClient, tools: ToolRegistry):

    tool_catalogue = tools.describe_for_prompt()

    async def planner_node(state: AgentState) -> dict[str, Any]:
        goal = state["goal"]
        session_id = UUID(state["session_id"])

        await sentinel.publish_state(session_id, state.get("status", "created"), "planning")

        # Goal-level Sentinel pre-flight. The same L1/L3/L4 stack that scores
        # tool args also scores the user's goal text, so prompts like
        # "email me my api key" or "reveal your system prompt" are caught
        # before the planner LLM ever sees them. This closes a real gap:
        # the executor's safety prompt strips secrets from the email body,
        # so by the time DLP looks at email.send args there is nothing to
        # block on — only blast radius — and the user ends up at APPROVE
        # instead of BLOCK on a clearly-malicious request.
        action_id = new_action_id()
        try:
            goal_verdict = await sentinel.inspect_inbound(
                session_id, action_id, "agent.goal", goal,
                meta={"len": len(goal), "stage": "pre_plan"},
            )
            log.info("Sentinel goal-preflight → %s (risk=%.2f)",
                     goal_verdict.decision, goal_verdict.composite_risk)
        except Exception as e:  # noqa: BLE001 — pre-plan check is best-effort
            log.warning("Sentinel goal pre-flight failed: %s", e)
            goal_verdict = None

        if goal_verdict is not None and goal_verdict.blocked:
            # Hard refusal — record the verdict and stop. No plan is generated,
            # so the planner LLM is never invoked with the malicious goal.
            blocked_step: StepRecord = {
                "intent": "goal pre-flight",
                "tool": "agent.goal",
                "args": {"goal": goal},
                "sentinel_decision": goal_verdict.decision,
                "sentinel_reason": goal_verdict.reason
                                    or "Sentinel rejected the user goal",
                "inbound_decision": goal_verdict.decision,
                "tool_result": {"goal_rejected": True,
                                "decision": goal_verdict.decision,
                                "risk": goal_verdict.composite_risk,
                                "reason": goal_verdict.reason},
                "error": "",
            }
            return {"plan": [], "cursor": 0, "history": [blocked_step],
                    "status": "blocked",
                    "last_error": f"Goal rejected by Sentinel: {goal_verdict.reason}",
                    "replan_count": state.get("replan_count", 0)}

        if goal_verdict is not None and goal_verdict.needs_approval:
            # Surface the verdict in history so the SOC sees it, but still
            # plan — APPROVAL is reviewer-in-the-loop, not refusal.
            log.info("Goal pre-flight wants human approval (id=%s); planning anyway",
                     goal_verdict.approval_id)

        site = get_settings().demo_site_base_url.rstrip("/")
        site_ctx = (
            f"\nSITE CONTEXT — the user's travel app \"SkyNest Travel\" is hosted at {site}.\n"
            f"Use these real pages (do NOT invent external sites):\n"
            f"- {site}/hotels?q=<city>      (hotel search results, HTML — use browser.goto)\n"
            f"- {site}/hotels/<id>          (hotel detail page — use browser.goto)\n"
            f"- {site}/flights              (flight list, HTML — use browser.goto)\n"
            f"- {site}/clean-prices         (hotel price JSON API — use http.get)\n"
            f"- {site}/api/hotels?city=&max_price=&min_rating=  (hotel catalogue JSON — use http.get)\n"
            f"\n"
            f"INTENT — decide before planning:\n"
            f"- LIST / SEARCH (verbs: list, show, find, search, recommend, "
            f"compare, what hotels) → http.get on /api/hotels with the right\n"
            f"  filter query params, then notes.append the summary. Do NOT book.\n"
            f"- BOOK / RESERVE → bookings.create on the right hotel id.\n"
            f"\n"
            f"BOOKING POLICY for SkyNest hotel goals:\n"
            f"- Use bookings.create with the hotel id (e.g. 'grand-plaza',\n"
            f"  'aerocity-delhi', 'cyberpark-pune', 'heritage-jaipur',\n"
            f"  'backwater-kochi', 'lakepalace-udaipur', 'pinelodge-manali',\n"
            f"  'marina-chennai', 'hitec-hyderabad', 'metro-mumbai',\n"
            f"  'lakeside-goa', 'skyline-suites', 'quiet-court').\n"
            f"- A *partner deal* listing also exists at\n"
            f"  '{site}/hotels/partner-grand-plaza'. When the goal explicitly\n"
            f"  asks for a partner / promo / deal listing, browser.goto that\n"
            f"  URL — Sentinel will scan its DOM for prompt-injection.\n"
            f"- bookings.create accepts optional check_in / check_out\n"
            f"  (YYYY-MM-DD), adults (1-12), children (0-8) when the goal\n"
            f"  specifies them.\n"
            f"- Do NOT call payments.charge for SkyNest hotels — bookings.create\n"
            f"  handles the reservation end-to-end. payments.charge is only for\n"
            f"  explicit non-SkyNest charges.\n"
            f"- When emailing the traveller, send to user@example.com.\n"
        )
        user = f"GOAL: {goal}\n\nAVAILABLE TOOLS:\n{tool_catalogue}\n{site_ctx}"
        try:
            plan_obj = await llm.complete_json(
                PLANNER_SYSTEM, user,
                schema_hint="{goal: str, steps: [{intent, tool, args}]}",
            )
        except LLMTimeoutError as e:
            log.warning("Planner LLM timed out: %s", e)
            blocked: StepRecord = {
                "intent": "planner",
                "tool": "agent.plan",
                "args": {"goal": goal},
                "sentinel_decision": "BLOCK",
                "sentinel_reason": "planner timed out — provider stalled",
                "inbound_decision": "BLOCK",
                "tool_result": {"planner_timeout": True, "error": str(e)},
                "error": str(e),
            }
            await sentinel.publish_plan(session_id, goal, [])
            return {"plan": [], "cursor": 0, "history": [blocked],
                    "status": "blocked",
                    "last_error": "Planner LLM timed out (likely upstream content filter stall).",
                    "replan_count": state.get("replan_count", 0)}
        except LLMRefusalError as e:
            log.warning("Planner LLM refused: %s", e)
            blocked = {
                "intent": "planner",
                "tool": "agent.plan",
                "args": {"goal": goal},
                "sentinel_decision": "BLOCK",
                "sentinel_reason": "planner refused — provider content filter",
                "inbound_decision": "BLOCK",
                "tool_result": {"planner_refused": True, "error": str(e)},
                "error": str(e),
            }
            await sentinel.publish_plan(session_id, goal, [])
            return {"plan": [], "cursor": 0, "history": [blocked],
                    "status": "blocked",
                    "last_error": "Planner LLM refused this goal (provider content filter).",
                    "replan_count": state.get("replan_count", 0)}
        except Exception as e:  # noqa: BLE001 — provider misbehaviour
            log.exception("Planner LLM crashed")
            blocked = {
                "intent": "planner",
                "tool": "agent.plan",
                "args": {"goal": goal},
                "sentinel_decision": "BLOCK",
                "sentinel_reason": f"planner error: {e}",
                "inbound_decision": "BLOCK",
                "tool_result": {"planner_error": True, "error": str(e)},
                "error": str(e),
            }
            await sentinel.publish_plan(session_id, goal, [])
            return {"plan": [], "cursor": 0, "history": [blocked],
                    "status": "blocked",
                    "last_error": f"Planner failed: {e}",
                    "replan_count": state.get("replan_count", 0)}

        steps = plan_obj.get("steps", [])
        if not isinstance(steps, list):
            steps = []
        # Interpolate {DEMO_SITE} etc.
        steps = [_interpolate(s, _arg_vars()) for s in steps]
        log.info("Planner produced %d steps for goal=%r", len(steps), goal)

        await sentinel.publish_plan(session_id, goal, steps)

        if not steps:
            # Empty plan = nothing to execute. Surface as blocked-with-reason
            # rather than silently completing, so the SOC sees that the
            # planner declined this goal.
            empty: StepRecord = {
                "intent": "planner",
                "tool": "agent.plan",
                "args": {"goal": goal},
                "sentinel_decision": "BLOCK",
                "sentinel_reason": "planner returned no steps",
                "inbound_decision": "BLOCK",
                "tool_result": {"empty_plan": True,
                                "raw": plan_obj.get("raw") if isinstance(plan_obj, dict) else None},
                "error": "",
            }
            return {"plan": [], "cursor": 0, "history": [empty],
                    "status": "blocked",
                    "last_error": "Planner produced no steps for this goal.",
                    "replan_count": state.get("replan_count", 0)}

        return {"plan": steps, "cursor": 0, "history": [], "status": "executing",
                "replan_count": state.get("replan_count", 0)}

    async def executor_node(state: AgentState) -> dict[str, Any]:
        """Pick the next concrete tool call from the plan (or revise via LLM)."""
        # Respect a terminal status the planner / replanner already chose:
        # the planner can short-circuit a malicious goal (refusal, timeout,
        # Sentinel-rejected goal pre-flight, or empty plan) and we must not
        # silently flip its "blocked" verdict into "completed" by walking
        # past an empty plan here.
        existing_status = state.get("status")
        if existing_status in ("blocked", "failed", "awaiting_approval"):
            return {"_proposed_tool": None}

        plan = state.get("plan", [])
        cursor = state.get("cursor", 0)
        if cursor >= len(plan):
            return {"status": "completed", "_proposed_tool": None}

        step = plan[cursor]
        # For v1 we trust the planner; in v2 we'd ask the LLM to refine via EXECUTOR_SYSTEM.
        return {"_proposed_tool": step.get("tool"),
                "_proposed_args": _interpolate(step.get("args", {}), _arg_vars()),
                "_proposed_intent": step.get("intent", "")}

    async def sentinel_out_node(state: AgentState) -> dict[str, Any]:
        session_id = UUID(state["session_id"])
        tool = state["_proposed_tool"]
        args = state["_proposed_args"]
        action_id = new_action_id()
        await sentinel.publish_tool_call(session_id, tool, args)
        verdict = await sentinel.inspect_outbound(
            session_id,
            action_id,
            tool,
            args,
            origin_actor="planner",
            current_actor="executor",
        )
        log.info("Sentinel outbound %s → %s (risk=%.2f)", tool, verdict.decision, verdict.composite_risk)
        return {"_action_id": str(action_id),
                "_outbound_decision": verdict.decision,
                "_outbound_reason": verdict.reason,
                "_rewritten_args": verdict.rewritten_args or {},
                "_approval_id": str(verdict.approval_id) if verdict.approval_id else ""}

    def out_router(state: AgentState) -> str:
        d = state.get("_outbound_decision", "BLOCK")
        if d in ("ALLOW", "REWRITE"):       return "run_tool"
        if d == "REQUIRE_APPROVAL":          return "await_approval"
        return "replanner"  # BLOCK / QUARANTINE

    async def run_tool_node(state: AgentState) -> dict[str, Any]:
        tool_name = state["_proposed_tool"]
        args = state["_proposed_args"]
        # On REWRITE, Sentinel hands back a redacted payload — send that, not the original.
        if state.get("_outbound_decision") == "REWRITE" and state.get("_rewritten_args"):
            args = state["_rewritten_args"]
            log.info("Using Sentinel-redacted args for %s", tool_name)
        try:
            tool = tools.get(tool_name)
            result = await tool.fn(args)
            return {"_tool_result": result, "_tool_error": "", "_effective_args": args}
        except Exception as e:
            log.exception("Tool %s failed", tool_name)
            return {"_tool_result": {"error": str(e)}, "_tool_error": str(e)}

    async def sentinel_in_node(state: AgentState) -> dict[str, Any]:
        session_id = UUID(state["session_id"])
        tool = state["_proposed_tool"]
        result = state.get("_tool_result", {})
        sample = _extract_sample(tool, result)
        await sentinel.publish_tool_result(session_id, tool, result, sample)
        action_id = UUID(state["_action_id"])
        verdict = await sentinel.inspect_inbound(session_id, action_id, tool, sample,
                                                  meta={"len": len(sample)})
        log.info("Sentinel inbound %s → %s (risk=%.2f)", tool, verdict.decision, verdict.composite_risk)
        return {"_inbound_decision": verdict.decision, "_inbound_reason": verdict.reason}

    def in_router(state: AgentState) -> str:
        d = state.get("_inbound_decision", "ALLOW")
        if d in ("ALLOW", "REWRITE"):       return "advance"
        return "replanner"

    max_steps = get_settings().max_steps

    async def advance_node(state: AgentState) -> dict[str, Any]:
        cursor = state.get("cursor", 0)
        history = list(state.get("history", []))
        if len(history) >= max_steps:
            log.warning("Step ceiling reached (%d); marking completed", max_steps)
            return {"history": history, "status": "completed",
                    "last_error": f"Stopped at step ceiling ({max_steps})",
                    "_proposed_tool": None}
        record: StepRecord = {
            "intent": state.get("_proposed_intent", ""),
            "tool": state["_proposed_tool"],
            "args": state.get("_effective_args") or state["_proposed_args"],
            "sentinel_decision": state.get("_outbound_decision", ""),
            "sentinel_reason": state.get("_outbound_reason", ""),
            "inbound_decision": state.get("_inbound_decision", ""),
            "tool_result": state.get("_tool_result", {}),
            "error": state.get("_tool_error", ""),
        }
        history.append(record)
        next_cursor = cursor + 1
        plan = state.get("plan", [])
        if next_cursor >= len(plan):
            return {"history": history, "cursor": next_cursor, "status": "completed",
                    "_proposed_tool": None}
        return {"history": history, "cursor": next_cursor}

    async def replanner_node(state: AgentState) -> dict[str, Any]:
        replans = state.get("replan_count", 0)
        # Record the blocked step for posterity.
        history = list(state.get("history", []))
        history.append({
            "intent": state.get("_proposed_intent", ""),
            "tool": state["_proposed_tool"],
            "args": state["_proposed_args"],
            "sentinel_decision": state.get("_outbound_decision", "")
                                  or state.get("_inbound_decision", ""),
            "sentinel_reason": state.get("_outbound_reason", "")
                                or state.get("_inbound_reason", ""),
            "tool_result": state.get("_tool_result", {}),
        })
        if replans >= MAX_REPLANS:
            log.warning("Max replans reached (%d); marking blocked", replans)
            return {"history": history, "status": "blocked",
                    "last_error": "Re-plan budget exhausted after Sentinel blocks"}
        # Cheap heuristic: just skip the blocked step.
        log.info("Replanning: skipping blocked step %d", state.get("cursor", 0))
        return {"history": history, "cursor": state.get("cursor", 0) + 1,
                "replan_count": replans + 1, "status": "executing"}

    async def await_approval_node(state: AgentState) -> dict[str, Any]:
        """Pause for a human decision in the SOC, then resume.

        Polls the backend approval until it leaves PENDING (APPROVED / MODIFIED /
        DENIED / EXPIRED) or we hit the wait budget. On approval we route back to
        run the gated action; otherwise we hand off to the replanner.
        """
        session_id = UUID(state["session_id"])
        approval_id_s = state.get("_approval_id") or ""
        await sentinel.publish_state(session_id, "executing", "awaiting_approval")
        log.info("Awaiting human approval %s (up to %.0fs)", approval_id_s, APPROVAL_MAX_WAIT_S)

        if not approval_id_s:
            return {"status": "blocked", "_approval_outcome": "missing",
                    "last_error": "No approval id to wait on"}

        approval_id = UUID(approval_id_s)
        status = "PENDING"
        waited = 0.0
        while waited < APPROVAL_MAX_WAIT_S:
            try:
                ap = await sentinel.get_approval(approval_id)
                status = (ap.get("status") or "PENDING").upper()
            except Exception as e:  # noqa: BLE001 — transient; keep polling
                log.warning("Approval poll failed: %s", e)
            if status != "PENDING":
                break
            await asyncio.sleep(APPROVAL_POLL_INTERVAL_S)
            waited += APPROVAL_POLL_INTERVAL_S

        log.info("Approval %s resolved: %s", approval_id, status)
        if status in ("APPROVED", "MODIFIED"):
            await sentinel.publish_state(session_id, "awaiting_approval", "executing")
            # Promote the decision to ALLOW so the action's history row
            # reflects what actually happened (operator approved → tool
            # ran), instead of leaving the original REQUIRE_APPROVAL on
            # the record. Without this fix the SOC timeline reads as
            # "approval pending" even after the action completed.
            return {
                "_approval_outcome": "approved",
                "status": "executing",
                "_outbound_decision": "ALLOW",
                "_outbound_reason": (
                    "ALLOW (operator-approved after REQUIRE_APPROVAL)"
                ),
            }
        # Denied, expired, or timed out → fall through to the replanner.
        await sentinel.publish_state(session_id, "awaiting_approval", "executing")
        return {"_approval_outcome": status.lower(), "status": "executing",
                "_outbound_decision": "BLOCK",
                "_outbound_reason": f"approval {status.lower()}"}

    # ---- assemble the graph ----

    g = StateGraph(AgentState)
    g.add_node("planner", planner_node)
    g.add_node("executor", executor_node)
    g.add_node("sentinel_out", sentinel_out_node)
    g.add_node("run_tool", run_tool_node)
    g.add_node("sentinel_in", sentinel_in_node)
    g.add_node("advance", advance_node)
    g.add_node("replanner", replanner_node)
    g.add_node("await_approval", await_approval_node)

    g.add_edge(START, "planner")
    g.add_edge("planner", "executor")

    # executor → either sentinel_out (if a step exists) or END (completed)
    def exec_router(state: AgentState) -> str:
        if state.get("status") == "completed": return END
        if state.get("_proposed_tool") is None: return END
        return "sentinel_out"

    g.add_conditional_edges("executor", exec_router, {END: END, "sentinel_out": "sentinel_out"})
    g.add_conditional_edges("sentinel_out", out_router, {
        "run_tool": "run_tool", "replanner": "replanner", "await_approval": "await_approval",
    })
    g.add_edge("run_tool", "sentinel_in")
    g.add_conditional_edges("sentinel_in", in_router, {
        "advance": "advance", "replanner": "replanner",
    })

    def loop_router(state: AgentState) -> str:
        if state.get("status") in ("completed", "blocked", "failed", "awaiting_approval"):
            return END
        return "executor"

    g.add_conditional_edges("advance", loop_router, {"executor": "executor", END: END})
    g.add_conditional_edges("replanner", loop_router, {"executor": "executor", END: END})

    # After a human decision: run the gated action, or re-plan around it.
    def approval_router(state: AgentState) -> str:
        return "run_tool" if state.get("_approval_outcome") == "approved" else "replanner"

    g.add_conditional_edges("await_approval", approval_router,
                            {"run_tool": "run_tool", "replanner": "replanner"})

    return g.compile()


# --------- sample extraction (what we hand to Sentinel) ---------

def _extract_sample(tool: str, result: dict[str, Any]) -> str:
    if tool == "browser.goto":
        # Hidden DOM lives in html_sample, not the visible text.
        return (result.get("html_sample") or "") + "\n\n" + (result.get("text") or "")
    if tool == "http.get":
        return result.get("body") or ""
    if tool == "email.send":
        return json.dumps({k: result.get(k) for k in ("to", "subject", "body")})
    return json.dumps(result)[:4000]
