"""Minimal LLM Protocol that returns either free-text or JSON-mode responses.

All implementations are async. Three live in this package:
  * :class:`stub.StubLLM`           — canned plans; works with zero install.
  * :class:`ollama_client.OllamaLLM`— local Ollama via its OpenAI-compatible API.
  * :class:`openai_compat.OpenAICompatLLM` — OpenAI, Azure OpenAI, Groq, ...
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass
class LLMMessage:
    role: str       # "system" | "user" | "assistant"
    content: str


@runtime_checkable
class LLM(Protocol):
    """Minimal contract every backend implements."""

    name: str

    async def complete_json(self, system: str, user: str, schema_hint: str = "") -> dict:
        """Return a JSON object (dict). Implementations must coerce non-JSON to a dict
        on best-effort basis; never raise on parse errors — raise only on transport."""
        ...

    async def complete_text(self, system: str, user: str) -> str:
        ...

    async def aclose(self) -> None:
        ...
