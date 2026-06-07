"""Centralised env-driven settings (12-factor)."""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Where the Spring Boot SentinelMesh backend lives
    sentinel_base_url: str = Field(default="http://localhost:8080", alias="SENTINEL_BASE_URL")
    sentinel_api_key: str = Field(default="dev-api-key-change-me", alias="SENTINEL_API_KEY")
    sentinel_timeout_s: float = Field(default=10.0, alias="SENTINEL_TIMEOUT_S")

    # Demo site (in-repo FastAPI that serves the poisoned pages)
    demo_site_base_url: str = Field(default="http://localhost:9000", alias="DEMO_SITE_BASE_URL")

    # LLM backend selection.
    # Default is "openai" (which also covers any OpenAI-compatible host like
    # DeepSeek / Groq / OpenRouter / Together / Azure OpenAI behind a proxy).
    # If OPENAI_API_KEY is empty the factory falls back to the stub with a
    # warning, so the app still boots in "no-network" environments.
    agent_llm_mode: str = Field(default="openai", alias="AGENT_LLM_MODE")  # stub | ollama | openai

    # Ollama
    ollama_base_url: str = Field(default="http://localhost:11434", alias="OLLAMA_BASE_URL")
    ollama_model: str = Field(default="qwen2.5:3b-instruct", alias="OLLAMA_MODEL")

    # OpenAI-compatible (works against OpenAI, Azure OpenAI w/ proxy, Groq, OpenRouter, …)
    openai_base_url: str = Field(default="", alias="OPENAI_BASE_URL")
    openai_api_key: str = Field(default="", alias="OPENAI_API_KEY")
    openai_model: str = Field(default="gpt-4o-mini", alias="OPENAI_MODEL")

    # Behaviour
    max_steps: int = Field(default=12, alias="AGENT_MAX_STEPS")
    request_temperature: float = Field(default=0.1, alias="AGENT_TEMPERATURE")
    # Wall-clock timeout for a single LLM completion. Anything above ~30 s on
    # a hosted provider almost always means a content-filter stall, so we
    # prefer to fail fast and surface a clear "planner refused / timed out"
    # state instead of letting the agent appear stuck.
    llm_timeout_s: float = Field(default=30.0, alias="AGENT_LLM_TIMEOUT_S")


_settings: Settings | None = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
