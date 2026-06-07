# SentinelMesh — Operations Console (Frontend)

An immersive security-operations dashboard for the SentinelMesh mesh. Built with **Next.js 14 (App Router)**, **Tailwind CSS**, and **Framer Motion**.

**Repo hub:** [../README.md](../README.md), [../SentinelMesh-Submission.md](../SentinelMesh-Submission.md), [../README_FOR_ARSALAN.md](../README_FOR_ARSALAN.md).

## What you get

| Panel | What it shows |
|---|---|
| **Top Bar** | Firehose connection state + tamper-evident audit-chain status |
| **Metrics Bar** | Live threat counts, policy rules, detect-latency p50/p95 |
| **Adversary Console** | One-click attack scenarios + free-text "run a live agent" box |
| **Reasoning Pipeline** | Animated graph: Goal → Planner → Executor → Sentinel → Tool, with Threat/Approval branches lighting up as events stream |
| **Live Agent Theater** | Real-time transcript of every plan, tool call, sentinel decision, threat, and approval |
| **Risk Index** | Animated radial gauge of the latest composite risk score |
| **Threat Feed** | Severity-coded threat cards with evidence |
| **Approval Center** | Human-in-the-loop queue — approve/deny with one tap |
| **Microsoft (`/microsoft`)** | ASR hero metrics, MAF + SK integration snippets, Foundry IQ preview |

Critical threats and `BLOCK`/`QUARANTINE` enforcement trigger a full-screen red
alert flash so the operator never misses an enforcement event.

## Data sources

- **Firehose (WebSocket):** `ws://<backend>/ws/events?token=<API_KEY>`
- **REST:** adversary scenarios, approvals, metrics, audit verify (all with `X-API-Key`)
- **Live agent goals:** `POST <agent>/goals`

## Configuration

All config is via `NEXT_PUBLIC_*` env vars (baked at build time):

| Var | Default | Purpose |
|---|---|---|
| `NEXT_PUBLIC_BACKEND_URL` | `http://localhost:8080` | REST base |
| `NEXT_PUBLIC_WS_URL` | `ws://localhost:8080` | Firehose base |
| `NEXT_PUBLIC_AGENT_URL` | `http://localhost:8090` | Agent service |
| `NEXT_PUBLIC_API_KEY` | `dev-api-key-change-me` | API key for REST + WS |

### Demo vs production (security)

The compose stack bakes **`NEXT_PUBLIC_*` into the browser bundle** and authenticates the WebSocket with **`?token=<API_KEY>`** on the query string. That is intentional for a **local / judge demo**: zero extra moving parts. It is **not** how you ship to production — keys would leak via build artifacts, browser history, and reverse-proxy access logs.

**v2 direction (one paragraph):** move browser auth to a **short-lived ticket** issued by `POST /api/v1/session/ws-ticket` (or similar) over `X-API-Key`, then connect with `Sec-WebSocket-Protocol` or `Sec-WebSocket-Key` + ticket in the first frame — never the long-lived API key in the URL. Until then, treat `NEXT_PUBLIC_API_KEY` as a **shared lab secret**, not a tenant credential.

## Run it — full stack (recommended)

The frontend is wired into the unified compose stack:

```bash
cd sentinelmesh-agents
docker compose up --build          # or: docker-compose up --build
```

Then open **http://localhost:3000**.

Services that come up: Postgres, Redis, backend (`:8080`), demo site (`:9000`),
agents (`:8090`), and **frontend (`:3000`)**.

## Run it — frontend only (local dev)

```bash
cd sentinelmesh-frontend
npm install
npm run dev        # http://localhost:3000
```

Requires the backend (`:8080`) and agents (`:8090`) to be running.

## Smoke test (Playwright)

With the stack up (`localhost:3000` serving the built or dev app):

```bash
cd sentinelmesh-frontend
npx playwright install   # once, downloads browsers
npm run test:e2e         # sets PLAYWRIGHT_BASE_URL=http://localhost:3000 by default
```

The spec loads `/`, asserts the shell + SOC chrome, and clicks the first adversary scenario when scenarios load (skips gracefully if the agents API is down).

## Using the console

1. Wait for the top bar to read **FIREHOSE LIVE** (green).
2. Click any scenario in the **Adversary Console** to drive the pipeline, or type
   a goal (e.g. *"Book a hotel in Lisbon"*) and hit **Run** to dispatch a real
   LangGraph agent (stub LLM by default).
3. Watch the **Reasoning Pipeline** light up and the **Theater** stream events.
4. When a payment-class action fires, an item appears in the **Approval Center** —
   approve or deny it and watch the agent resume/halt.
