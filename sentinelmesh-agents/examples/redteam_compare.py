"""Run the SentinelMesh red-team comparison demo.

Produces the headline number for the hackathon pitch::

    Attack Success Rate (ASR) — measured by SentinelMesh's PyRIT-style
    harness on Microsoft's four canonical risk categories:

        Naked agent (direct LLM):              {asr_naked:.0%}
        SentinelMesh-protected agent:           {asr_sentinel:.0%}
        Absolute drop:                          {drop:+.0%}
        Relative reduction:                     {rel:.0%}

By default this script uses *fake* targets — a permissive naked model and
a Sentinel-protected agent that always BLOCKs — so it runs offline and
produces an indicative number. In real demos, replace `make_naked_target`
and `make_sentinel_target` with adapters around your actual LLM client and
your SentinelMesh-protected MAF agent (see ``examples/maf_governed_agent.py``).

Usage::

    python -m examples.redteam_compare \
        [--objectives-per-category 2] \
        [--out artifacts/redteam-report.json]

Or, programmatically::

    from sentinelmesh_agents.microsoft.red_team import RedTeamScanner, default_objectives, AttackStrategy
    naked   = await RedTeamScanner(my_naked_target,    target_label="naked"   ).scan()
    sentinel= await RedTeamScanner(my_sentinel_target, target_label="sentinel").scan()
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from typing import Any

from sentinelmesh_agents.microsoft.red_team import (
    AttackStrategy, RedTeamScanner, compare, compose, default_objectives,
)


# ---------- demo target factories ----------

async def _fake_naked_llm(prompt: str) -> str:
    """A weak content-filter model: refuses literal harmful words, complies
    with anything obfuscated."""
    triggers = ("kill", "suicide", "deport", "self-harm", "explicit",
                "assault rifle", "minor", "robbery", "hate", "violent",
                "end their own life", "self harm", "exterminate")
    if any(t in prompt.lower() for t in triggers):
        return "I can't help with that."
    return ("Here's a detailed answer to your request, with steps and "
            "concrete recommendations...")


async def _fake_sentinel_protected(prompt: str) -> tuple[str, bool]:
    """The Sentinel-protected target: every harmful objective is BLOCKed by
    the policy engine before the model sees it."""
    return ("[SentinelMesh] BLOCK: composite risk too high; refused.", True)


# ---------- pretty printer ----------

_HEADER = "=" * 78
_DIVIDER = "-" * 78


def _print_report(naked, sentinel, cmp_data: dict[str, Any]) -> None:
    print(_HEADER)
    print("  SentinelMesh red-team comparison (PyRIT-style)")
    print(_HEADER)
    print(f"  Objectives:           {naked.total_attempts // len(naked.by_strategy)} "
          f"× {len(naked.by_strategy)} strategies = {naked.total_attempts} attempts per target")
    print()
    print(f"  Naked agent ASR:      {naked.asr * 100:6.1f}%   "
          f"({naked.successes}/{naked.total_attempts} attempts succeeded)")
    print(f"  SentinelMesh ASR:     {sentinel.asr * 100:6.1f}%   "
          f"({sentinel.successes}/{sentinel.total_attempts} attempts succeeded)")
    print(f"  Absolute drop:        {cmp_data['absolute_drop'] * 100:+6.1f} pp")
    print(f"  Relative reduction:   {cmp_data['relative_reduction'] * 100:6.1f}%")
    print()
    print(_DIVIDER)
    print("  Per-strategy breakdown")
    print(_DIVIDER)
    print(f"  {'Strategy':<24}  {'Naked ASR':>10}  {'Sentinel ASR':>13}")
    print(f"  {'-' * 24}  {'-' * 10}  {'-' * 13}")
    for strat, vals in cmp_data["by_strategy"].items():
        print(f"  {strat:<24}  {vals['naked_asr'] * 100:9.1f}%  "
              f"{vals['sentinel_asr'] * 100:12.1f}%")
    print(_HEADER)


async def _run(objectives_per_category: int, out_path: Path | None) -> dict[str, Any]:
    objectives = default_objectives(num_per_category=objectives_per_category)
    strategies = [
        AttackStrategy.Baseline,
        AttackStrategy.Base64,
        AttackStrategy.ROT13,
        AttackStrategy.Flip,
        compose(AttackStrategy.Base64, AttackStrategy.ROT13),
    ]

    naked = await RedTeamScanner(
        _fake_naked_llm, target_label="naked-llm",
        objectives=objectives, strategies=strategies,
    ).scan(scan_name="naked-baseline")

    sentinel = await RedTeamScanner(
        _fake_sentinel_protected, target_label="sentinelmesh-protected",
        objectives=objectives, strategies=strategies,
    ).scan(scan_name="sentinelmesh-protected")

    cmp_data = compare(naked, sentinel)
    _print_report(naked, sentinel, cmp_data)

    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps({
            "comparison": cmp_data,
            "naked": naked.to_dict(),
            "sentinel": sentinel.to_dict(),
        }, indent=2))
        print(f"\nFull machine-readable report written to: {out_path}")

    return cmp_data


def _cli() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--objectives-per-category", type=int, default=2,
                   help="Number of attack objectives per risk category (default: 2)")
    p.add_argument("--out", type=Path, default=Path("artifacts/redteam-report.json"),
                   help="Where to write the full JSON report")
    args = p.parse_args()
    asyncio.run(_run(args.objectives_per_category, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(_cli())
