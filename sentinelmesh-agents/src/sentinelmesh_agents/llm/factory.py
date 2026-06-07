"""Pick an LLM based on configuration. Default falls back to the stub so the app
boots regardless of what's installed."""

from __future__ import annotations

import logging

from sentinelmesh_agents.config import get_settings
from sentinelmesh_agents.llm.base import LLM
from sentinelmesh_agents.llm.ollama_client import OllamaLLM
from sentinelmesh_agents.llm.openai_compat import OpenAICompatLLM
from sentinelmesh_agents.llm.stub import StubLLM

log = logging.getLogger(__name__)


def build_llm() -> LLM:
    s = get_settings()
    mode = (s.agent_llm_mode or "stub").strip().lower()

    if mode == "ollama":
        log.info("LLM backend: Ollama (%s, model=%s)", s.ollama_base_url, s.ollama_model)
        return OllamaLLM(base_url=s.ollama_base_url, model=s.ollama_model,
                          temperature=s.request_temperature)
    if mode in ("openai", "azure", "openai-compat"):
        if not s.openai_api_key:
            log.warning("AGENT_LLM_MODE=%s requested but OPENAI_API_KEY empty; falling back to stub", mode)
            return StubLLM()
        log.info("LLM backend: OpenAI-compatible (%s, model=%s)", s.openai_base_url or "default OpenAI", s.openai_model)
        return OpenAICompatLLM(base_url=s.openai_base_url or None,
                                api_key=s.openai_api_key, model=s.openai_model,
                                temperature=s.request_temperature,
                                timeout_s=s.llm_timeout_s)

    log.info("LLM backend: STUB (deterministic; set AGENT_LLM_MODE to upgrade)")
    return StubLLM()
