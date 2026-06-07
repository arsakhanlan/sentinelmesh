"""Foundry Hosted Agent entrypoint for the SentinelMesh-protected booking agent.

This is the bridge that takes a Sentinel-governed Microsoft Agent Framework
agent and exposes it through Foundry's **Responses protocol** so it can be
packaged as a Foundry Hosted Agent — Microsoft Foundry's managed runtime
that runs each session in an isolated MicroVM with its own Entra identity
and built-in observability.

Foundry Hosted Agent contract (paraphrased from the docs):

* You instantiate an Agent Framework ``Agent`` with your tools and middleware.
* You wrap it in ``ResponsesHostServer`` (or ``InvocationsHostServer``) from
  ``agent_framework_foundry_hosting`` and call ``server.run()``.
* Foundry packages the result as a container image and runs it on its
  managed runtime, exposing it through the Responses API at the project
  endpoint.

Where SentinelMesh fits: we attach ``SentinelMiddleware`` to the Agent
*before* handing it to ``ResponsesHostServer``, which means every tool
call the hosted agent issues is governed by our policy engine — including
the ones triggered by Foundry Tools (file_search, code_interpreter,
web_search), platform tools (SharePoint, Fabric IQ), and any MCP servers
attached to the session.

Usage::

    # Local dev — run on http://localhost:8080 against the Responses protocol
    OPENAI_API_KEY=... python -m sentinelmesh_agents.microsoft.foundry_host

    # Build a container for Foundry Hosted Agents
    docker build -t sentinelmesh-booking-agent -f docker/Dockerfile.foundry .
    docker push <registry>/sentinelmesh-booking-agent:latest
    az foundry agents deploy --image <registry>/sentinelmesh-booking-agent:latest \\
        --project-endpoint <foundry-project-endpoint>

The script imports ``agent_framework`` lazily and prints an actionable error
when the package isn't installed, so the rest of ``sentinelmesh_agents``
still imports cleanly without the ``[microsoft]`` extra.
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys

from sentinelmesh_agents.microsoft import tracing as sm_tracing
from sentinelmesh_agents.microsoft.maf_middleware import attach_sentinel


# ---------- the booking agent's tools ----------

async def book_hotel(hotel_id: str, nights: int, adults: int = 2,
                     children: int = 0, check_in: str = "",
                     check_out: str = "") -> str:
    """Book a hotel by id. SentinelMesh inspects this call before it runs.

    For the hosted agent we just simulate a successful booking — the real
    call would hit the SkyNest demo backend. The signature is what the LLM
    sees and the Sentinel pipeline inspects.
    """
    return (f"Booked {hotel_id} for {nights} night(s), {adults} adult(s), "
            f"{children} child(ren) (check-in {check_in or 'today'}, "
            f"check-out {check_out or 'today + nights'}). Ref: BKN-DEMO.")


async def send_email(to: str, subject: str, body: str) -> str:
    """Send a confirmation email. Sentinel inspects body for secrets / PII /
    L2 categories before this runs."""
    return f"Sent '{subject}' to {to} ({len(body)} bytes)."


async def list_hotels(city: str, max_price_inr: int | None = None) -> str:
    """List SkyNest hotels in a given city, optionally under a price ceiling."""
    return f"Listing hotels in {city}" + (
        f" under ₹{max_price_inr}" if max_price_inr else "") + " (demo stub)."


# ---------- entrypoint ----------

async def main() -> int:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)-7s %(name)s — %(message)s",
    )
    log = logging.getLogger("sentinelmesh.foundry_host")

    try:
        from agent_framework import Agent  # type: ignore[import-not-found]
        from agent_framework.openai import OpenAIChatClient  # type: ignore[import-not-found]
    except ImportError:
        print(
            "agent-framework is not installed.\n"
            "Run `pip install -e .[microsoft]` to enable the Foundry host.",
            file=sys.stderr,
        )
        return 2

    # The Foundry hosting wrapper is a separate package — only loaded when
    # we're actually packaging for Foundry. For local dev (no Foundry
    # endpoint), we fall back to a plain run loop.
    try:
        from agent_framework_foundry_hosting import ResponsesHostServer  # type: ignore
    except ImportError:
        ResponsesHostServer = None  # type: ignore[assignment]
        log.warning(
            "agent_framework_foundry_hosting not installed; running in "
            "local-loop mode. Install for production deployment to Foundry."
        )

    # ---------- observability ----------
    # When OTEL_EXPORTER_OTLP_ENDPOINT is set (Foundry sets it automatically
    # on the hosted runtime), Sentinel decisions emit gen_ai-conventioned
    # spans into the same trace stream as the agent's ``invoke_agent`` and
    # ``execute_tool`` spans, so they show up natively in Foundry's Trace
    # explorer.
    sm_tracing.configure_tracing(service_name="sentinelmesh-booking-agent")

    # ---------- Sentinel session ----------
    goal = os.environ.get(
        "SENTINELMESH_AGENT_GOAL",
        "Help users book hotels and email confirmations on SkyNest, safely.",
    )
    sentinel = await attach_sentinel(
        goal=goal,
        user_id=os.environ.get("SENTINELMESH_USER_ID", "user@example.com"),
    )

    chat_client = OpenAIChatClient(
        model=os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
    )
    agent = Agent(
        name="skynest_concierge",
        client=chat_client,
        instructions=(
            "You are a SkyNest travel concierge. Use book_hotel for "
            "reservations, list_hotels for searches, and send_email for "
            "confirmations. Never invent hotel ids; if the user doesn't "
            "give one, ask. Never email secrets, credentials, or API keys."
        ),
        tools=[book_hotel, list_hotels, send_email],
        # ↓ This is the whole SentinelMesh integration: one item in the
        # middleware list. Every tool call now flows through the policy
        # engine before it executes, and Sentinel decisions become OTel
        # spans visible in Foundry's Trace explorer.
        middleware=[sentinel],
        default_options={"store": False},  # Foundry hosts session state
    )

    if ResponsesHostServer is not None:
        # Production path: hand the agent to Foundry's hosting wrapper.
        port = int(os.environ.get("PORT", "8080"))
        log.info("Starting Foundry ResponsesHostServer on :%d", port)
        server = ResponsesHostServer(agent, port=port)
        try:
            await server.run()
        finally:
            await sentinel.aclose()
        return 0

    # Local dev fallback: read goals from stdin.
    log.info("Local-loop mode. Type a goal and hit return; Ctrl-D to exit.")
    try:
        loop = asyncio.get_running_loop()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            result = await agent.run(line)
            print(f"\nagent → {result.text}\n")
    finally:
        await sentinel.aclose()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
