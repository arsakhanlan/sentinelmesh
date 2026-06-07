"""Pydantic models for the agent service's REST API."""

from __future__ import annotations

from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class SubmitGoalRequest(BaseModel):
    goal: str = Field(min_length=3, max_length=500)
    user_id: str = "demo-user"
    policy_bundle_id: str = "default"


class SubmitGoalResponse(BaseModel):
    session_id: UUID
    status: str
    plan: list[dict[str, Any]]
    history: list[dict[str, Any]]
    last_error: str | None = None


class HealthResponse(BaseModel):
    status: str
    llm: str
    sentinel_url: str
