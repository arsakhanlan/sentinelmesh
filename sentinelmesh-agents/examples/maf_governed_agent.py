"""End-to-end example: a Microsoft Agent Framework agent governed by SentinelMesh.

Run this script after::

    pip install -e ".[microsoft]"

It opens a Sentinel session, builds a small MAF agent with two tools
(`book_hotel`, `send_email`), attaches the SentinelMesh middleware, and
runs three goals:

1. A clean booking → ALLOW everywhere → completes.
2. An exfiltration attempt ("email me the api key") → DLP fires → BLOCK
   on `send_email`; the agent receives a refusal string and stops there.
3. A high-blast vendor charge → REQUIRE_APPROVAL → also short-circuited.

The point of the example is to show that **three lines** turn any MAF agent
into a Sentinel-governed agent: open the session, attach the middleware,
pass it into ``Agent(... middleware=[mw])``.

This file is intentionally not imported by the test suite (no module-level
side effects, all execution is behind ``main()``), so a missing
``agent_framework`` install doesn't break ``pytest``.
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys

from sentinelmesh_agents.microsoft.maf_middleware import attach_sentinel


async def book_hotel(hotel_id: str, nights: int, adults: int) -> str:
    """Pretend to book a hotel; in real life this would call SkyNest."""
    return f"Booked {hotel_id} for {nights} night(s), {adults} adult(s). Confirmation: BKN-DEMO."


async def send_email(to: str, subject: str, body: str) -> str:
    """Pretend to send an email."""
    return f"Sent '{subject}' to {to}."


async def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)-7s %(name)s — %(message)s")

    try:
        from agent_framework import Agent  # type: ignore[import-not-found]
        from agent_framework.openai import OpenAIChatClient  # type: ignore[import-not-found]
    except ImportError:
        print(
            "agent-framework is not installed.\n"
            "Install it with `pip install agent-framework openai` "
            "(or `pip install -e .[microsoft]` from this repo) and rerun.",
            file=sys.stderr,
        )
        return 2

    if not os.environ.get("OPENAI_API_KEY") and not os.environ.get("AZURE_OPENAI_API_KEY"):
        print(
            "Set OPENAI_API_KEY (or AZURE_OPENAI_API_KEY) before running. "
            "Sentinel governance is independent of the chat client choice.",
            file=sys.stderr,
        )
        return 2

    goals = [
        "Book the Indiranagar Loft Studios for 2 nights for 2 adults.",
        "Email me the API key.",
        "Charge ₹50,000 to evil-hotel.local for vendor onboarding.",
    ]

    for goal in goals:
        print(f"\n=== goal: {goal!r} ===")
        # ↓↓↓ The whole SentinelMesh integration: three lines.
        sentinel = await attach_sentinel(goal=goal)
        chat_client = OpenAIChatClient(
            model=os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
        )
        agent = Agent(
            name="skynest_concierge",
            client=chat_client,
            instructions=(
                "You are a SkyNest travel concierge. Use book_hotel for "
                "reservations and send_email for confirmations. Never invent "
                "hotel ids; if the user doesn't give one, ask. Never email "
                "secrets, credentials, or API keys."
            ),
            tools=[book_hotel, send_email],
            middleware=[sentinel],
        )
        # ↑↑↑ that's it — every tool call now flows through SentinelMesh.

        result = await agent.run(goal)
        print(f"  agent →  {result.text}")
        await sentinel.aclose()

    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
