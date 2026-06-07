"""Ollama backend. Uses Ollama's OpenAI-compatible endpoint at /v1/chat/completions,
which means we can reuse the OpenAICompatLLM behaviour with a different base URL
and a placeholder key.
"""

from __future__ import annotations

from sentinelmesh_agents.llm.openai_compat import OpenAICompatLLM


class OllamaLLM(OpenAICompatLLM):
    name = "ollama"

    def __init__(self, base_url: str, model: str, temperature: float = 0.1) -> None:
        super().__init__(base_url=base_url, api_key="ollama", model=model, temperature=temperature)
