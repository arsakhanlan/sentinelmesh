"""Process-scoped runtime: builds the LLM, Sentinel client, tool registry, and
compiled LangGraph once, and exposes them via :func:`get_runtime`.

Lifecycle is managed in :func:`main.lifespan` so we have one shared session
across the FastAPI process.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from sentinelmesh_agents.llm.base import LLM
from sentinelmesh_agents.sentinel.client import SentinelClient
from sentinelmesh_agents.tools.registry import ToolRegistry


@dataclass
class Runtime:
    llm: LLM
    sentinel: SentinelClient
    tools: ToolRegistry
    graph: Any  # CompiledStateGraph


_runtime: Runtime | None = None


def set_runtime(rt: Runtime) -> None:
    global _runtime
    _runtime = rt


def get_runtime() -> Runtime:
    if _runtime is None:
        raise RuntimeError("Runtime not initialised; called outside of FastAPI lifespan?")
    return _runtime
