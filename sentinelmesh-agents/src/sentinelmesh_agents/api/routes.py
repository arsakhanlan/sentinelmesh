"""REST routes for the agent service. Two endpoints:

  POST /goals       → submit a goal; runs the LangGraph; returns final state.
  GET  /health      → liveness for the orchestrator.

State is intentionally NOT persisted in the agent process — the SentinelMesh
backend already records every step (events + audit). Resuming a paused
(awaiting-approval) goal is a v2 enhancement.
"""

from __future__ import annotations

import logging
from typing import Any
from uuid import UUID

from fastapi import APIRouter, HTTPException

from sentinelmesh_agents.api.models import (
    HealthResponse, SubmitGoalRequest, SubmitGoalResponse,
)
from sentinelmesh_agents.config import get_settings
from sentinelmesh_agents.runtime import get_runtime

log = logging.getLogger(__name__)

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    rt = get_runtime()
    return HealthResponse(status="UP", llm=rt.llm.name, sentinel_url=get_settings().sentinel_base_url)


@router.post("/goals", response_model=SubmitGoalResponse)
async def submit_goal(req: SubmitGoalRequest) -> SubmitGoalResponse:
    rt = get_runtime()
    try:
        session_id = await rt.sentinel.create_session(req.user_id, req.goal, req.policy_bundle_id)
    except Exception as e:
        log.exception("Failed to create backend session")
        raise HTTPException(status_code=502, detail=f"Backend session create failed: {e}")

    initial = {"session_id": str(session_id), "goal": req.goal, "status": "planning"}
    # Each plan step traverses ~5 graph nodes (executor → sentinel_out → run_tool
    # → sentinel_in → advance), and replans + approvals add more. The default
    # LangGraph cap of 25 is too low for realistic runs — bump it generously.
    try:
        final = await rt.graph.ainvoke(initial, config={"recursion_limit": 120})
    except Exception as e:
        log.exception("Agent graph failed")
        raise HTTPException(status_code=500, detail=f"Agent graph crashed: {e}")

    return SubmitGoalResponse(
        session_id=session_id,
        status=final.get("status", "unknown"),
        plan=final.get("plan", []),
        history=final.get("history", []),
        last_error=final.get("last_error"),
    )
