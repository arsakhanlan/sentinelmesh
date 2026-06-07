# SentinelMesh Agents

Real LangGraph-based AI agents that consult the SentinelMesh backend for every action and every fetched piece of content. Companion to the [Spring Boot backend](../sentinelmesh-backend).

**Repo hub:** [../README.md](../README.md), [../SentinelMesh-Submission.md](../SentinelMesh-Submission.md), [../README_FOR_ARSALAN.md](../README_FOR_ARSALAN.md). **PDF:** from `sentinelmesh-agents` run `pip install -e ".[docs]"` then `python ../tools/build_submission_pdf.py`.

## End-to-end tests (live Docker)

With the full stack up (`localhost` 8080 / 8090 / 9000), run:

```bash
cd sentinelmesh-agents
SENTINELMESH_E2E=1 .venv/bin/python -m pytest -m e2e -q
```

See `tests/test_end_to_end.py` — drives SkyNest, backend inspect, booking idempotency, concurrency, outbox, and (optionally) a slow real concierge goal.

## What this is

A FastAPI service that, given a goal, runs a multi-step LangGraph plan with:
- **Real Playwright browser** (Chromium in a headless container)
- **Real Sentinel integration** (every outbound action and inbound content scanned)
- **Pluggable LLM**: stub (default, zero install), Ollama (free local), or any OpenAI-compatible API

The full demo: agent receives goal → planner produces a plan → executor calls a tool → Sentinel inspects → if a poisoned page is fetched, Sentinel blocks it → planner re-plans on a safer source.

## Run the whole stack with one command

```bash
cd sentinelmesh-agents
docker compose up --build
```

That brings up:

| Service | Port | What |
|---|---|---|
| `postgres` | 5432 | Postgres 16 (sessions, threats, approvals, audit) |
| `redis` | 6379 | Pub/sub for live events |
| `backend` | 8080 | Spring Boot SentinelMesh |
| `demo-site` | 9000 | Tiny site serving poisoned + clean HTML pages |
| `agents` | 8090 | This LangGraph agent service |
| `ollama` *(optional)* | 11434 | Local LLM, only with `--profile llm` |

First build takes ~5 min (downloads Playwright base image, builds Spring jar, installs Python deps). Subsequent runs are seconds.

## Try it

```bash
# 1. Submit a goal — stub LLM produces a plan that touches the poisoned page
curl -s -X POST http://localhost:8090/goals \
  -H "Content-Type: application/json" \
  -d '{"goal":"Book a business trip to Bangalore under 7000 INR"}' | jq

# Response includes the session_id, the plan, and the per-step history with
# Sentinel verdicts. Look for steps where "sentinel_decision":"BLOCK" or "REWRITE".

# 2. See the threats Sentinel detected (against the backend on :8080)
SID="<session_id from step 1>"
curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/sessions/$SID/threats | jq

# 3. Watch the live stream (browser console)
new WebSocket("ws://localhost:8080/ws/events").onmessage = e => console.log(JSON.parse(e.data));

# 4. Verify the audit chain
curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/audit/verify | jq
```

Outbound `POST /api/v1/sentinel/inspect` may include **`originActor`** and **`currentActor`** (the LangGraph graph sets `planner` / `executor`) so the backend **CAP** scanner can block confused-deputy patterns. See scenario **`capability_escalation`** on the SOC.

## LLM modes — pick your level

```bash
# Default (no setup): canned plans, real LangGraph orchestration, real tools.
docker compose up --build

# Real local LLM via Ollama (downloads ~2GB qwen2.5:3b-instruct on first start).
AGENT_LLM_MODE=ollama docker compose --profile llm up --build

# Any OpenAI-compatible API (Azure OpenAI, OpenAI, Groq free tier, OpenRouter, ...).
# Example with Groq's free tier:
AGENT_LLM_MODE=openai \
OPENAI_BASE_URL=https://api.groq.com/openai \
OPENAI_API_KEY=gsk_xxx \
OPENAI_MODEL=llama-3.1-70b-versatile \
docker compose up --build
```

The agent code never mentions a specific provider — it just calls `LLM.complete_json(...)`. Provider selection is a single env var.

## Architecture

```
        ┌────────────┐
        │  Client    │  curl, frontend, anything
        └─────┬──────┘
              │  POST /goals
              ▼
┌─────────────────────────────────┐         ┌──────────────────────────┐
│  Agents service (FastAPI)       │  HTTP   │ Spring Boot backend      │
│  ┌───────────────────────────┐  │ ──────▶ │  /api/v1/sentinel/inspect│
│  │  LangGraph orchestrator   │  │         │  /api/v1/events          │
│  │  planner → executor       │  │         │  /api/v1/sessions/...    │
│  │       → sentinel_out      │  │         │                          │
│  │       → run_tool          │  │         │  (security pipeline,     │
│  │       → sentinel_in       │  │         │   policy, audit, approvals│
│  │       → advance / replan  │  │         │   websockets)            │
│  └─────────┬──────┬──────────┘  │         └──────────────────────────┘
│            │      │             │
│      Playwright   HTTP / mocks  │
│            │      │             │
│            ▼      ▼             │
│  http://demo-site:9000          │  poisoned + clean pages
└─────────────────────────────────┘
```

### File map

```
src/sentinelmesh_agents/
├── main.py                 FastAPI app + lifespan
├── runtime.py              process-scoped runtime singleton
├── config.py               12-factor settings
├── api/
│   ├── routes.py           POST /goals, GET /health
│   └── models.py           Pydantic
├── agents/
│   ├── state.py            AgentState TypedDict
│   ├── graph.py            ←── the LangGraph wiring
│   └── prompts.py          system prompts
├── llm/
│   ├── base.py             LLM Protocol
│   ├── stub.py             zero-install canned plans
│   ├── ollama_client.py    Ollama (uses OpenAI-compat surface)
│   ├── openai_compat.py    OpenAI/Azure/Groq/OpenRouter
│   └── factory.py          mode → impl
├── sentinel/
│   └── client.py           HTTP client for the Spring backend
├── tools/
│   ├── registry.py         Tool dataclass + registry
│   ├── browser.py          Playwright wrapper
│   ├── http_tool.py        httpx GET
│   ├── mock_tools.py       email.send, payments.charge, notes.append
│   └── defaults.py         build_default_registry()
└── demo_site/
    └── server.py           tiny FastAPI serving poisoned + clean HTML
```

### What "real agent" means here

- **Real LangGraph** — typed state, conditional edges, replan loop.
- **Real Sentinel boundary** — every tool call goes `executor → sentinel_out → run_tool → sentinel_in`. The security check is not a comment in the agent prompt; it's a network hop that happens whether the LLM wants it to or not.
- **Real Playwright** — Chromium fetches `http://demo-site:9000/poisoned-hotel` over the compose network. Hidden DOM in real HTML; not a string literal.
- **Real LLM (when enabled)** — flip `AGENT_LLM_MODE`; same code path.

### What's still a stub

- **Approvals** — when Sentinel returns `REQUIRE_APPROVAL`, the graph stops at `awaiting_approval` and returns. A v2 enhancement would poll the backend approval endpoint and re-enter the graph on decide.
- **Validator agent** — skipped for v1; the planner re-plans on failures instead.
- **Memory / RAG** — none in v1; planner gets the raw goal + tool catalogue.

## Standalone — only the agents

If the backend is already running on `localhost:8080`:

```bash
pip install -r requirements.txt
playwright install --with-deps chromium
SENTINEL_BASE_URL=http://localhost:8080 SENTINEL_API_KEY=dev-api-key-change-me \
  DEMO_SITE_BASE_URL=http://localhost:9000 \
  uvicorn sentinelmesh_agents.demo_site.server:app --port 9000 &
uvicorn sentinelmesh_agents.main:app --port 8090
```

## License

Hackathon code; do what you want with it.
