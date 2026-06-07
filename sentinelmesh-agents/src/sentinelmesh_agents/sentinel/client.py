"""HTTP client for the Spring Boot SentinelMesh backend.

Every agent that needs a security decision OR wants to publish an event to the
audit log / websocket stream goes through this client. Keeps all backend
coupling in one file.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any
from uuid import UUID, uuid4

import httpx

from sentinelmesh_agents.config import get_settings

log = logging.getLogger(__name__)


@dataclass
class InspectResult:
    decision: str
    reason: str
    composite_risk: float
    blast_radius: float
    scores: dict[str, float]
    findings: dict[str, Any]
    approval_id: UUID | None
    raw: dict[str, Any]
    rewritten_args: dict[str, Any] | None = None
    rewritten_content: str | None = None

    @property
    def allowed(self) -> bool:
        return self.decision in {"ALLOW", "REWRITE"}

    @property
    def needs_approval(self) -> bool:
        return self.decision == "REQUIRE_APPROVAL"

    @property
    def blocked(self) -> bool:
        return self.decision in {"BLOCK", "QUARANTINE"}


class SentinelClient:
    def __init__(self) -> None:
        s = get_settings()
        self._base = s.sentinel_base_url.rstrip("/")
        self._headers = {"X-API-Key": s.sentinel_api_key, "Content-Type": "application/json"}
        self._client = httpx.AsyncClient(timeout=s.sentinel_timeout_s, headers=self._headers)

    async def close(self) -> None:
        await self._client.aclose()

    # -------- session lifecycle --------

    async def create_session(self, user_id: str, goal: str, policy_bundle_id: str = "default") -> UUID:
        r = await self._client.post(
            f"{self._base}/api/v1/sessions",
            json={"userId": user_id, "goal": goal, "policyBundleId": policy_bundle_id},
        )
        r.raise_for_status()
        return UUID(r.json()["id"])

    # -------- pipeline inspection --------

    async def inspect_outbound(
        self,
        session_id: UUID,
        action_id: UUID,
        tool: str,
        args: dict[str, Any],
        *,
        origin_actor: str | None = None,
        current_actor: str | None = None,
    ) -> InspectResult:
        body: dict[str, Any] = {
            "sessionId": str(session_id),
            "actionId": str(action_id),
            "direction": "OUTBOUND",
            "tool": tool,
            "args": args,
        }
        if origin_actor:
            body["originActor"] = origin_actor
        if current_actor:
            body["currentActor"] = current_actor
        return await self._inspect(body)

    async def inspect_inbound(self, session_id: UUID, action_id: UUID,
                               tool: str, content: str, meta: dict[str, Any] | None = None) -> InspectResult:
        return await self._inspect({
            "sessionId": str(session_id),
            "actionId": str(action_id),
            "direction": "INBOUND",
            "tool": tool,
            "content": content,
            "meta": meta or {},
        })

    async def _inspect(self, body: dict[str, Any]) -> InspectResult:
        r = await self._client.post(f"{self._base}/api/v1/sentinel/inspect", json=body)
        r.raise_for_status()
        data = r.json()
        approval_id = UUID(data["approvalId"]) if data.get("approvalId") else None
        return InspectResult(
            decision=data["decision"],
            reason=data.get("reason", ""),
            composite_risk=float(data.get("compositeRisk", 0.0)),
            blast_radius=float(data.get("blastRadius", 0.0)),
            scores=data.get("scores", {}) or {},
            findings=data.get("findings", {}) or {},
            approval_id=approval_id,
            raw=data,
            rewritten_args=data.get("rewrittenArgs"),
            rewritten_content=data.get("rewrittenContent"),
        )

    # -------- event publishing --------

    async def publish(self, session_id: UUID, kind: str, actor: str,
                       payload: dict[str, Any]) -> UUID:
        r = await self._client.post(
            f"{self._base}/api/v1/events",
            json={"sessionId": str(session_id), "kind": kind, "actor": actor, "payload": payload},
        )
        r.raise_for_status()
        return UUID(r.json()["eventId"])

    async def publish_plan(self, session_id: UUID, goal: str, plan: list[dict[str, Any]]) -> None:
        await self.publish(session_id, "plan", "planner", {"goal": goal, "plan": plan})

    async def publish_tool_call(self, session_id: UUID, tool: str, args: dict[str, Any]) -> None:
        await self.publish(session_id, "tool_call", "executor", {"tool": tool, "args": args})

    async def publish_tool_result(self, session_id: UUID, tool: str,
                                   result: dict[str, Any], sample: str = "") -> None:
        await self.publish(session_id, "tool_result", "executor",
                            {"tool": tool, "result": result, "sample": sample})

    async def publish_state(self, session_id: UUID, from_state: str, to_state: str) -> None:
        await self.publish(session_id, "state_transition", "agent",
                            {"from": from_state, "to": to_state})

    # -------- approvals (used by graph when REQUIRE_APPROVAL) --------

    async def get_approval(self, approval_id: UUID) -> dict[str, Any]:
        r = await self._client.get(f"{self._base}/api/v1/approvals/{approval_id}")
        r.raise_for_status()
        return r.json()


def new_action_id() -> UUID:
    """Lightweight id for correlating one tool call across publish + inspect."""
    return uuid4()
