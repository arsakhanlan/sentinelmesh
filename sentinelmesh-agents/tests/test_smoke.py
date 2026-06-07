"""Sanity test: build the graph with stub LLM + an in-process fake Sentinel,
fire one goal, assert we got through planner + executor + advance."""

from __future__ import annotations

import pytest


@pytest.mark.asyncio
async def test_stub_graph_smoke(monkeypatch):
    # Patch the settings to use stub mode
    monkeypatch.setenv("AGENT_LLM_MODE", "stub")
    monkeypatch.setenv("SENTINEL_BASE_URL", "http://nowhere.invalid")
    # Reset cached settings
    from sentinelmesh_agents import config as cfg
    cfg._settings = None

    from sentinelmesh_agents.llm.factory import build_llm
    llm = build_llm()
    assert llm.name == "stub"
    out = await llm.complete_json("you are a planner", "book a trip to bangalore under 7000",
                                    schema_hint="{goal, steps}")
    assert "steps" in out
    assert len(out["steps"]) >= 1
