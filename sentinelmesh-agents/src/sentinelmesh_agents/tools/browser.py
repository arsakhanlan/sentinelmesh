"""Playwright-driven browser tool. Spins up Chromium in headless mode, opens
the URL in an isolated context (fresh cookies/storage), extracts a substantial
text + selected HTML payload so the inbound Sentinel scan can see both the
visible text and the hidden DOM.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from playwright.async_api import async_playwright, Browser, BrowserContext

from sentinelmesh_agents.tools.registry import Tool

log = logging.getLogger(__name__)


class BrowserSession:
    """Single shared Browser instance; one context per goto for isolation."""

    _browser: Browser | None = None
    _lock = asyncio.Lock()

    @classmethod
    async def _ensure(cls) -> Browser:
        async with cls._lock:
            if cls._browser is None:
                pw = await async_playwright().start()
                cls._browser = await pw.chromium.launch(headless=True, args=[
                    "--no-sandbox", "--disable-dev-shm-usage"])
            return cls._browser

    @classmethod
    async def new_context(cls) -> BrowserContext:
        b = await cls._ensure()
        return await b.new_context(user_agent="SentinelMeshAgent/0.1 (+demo)")

    @classmethod
    async def shutdown(cls) -> None:
        if cls._browser is not None:
            await cls._browser.close()
            cls._browser = None


async def _browser_goto(args: dict[str, Any]) -> dict[str, Any]:
    url = args["url"]
    ctx = await BrowserSession.new_context()
    try:
        page = await ctx.new_page()
        resp = await page.goto(url, wait_until="domcontentloaded", timeout=15_000)
        status = resp.status if resp else 0
        # Pull both visible text and outerHTML so Sentinel sees hidden DOM.
        text = await page.evaluate("() => document.body ? document.body.innerText : ''")
        html = await page.content()
        log.info("browser.goto %s → %d (%d chars text, %d chars html)", url, status, len(text), len(html))
        # Truncate to a reasonable size for the LLM context window and Sentinel call.
        return {
            "url": url,
            "status": status,
            "text": text[:4000],
            "html_sample": html[:8000],
        }
    finally:
        await ctx.close()


def browser_goto_tool() -> Tool:
    return Tool(
        name="browser.goto",
        description="Navigate to a URL in an isolated browser context; returns text + html sample.",
        args_schema={"url": "string"},
        fn=_browser_goto,
    )
