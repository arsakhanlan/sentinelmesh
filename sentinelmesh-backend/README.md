# SentinelMesh — Backend

> Runtime security mesh for autonomous AI agents.  
> Java 21 + Spring Boot 3.3, hexagonal architecture.  
> Microsoft Build AI Hackathon 2026.

**Repo hub:** [../README.md](../README.md) (full submission), [../SentinelMesh-Submission.md](../SentinelMesh-Submission.md) (printable narrative), [../README_FOR_ARSALAN.md](../README_FOR_ARSALAN.md) (live demo steps).

This module is the **security backend**: REST inspect API, layered scanner pipeline (L1–L7, CAP, DLP), YAML policy engine + read-only simulator, capability budgets, multi-tenant API keys, approval workflow, hash-chained audit log, Redis WebSocket firehose, and an **adversary scenario** API for repeatable demos. Production agents (LangGraph in `sentinelmesh-agents`) call the same inspect contract as the built-in simulator.

---

## Quick start (Docker — no Java, no Gradle needed)

You only need **Docker** locally. The Spring Boot app, Postgres, and Redis all come up via Compose.

```bash
cd sentinelmesh-backend
docker compose up --build         # first run pulls images + builds; ~3-4 min
# (older Docker without v2 plugin: use `docker-compose up --build`)
```

Wait for the log line:
```
Started SentinelMeshApplication in X seconds
```

Then explore:

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui | Interactive API explorer |
| http://localhost:8080/actuator/health | Liveness probe |
| http://localhost:8080/actuator/prometheus | Metrics |
| http://localhost:8080/v3/api-docs | OpenAPI spec |

All non-public endpoints require the header `X-API-Key: dev-api-key-change-me` (override via `SENTINELMESH_API_KEY` env).

### Tear down
```bash
docker compose down            # stop containers
docker compose down -v         # also wipe the postgres volume
```

---

## Try the demo flow

### 1. List available adversary scenarios
```bash
curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/adversary/scenarios | jq
```

You'll see scenarios including `capability_escalation`, `hidden_prompt_injection`, `credential_theft`, `malicious_workflow`, `phishing_email`, `unauthorized_payment`, `agent_identity_spoof`, `budget_runaway`.

### 2. Fire one
```bash
curl -s -X POST -H "X-API-Key: dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"scenarioId":"hidden_prompt_injection"}' \
  http://localhost:8080/api/v1/adversary/fire | jq
# → { "sessionId": "0193...", "scenarioId": "hidden_prompt_injection", "message": "..." }
```

Within ~1 second the simulator emits Plan → ToolCall → ToolResult → SentinelDecision → ThreatDetected events that flow through the full pipeline.

### 2b. Capability escalation scenario (confused deputy)
```bash
curl -s -X POST -H "X-API-Key: dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"scenarioId":"capability_escalation"}' \
  http://localhost:8080/api/v1/adversary/fire | jq
```
Then inspect threats for `CAPABILITY_ESCALATION_ATTEMPT` and audit kinds `capability_escalation_detected` on that `sessionId`.

### 2c. Same check via `/inspect` (curl)
```bash
SID=$(uuidgen | tr 'A-Z' 'a-z')
AID=$(uuidgen | tr 'A-Z' 'a-z')
curl -s -H "X-API-Key: dev-api-key-change-me" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"actionId\":\"$AID\",\"direction\":\"OUTBOUND\",\"tool\":\"email.send\",\"args\":{\"to\":\"attacker@evil.com\"},\"originActor\":\"planner\",\"currentActor\":\"executor\"}" \
  http://localhost:8080/api/v1/sentinel/inspect | jq
# expect: decision BLOCK, policyMatched capability-escalation-block
```

### 3. See what was detected
```bash
SID="<sessionId from step 2>"
curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/sessions/$SID/threats | jq
```

### 4. Watch the live event stream
Connect a WebSocket client to **`ws://localhost:8080/ws/sessions/{sessionId}`** (per-session) or **`ws://localhost:8080/ws/events`** (global firehose). The demo authenticates the browser with **`?token=<API_KEY>`** for simplicity; **v2** should issue a **short-lived ticket** over REST and avoid putting long-lived secrets in query strings (they appear in proxy logs and `Referer`).

Every event the system produces is pushed in real time as JSON.

Quick browser test (open DevTools console on any page):
```javascript
const ws = new WebSocket("ws://localhost:8080/ws/events");
ws.onmessage = e => console.log(JSON.parse(e.data));
```

### 5. Verify the audit chain
```bash
curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/audit/verify | jq
# → { "chain_intact": true }

curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/audit/export | jq '.[0:3]'
```

### 6. Fire the unauthorized-payment scenario and approve
```bash
SID=$(curl -s -X POST -H "X-API-Key: dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"scenarioId":"unauthorized_payment"}' \
  http://localhost:8080/api/v1/adversary/fire | jq -r .sessionId)

sleep 1

# List pending approvals
APPROVAL=$(curl -s -H "X-API-Key: dev-api-key-change-me" \
  http://localhost:8080/api/v1/approvals | jq -r '.[0].id')

# Decide
curl -s -X POST -H "X-API-Key: dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"decision":"ALLOW","approverId":"demo-approver"}' \
  http://localhost:8080/api/v1/approvals/$APPROVAL/decide | jq
```

---

## Architecture

Hexagonal / Ports & Adapters. The core security logic is pure Java with **zero Spring imports**, enforced by ArchUnit tests in CI.

```
api/             Spring MVC REST controllers + WebSocket handler + DTOs
config/          Spring @Configuration beans (no logic)
domain/
  model/         Records + sealed interfaces (AgentEvent, Decision, ...)
  port/in/       Use case interfaces (driving)
  port/out/      SPI interfaces (driven; SessionRepository, EventPublisher, ...)
  service/       Use case implementations (SessionService, ApprovalService)
security/        Sentinel pipeline (ScannerStage: L1/L3/L4/L5/**CAP**/DLP/L6/L7)
policy/          YAML-driven rule engine (PolicyEngine + RuleExpressionEvaluator)
adversary/       AgentSimulator + AttackScenario implementations — the demo engine
persistence/     JPA entities, repositories, domain-port adapters
messaging/       Redis pub/sub publisher (impl of EventPublisher)
audit/           SHA-256 hash-chained audit log
azure/           Real Azure clients + stubs (profile switch)
common/          Errors, UUIDv7 generator
```

### The Sentinel pipeline (`security.pipeline.SecurityPipeline`)
A chain of `ScannerStage` implementations, cheapest first:

| Stage | Cost | Detects |
|---|---|---|
| **L1** deterministic | <1ms | Regex, hidden-DOM, phishing keywords |
| **L3** Azure Prompt Shields | ~50ms (real) | Indirect injection (Azure AI Content Safety) |
| **L4** LLM judge | ~600ms (real) | Grey-zone ambiguous content (gpt-4o-mini) |
| **L5** Behavioral anomaly | <1ms | Tool-frequency spikes, first-use of high-risk tools |
| **DLP** Egress filter | <1ms | Outbound secrets + PII |

A `RiskAggregator` combines per-stage scores into a composite, which the `PolicyEngine` evaluates against YAML rules to produce one of: `ALLOW | REWRITE | REQUIRE_APPROVAL | BLOCK | QUARANTINE`.

### The AgentSimulator (`adversary.AgentSimulator`)
The bridge between scripted scenarios and the rest of the system. Each `AttackScenario` script calls `sim.plan(...)`, `sim.toolCall(...)`, `sim.toolResult(...)`. The simulator runs each call through the pipeline, persists threats, appends to audit, and pushes events to the WebSocket bus.

When the real agent layer ships (LangGraph/Python sidecar), it just calls the same `EventPublisher` — no other code changes.

---

## Configuration

All settings via env vars or `application.yml`:

| Env | Default | Purpose |
|---|---|---|
| `SENTINELMESH_API_KEY` | `dev-api-key-change-me` | Required on all `/api/v1/**` calls |
| `SENTINELMESH_JWT_SECRET` | dev value | (Reserved for approver JWTs) |
| `SENTINELMESH_AZURE_MODE` | `stub` | `stub` \| `real` |
| `AZURE_OPENAI_ENDPOINT` | empty | When `mode=real` |
| `AZURE_OPENAI_KEY` | empty | When `mode=real` |
| `AZURE_OPENAI_DEPLOYMENT` | `gpt-4o-mini` | Deployment name |
| `AZURE_CONTENT_SAFETY_ENDPOINT` | empty | When `mode=real` |
| `AZURE_CONTENT_SAFETY_KEY` | empty | When `mode=real` |

Switching from stub → real is a one-line `.env` change; no code edits.

---

## Tests

Three layers:

1. **Unit** (millisecond) — `L1DeterministicScannerTest` and friends.
2. **Architecture** (ArchUnit) — domain doesn't import Spring; scanners implement `ScannerStage`; `@RestController` lives only in `api.rest.*`.
3. **Integration** (Testcontainers) — `AdversaryFlowIT` boots the full app against a real Postgres + Redis, fires scenarios, asserts threats and audit chain.

Run via gradle (inside Docker is easiest):
```bash
docker run --rm -v "$PWD:/workspace" -w /workspace \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gradle:8.10-jdk21 gradle test
```

---

## Deploying to Azure

The same image runs anywhere. For Azure Container Apps:

```bash
# Build & push
az acr build --registry <your-acr> --image sentinelmesh:0.1 .

# Create the app
az containerapp create \
  --name sentinelmesh \
  --resource-group <rg> \
  --image <your-acr>.azurecr.io/sentinelmesh:0.1 \
  --target-port 8080 --ingress external \
  --env-vars \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_DATASOURCE_URL=jdbc:postgresql://<flex-server>.postgres.database.azure.com:5432/sentinelmesh \
    SPRING_DATASOURCE_USERNAME=<user> \
    SPRING_DATASOURCE_PASSWORD=secretref:db-password \
    SPRING_DATA_REDIS_HOST=<redis>.redis.cache.windows.net \
    SENTINELMESH_AZURE_MODE=real \
    AZURE_OPENAI_ENDPOINT=https://<your>.openai.azure.com \
    AZURE_OPENAI_KEY=secretref:aoai-key \
    AZURE_CONTENT_SAFETY_ENDPOINT=https://<your>.cognitiveservices.azure.com \
    AZURE_CONTENT_SAFETY_KEY=secretref:cs-key
```

---

## What's intentionally NOT in v1

- Real LangGraph agent layer (the `AgentSimulator` is the bridge for now)
- Playwright browser pool (lives in the future Python sidecar)
- L2 pgvector embedding similarity (TODO; corpus seeding planned)
- Policy editing UI / write API (read-only via `/api/v1/policies/current`)
- Multi-tenant auth (single API key)

When you add them, **nothing else changes** — that's the whole point of the hexagonal layout.
