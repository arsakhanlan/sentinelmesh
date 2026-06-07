# Multi-agent security roadmap

SentinelMesh today is a **single-session governance mesh**: every outbound tool call and inbound observation is inspected, policy is applied, decisions are audit-chained, and the SOC renders one linear agent story (planner → executor in LangGraph, or the Java `AgentSimulator` for adversary demos).

The seven ideas below are **enterprise swarm** concerns. This document states what is **already real**, what is **partially analogous**, and what belongs on the **v2+ backlog**.

| # | Capability | Status in this repo | What exists today (closest analogue) | v2 direction |
|---|------------|----------------------|--------------------------------------|--------------|
| 1 | **Agent identity spoofing** | **Partial — ingest path** | External agents publish timeline events via `POST /api/v1/events`. An allow-list (`sentinelmesh.event-ingest.actor-allowlist`, or `*` to disable) rejects unknown `actor` values with **HTTP 403**, emits **`AGENT_IDENTITY_VIOLATION`** on the firehose, and appends `ingest_rejected` to audit. Scenario: **`agent_identity_spoof`**. | Per-message **HMAC** or **mTLS** between runtimes; bind actor → workload identity (SPIFFE, OIDC). |
| 2 | **Capability delegation / confused deputy** | **Implemented** | Outbound inspect carries optional **`originActor` / `currentActor`** (Python LangGraph: planner vs executor). **`CapabilityEscalationScanner` (CAP)** compares the tool against the **origin** actor's capability set from `AgentCapabilityRegistry` (defaults: planner = browse+HTTP; executor = +email+payments). On mismatch → **`CAPABILITY_ESCALATION_ATTEMPT`**, policy **`capability-escalation-block`**, extra audit row **`capability_escalation_detected`**, metrics rollup. Scenario: **`capability_escalation`**. | Signed delegation tokens; OCap-style attenuation; per-tenant registry from IdP. |
| 3 | **Goal drift detection** | **Not implemented** | Session stores the user **goal** string, but the pipeline does **not** score each action’s semantic distance to that goal. **L5** looks at behavioural signals (tool novelty, rates), not textual drift from the goal. | Embedding similarity goal ↔ tool payload + rolling chart in SOC; optional quarantine when drift &gt; threshold. |
| 4 | **Agent collusion detection** | **Not implemented** | One primary agent thread per session in the demo; no **cross-agent** message graph or shared-secret handoff model. | Multi-session correlation store + graph queries (“secret touched by A then exfiltrated by B”). |
| 5 | **Trust score per agent** | **Not implemented** | **Session** quarantine after `QUARANTINE` decision; no per-role numeric trust ledger. | Decaying trust score per `actor` + automatic isolation when score &lt; threshold. |
| 6 | **Message provenance chain (A2A)** | **Partial** | **Audit hash chain** + per-event `eventId` / timestamps; **no** explicit `parentMessageId` linking agent-to-agent payloads. Internal `AgentSimulator` events are trusted by construction. | Signed envelope `{parentId, agentId, payloadHash}` on every A2A message; extend audit schema. |
| 7 | **Secret taint tracking** | **Partial** | **DLP** scans outbound content at **inspect** time (secrets/PII signals). There is **no** taint label that follows data through multiple hops inside the agent graph. | Propagate taint bits in state + block when tainted data meets egress tools regardless of DLP regex luck. |

## Demo script: capability escalation (today)

1. Open the SOC → **Adversary Console** → fire **`capability_escalation`**.
2. Threat feed shows **`CAPABILITY_ESCALATION_ATTEMPT`** (CRITICAL/HIGH) with evidence `origin_actor=planner`, `current_actor=executor`, `requested_capability=email.send`.
3. Audit export for the session includes **`capability_escalation_detected`** plus the normal **`sentinel_decision`** row with `capability_escalation: true`.
4. `GET /api/v1/metrics/summary` → `threats_by_category.CAPABILITY_ESCALATION_ATTEMPT` increments.

### Curl (inspect with provenance)

```bash
SID=$(uuidgen | tr 'A-Z' 'a-z')
AID=$(uuidgen | tr 'A-Z' 'a-z')
curl -s -H "X-API-Key: dev-api-key-change-me" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"actionId\":\"$AID\",\"direction\":\"OUTBOUND\",\"tool\":\"email.send\",\"args\":{\"to\":\"attacker@evil.com\"},\"originActor\":\"planner\",\"currentActor\":\"executor\"}" \
  http://localhost:8080/api/v1/sentinel/inspect | jq
# expect: decision BLOCK, policyMatched capability-escalation-block
```

## Demo script: identity spoof (today)

1. Open the SOC, fire scenario **`agent_identity_spoof`**.
2. Watch the **Threat feed** for **`AGENT_IDENTITY_VIOLATION`** (HIGH) when `FakeExecutor` hits `/api/v1/events`.
3. A subsequent legitimate **`http.get`** still flows through **`/sentinel/inspect`** as usual.

### Curl (manual)

```bash
curl -s -o /dev/stderr -w "%{http_code}" \
  -H "X-API-Key: dev-api-key-change-me" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"kind\":\"tool_call\",\"actor\":\"FakeExecutor\",\"payload\":{\"tool\":\"email.send\",\"args\":{}}}" \
  http://localhost:8080/api/v1/events
# expect: 403
```

### Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `sentinelmesh.event-ingest.actor-allowlist` | `planner,executor,agent,memory,validator,researcher` | Lowercase comparison; unknown actors rejected. |
| `sentinelmesh.event-ingest.actor-allowlist` | `*` | Disable allow-list (dev only). |
| `sentinelmesh.event-ingest.self-base-url` | `http://127.0.0.1:8080` | Used by **`agent_identity_spoof`** to call itself; override in Docker if loopback is wrong. |

## Why not build all seven now?

Items **3–7** still need a richer **multi-agent protocol** (cross-session correlation, signed A2A envelopes, taint propagation) than the current “one session, one LangGraph” topology. **#1 (ingest allow-list)** and **#2 (CAP confused deputy on `/inspect`)** are narrow, testable slices of the roadmap. The **ingest allow-list** matches an untrusted publisher surface; **CAP** matches the common planner→executor split in LangGraph with minimal schema churn.

### Phased plan for **#2–#5**

See **[`Multi-Agent-Plan-2-5.md`](./Multi-Agent-Plan-2-5.md)** for phases (P0 foundation → delegation → drift → collusion → trust), dependencies, acceptance tests, and cut lines between “demo slice” and “enterprise depth”.
