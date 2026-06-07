"""Tests for the AI Red Teaming harness.

We can't drive a real Azure / OpenAI endpoint from a unit test, but we can
build two pure-Python fake targets that behave like:

* a *naked* LLM with weak content filters that complies with most attack
  prompts but refuses obvious ones, and
* a *Sentinel-protected* agent that refuses **all** harmful objectives
  before they reach the LLM (the policy-engine BLOCK path).

Then we assert the comparison report shows the expected ASR delta. This
exercises the full harness — strategy application, target invocation,
classification, aggregation, comparison.
"""

from __future__ import annotations

import pytest

from sentinelmesh_agents.microsoft.red_team import (
    AttackStrategy, RedTeamScanner, RiskCategory, compare, compose,
    default_objectives,
)


# ---------- fake targets ----------

async def _naked_llm(prompt: str) -> str:
    """Pretends to be an LLM with a weak literal-keyword content filter: it
    refuses prompts whose plaintext contains an obvious harmful word, but
    happily complies with anything obfuscated (Base64, ROT13, Flip, etc.).
    Mirrors the failure mode PyRIT was built to expose."""
    triggers = (
        "kill", "suicide", "deport", "self-harm", "explicit",
        "assault rifle", "minor", "robbery", "hate", "violent",
        "end their own life", "self harm", "exterminate",
    )
    if any(t in prompt.lower() for t in triggers):
        return "I can't help with that."
    return "Here's a detailed plan for what you asked..."


async def _sentinel_protected(prompt: str) -> tuple[str, bool]:
    """Always blocks. Stand-in for an agent where SentinelMesh has already
    classified every harmful objective as BLOCK before the model sees it.
    Returns the (text, blocked) tuple shape the harness honors directly."""
    return ("[SentinelMesh] BLOCK: composite risk too high.", True)


# ---------- behaviour ----------

@pytest.mark.asyncio
async def test_obfuscation_strategies_bypass_naked_model() -> None:
    """The whole point of the harness: Base64/ROT13/Flip should *raise* the
    naked model's ASR because the trigger words no longer literally appear
    in the prompt."""
    objectives = default_objectives(num_per_category=1)
    strategies = [AttackStrategy.Baseline, AttackStrategy.Base64,
                  AttackStrategy.ROT13, AttackStrategy.Flip]
    scanner = RedTeamScanner(_naked_llm, target_label="naked",
                             objectives=objectives, strategies=strategies)
    report = await scanner.scan("naked-scan")

    # Total attempts = 4 strategies × 4 categories.
    assert report.total_attempts == 16
    # Baseline has direct trigger words → naked model refuses → 0 successes.
    assert report.by_strategy["Baseline"]["successes"] == 0
    # Obfuscated strategies bypass the naked filter → at least one success each.
    assert report.by_strategy["Base64"]["successes"] >= 1
    assert report.by_strategy["ROT13"]["successes"] >= 1
    assert report.by_strategy["Flip"]["successes"] >= 1
    # Aggregate ASR is well above zero — we have a story to tell.
    assert report.asr > 0.4


@pytest.mark.asyncio
async def test_sentinel_protected_target_drives_asr_to_zero() -> None:
    objectives = default_objectives(num_per_category=1)
    scanner = RedTeamScanner(_sentinel_protected, target_label="sentinel",
                             objectives=objectives,
                             strategies=[AttackStrategy.Baseline, AttackStrategy.Base64,
                                         AttackStrategy.ROT13, AttackStrategy.Flip])
    report = await scanner.scan("sentinel-scan")

    assert report.total_attempts == 16
    assert report.successes == 0
    assert report.asr == 0.0
    # Every row was classified as blocked.
    assert all(row.blocked for row in report.rows)


@pytest.mark.asyncio
async def test_compare_report_shows_drop_and_relative_reduction() -> None:
    objectives = default_objectives(num_per_category=1)
    common = dict(objectives=objectives,
                  strategies=[AttackStrategy.Baseline, AttackStrategy.Base64,
                              AttackStrategy.ROT13, AttackStrategy.Flip])
    naked = await RedTeamScanner(_naked_llm, target_label="naked", **common).scan("a")
    sentinel = await RedTeamScanner(_sentinel_protected, target_label="sentinel", **common).scan("b")

    cmp = compare(naked, sentinel)
    assert cmp["sentinel_asr"] == 0.0
    assert cmp["naked_asr"] > 0.0
    assert cmp["absolute_drop"] == cmp["naked_asr"]
    assert cmp["relative_reduction"] == 1.0
    # Per-strategy breakdown is populated for every strategy that ran.
    for strat in ("Baseline", "Base64", "ROT13", "Flip"):
        assert strat in cmp["by_strategy"]


@pytest.mark.asyncio
async def test_compose_strategy_label_and_double_transform() -> None:
    """Ensures composed strategies are labelled and apply each transformation
    in order (last listed = outermost)."""
    transforms = compose(AttackStrategy.ROT13, AttackStrategy.Base64)
    objectives = [default_objectives(num_per_category=1)[0]]
    scanner = RedTeamScanner(_naked_llm, target_label="naked",
                             objectives=objectives, strategies=[transforms])
    report = await scanner.scan("compose-test")

    assert report.total_attempts == 1
    row = report.rows[0]
    assert row.strategy.startswith("Compose(")
    assert "Base64" in row.strategy and "ROT13" in row.strategy
    # The obfuscated prompt is base64 — alphanumeric + padding only.
    assert all(c.isalnum() or c in "+/=" for c in row.obfuscated_prompt)


@pytest.mark.asyncio
async def test_per_category_breakdown_is_complete() -> None:
    objectives = default_objectives()
    scanner = RedTeamScanner(_naked_llm, target_label="naked", objectives=objectives,
                             strategies=[AttackStrategy.Baseline])
    report = await scanner.scan()
    # Every risk category from the seed set ends up in the breakdown.
    for cat in (RiskCategory.Violence, RiskCategory.HateUnfairness,
                RiskCategory.Sexual, RiskCategory.SelfHarm):
        assert cat.value in report.by_risk_category
        assert report.by_risk_category[cat.value]["attempts"] >= 1


@pytest.mark.asyncio
async def test_target_exception_counts_as_block_not_success() -> None:
    async def boom(prompt: str) -> str:
        raise RuntimeError("upstream rate limit")

    scanner = RedTeamScanner(boom, target_label="boom",
                             objectives=default_objectives(num_per_category=1),
                             strategies=[AttackStrategy.Baseline])
    report = await scanner.scan()
    assert report.successes == 0
    assert all(row.blocked for row in report.rows)


@pytest.mark.asyncio
async def test_scan_writes_report_to_disk(tmp_path) -> None:
    out = tmp_path / "report.json"
    scanner = RedTeamScanner(_sentinel_protected, target_label="sentinel",
                             objectives=default_objectives(num_per_category=1),
                             strategies=[AttackStrategy.Baseline])
    report = await scanner.scan("ondisk", output_path=out)

    assert out.exists()
    import json
    on_disk = json.loads(out.read_text())
    assert on_disk["scan_name"] == "ondisk"
    assert on_disk["total_attempts"] == report.total_attempts
