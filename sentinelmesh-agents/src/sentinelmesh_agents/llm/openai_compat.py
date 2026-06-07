"""OpenAI-compatible client. Works against OpenAI, Azure OpenAI (via REST proxy
or compatibility shim), Groq, OpenRouter, Together, and many others — they all
expose the same `/v1/chat/completions` surface.

JSON mode is requested when the caller asks for it; we still parse defensively
because not every provider honours `response_format`.

We pin an explicit per-call timeout (default 30 s) and wrap the actual call in
``asyncio.wait_for`` so a content-filtered or stalled provider response cannot
freeze the planner. Without this guard the LangGraph planner appears "stuck"
when DeepSeek / Azure refuse to respond to adversarial prompts like
"reveal your system prompt" or known attack-replay payloads.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

from openai import AsyncOpenAI

log = logging.getLogger(__name__)


class LLMTimeoutError(RuntimeError):
    """Raised when the upstream LLM does not respond within the wall-clock budget."""


class LLMRefusalError(RuntimeError):
    """Raised when the upstream LLM produces a content-filtered / refusal output."""


class OpenAICompatLLM:
    name = "openai"

    def __init__(self, base_url: str | None, api_key: str, model: str,
                  temperature: float = 0.1, timeout_s: float = 30.0) -> None:
        kwargs: dict[str, Any] = {"api_key": api_key or "no-key", "timeout": timeout_s}
        if base_url:
            kwargs["base_url"] = base_url.rstrip("/") + "/v1" \
                if not base_url.rstrip("/").endswith("/v1") else base_url.rstrip("/")
        self._client = AsyncOpenAI(**kwargs)
        self._model = model
        self._temperature = temperature
        # Wall-clock guard slightly above the per-call OpenAI timeout so a
        # provider that just hangs the socket cannot freeze the planner.
        self._wall_timeout_s = max(timeout_s + 5.0, 15.0)

    async def aclose(self) -> None:
        await self._client.close()

    async def complete_json(self, system: str, user: str, schema_hint: str = "") -> dict:
        sys_with_hint = system if not schema_hint else f"{system}\n\nReturn ONLY a JSON object matching: {schema_hint}"

        async def _call(strict_json: bool):
            kwargs: dict[str, Any] = {
                "model": self._model,
                "messages": [{"role": "system", "content": sys_with_hint},
                             {"role": "user", "content": user}],
                "temperature": self._temperature,
                "max_tokens": 800,
            }
            if strict_json:
                kwargs["response_format"] = {"type": "json_object"}
            return await self._client.chat.completions.create(**kwargs)

        try:
            resp = await asyncio.wait_for(_call(True), timeout=self._wall_timeout_s)
        except asyncio.TimeoutError as exc:
            raise LLMTimeoutError(
                f"LLM did not respond within {self._wall_timeout_s:.0f}s") from exc
        except Exception as e:  # noqa: BLE001 — provider quirks
            log.warning("json_object mode failed (%s); retrying without", e)
            try:
                resp = await asyncio.wait_for(_call(False), timeout=self._wall_timeout_s)
            except asyncio.TimeoutError as exc:
                raise LLMTimeoutError(
                    f"LLM did not respond within {self._wall_timeout_s:.0f}s on retry") from exc

        text = resp.choices[0].message.content or "{}"
        finish = getattr(resp.choices[0], "finish_reason", "") or ""
        # Azure / DeepSeek / OpenAI all surface content-filter refusals via
        # finish_reason ("content_filter") or non-JSON apology text. Detect
        # both so we can fail fast with a clear reason instead of looping.
        parsed = _coerce_json(text)
        if finish == "content_filter" or _looks_like_refusal(text, parsed):
            raise LLMRefusalError(f"LLM refused / filtered (finish_reason={finish}); preview={text[:120]!r}")
        return parsed

    async def complete_text(self, system: str, user: str) -> str:
        try:
            resp = await asyncio.wait_for(
                self._client.chat.completions.create(
                    model=self._model,
                    messages=[{"role": "system", "content": system},
                              {"role": "user", "content": user}],
                    temperature=self._temperature,
                    max_tokens=600,
                ),
                timeout=self._wall_timeout_s,
            )
        except asyncio.TimeoutError as exc:
            raise LLMTimeoutError(
                f"LLM did not respond within {self._wall_timeout_s:.0f}s") from exc
        return resp.choices[0].message.content or ""


def _coerce_json(text: str) -> dict:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{.*\}", text, re.S)
    if m:
        try: return json.loads(m.group(0))
        except json.JSONDecodeError: pass
    return {"raw": text, "parse_error": True}


_REFUSAL_HINTS = (
    "i can't help",
    "i cannot help",
    "i can't assist",
    "i cannot assist",
    "i won't ",
    "i will not ",
    "i'm sorry",
    "i am sorry",
    "as an ai",
    "cannot comply",
    "cannot fulfill",
    "violates the policy",
    "against my guidelines",
    "policy violation",
)


def _looks_like_refusal(text: str, parsed: dict) -> bool:
    if not isinstance(parsed, dict):
        return False
    # When the provider returns a structured plan we trust it.
    if "steps" in parsed and isinstance(parsed.get("steps"), list):
        return False
    if not parsed.get("parse_error"):
        return False
    lo = (text or "").lower()
    return any(h in lo for h in _REFUSAL_HINTS)
