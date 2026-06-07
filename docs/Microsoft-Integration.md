# Microsoft AI ecosystem integration

> **TL;DR.** SentinelMesh now plugs natively into Microsoft's AI stack:
> Microsoft Agent Framework agents, Azure AI Content Safety (both
> Prompt Shields *and* the categories endpoint), AI Red Teaming Agent
> (PyRIT-style scans with an Attack Success Rate metric), Foundry tracing
> via OpenTelemetry `gen_ai` semantic conventions, Foundry Hosted Agent
> packaging, and Foundry IQ knowledge-base exports.

This is the integration layer. Read it once if you want to know what
"Sentinel governs Microsoft Foundry agents" actually means in code.

---

## 1. Microsoft Agent Framework — the three-line integration

[Microsoft Agent Framework (MAF)][maf-docs] is the unified, stable
Python+.NET SDK that replaces Semantic Kernel + AutoGen. It's the
recommended path for [Foundry Hosted Agents][hosted-docs]. MAF exposes a
`function_middleware` extension point that fires on every tool call —
exactly the seam SentinelMesh wants.

```python
from agent_framework import Agent
from agent_framework.openai import OpenAIChatClient
from sentinelmesh_agents.microsoft.maf_middleware import attach_sentinel

sentinel = await attach_sentinel(goal="book a hotel in Bangalore")
agent = Agent(
    name="skynest_concierge",
    client=OpenAIChatClient(model="gpt-4o-mini"),
    instructions="You book hotels for travellers, safely.",
    tools=[book_hotel, send_email],
    middleware=[sentinel],          # ← the whole integration
)
```

That's it. Every tool call now flows through:

1. SentinelClient → backend `/api/v1/sentinel/inspect`
2. L1–L7 + CAP + DLP scanner pipeline
3. Policy engine (BLOCK / REQUIRE_APPROVAL / REWRITE / ALLOW)
4. Hash-chained audit ledger
5. Live SOC dashboard event stream

When the policy returns BLOCK, the middleware sets `context.terminate = True`
and writes a refusal string to `context.result`, so the LLM sees a
structured deny instead of a silent skip. REWRITE swaps in the redacted
arguments before the call runs. ALLOW passes through and re-inspects the
result on the way back.

The same middleware works against **Semantic Kernel** without changes —
SK's `FilterTypes.FUNCTION_INVOCATION` filter contract is the same shape.

Source: `src/sentinelmesh_agents/microsoft/maf_middleware.py`
Example: `examples/maf_governed_agent.py`

---

## 2. Azure AI Content Safety — both endpoints, both stub-and-real

Azure Content Safety has **two** distinct text endpoints, and SentinelMesh
now uses both:

| Endpoint | Purpose | SentinelMesh layer | Client |
|---|---|---|---|
| `text:shieldPrompt` | Jailbreak / indirect-injection detection | **L3** | [`AzureContentSafetyClient`][shield-src] |
| `text:analyze` | Hate / SelfHarm / Sexual / Violence categories with severity 0–7 | **L2** | [`AzureContentSafetyCategoriesClient`][cats-src] |

Both clients have a stub sibling so the demo runs without Azure quota.
Toggles:

```yaml
sentinelmesh:
  azure:
    mode: real|stub                                 # default: stub
    contentSafety:
      endpoint: https://<resource>.cognitiveservices.azure.com
      key: <ocp-apim-subscription-key>
      categories:
        mode: real|stub                             # default: follows azure.mode
        flagThreshold: 4                            # severity ≥ this counts as flagged
```

Severity → score mapping for L2: 4 → 0.55, 5 → 0.65, 6 → 0.80, 7 → 0.95.
Two flagged categories compound by +0.15 so e.g. Violence=4 + Hate=4 lands
in the BLOCK band even though neither would individually.

[shield-src]: ../sentinelmesh-backend/src/main/java/com/sentinelmesh/azure/AzureContentSafetyClient.java
[cats-src]: ../sentinelmesh-backend/src/main/java/com/sentinelmesh/azure/AzureContentSafetyCategoriesClient.java

---

## 3. AI Red Teaming Agent — the headline number

The [Microsoft AI Red Teaming Agent][redteam-docs] (`azure-ai-evaluation[redteam]`)
wraps PyRIT and produces an Attack Success Rate (ASR) metric. We vendored a
PyRIT-style harness in `sentinelmesh_agents.microsoft.red_team` that produces
the same metric **without** an Azure AI project endpoint, so the demo runs
offline. Drop in a real `RedTeam` instance for production.

Run the comparison demo:

```bash
python -m examples.redteam_compare --objectives-per-category 2 \
    --out artifacts/redteam-report.json
```

Output:

```
==============================================================================
  SentinelMesh red-team comparison (PyRIT-style)
==============================================================================
  Objectives:           7 × 5 strategies = 35 attempts per target

  Naked agent ASR:        80.0%   (28/35 attempts succeeded)
  SentinelMesh ASR:        0.0%   (0/35 attempts succeeded)
  Absolute drop:         +80.0 pp
  Relative reduction:    100.0%

  Strategy                   Naked ASR   Sentinel ASR
  ------------------------  ----------  -------------
  Base64                        100.0%           0.0%
  Baseline                        0.0%           0.0%
  Compose(Base64,ROT13)         100.0%           0.0%
  Flip                          100.0%           0.0%
  ROT13                         100.0%           0.0%
==============================================================================
```

The harness covers the standard PyRIT attack strategies — Baseline, Base64,
ROT13, Morse, Leetspeak, Flip, CharacterSwap, UnicodeConfusable, plus
arbitrary `Compose([...])` chains. Risk categories follow Microsoft's
canonical four (Violence, HateUnfairness, Sexual, SelfHarm).

The "Baseline" strategy frequently scores 0% on the naked target because
even weak content filters catch unobfuscated harmful prompts — the *whole
point* is that the obfuscated strategies bypass naked filters and trip
SentinelMesh's deeper layers.

Source: `src/sentinelmesh_agents/microsoft/red_team.py`
Example: `examples/redteam_compare.py`

---

## 4. OpenTelemetry / Foundry tracing — visible inside the Foundry portal

Microsoft Agent Framework ships with [first-class OTel
observability][maf-otel] using the `gen_ai` semantic conventions: every
`invoke_agent` span gets `execute_tool` children for tool calls. Foundry's
trace explorer renders these natively.

`InstrumentedSentinelClient` wraps `SentinelClient` and emits **a span per
inspect call** with the canonical attribute names plus SentinelMesh-specific
ones (`sentinelmesh.decision`, `sentinelmesh.composite_risk`,
`sentinelmesh.scanner.<L1|L2|...>`). Span status is set to ERROR on
BLOCK/QUARANTINE so the trace viewer flags it red.

```python
from sentinelmesh_agents.microsoft.tracing import (
    configure_tracing, InstrumentedSentinelClient,
)

configure_tracing(service_name="sentinelmesh-booking-agent")
client = InstrumentedSentinelClient()       # drop-in for SentinelClient
```

Configuration is automatic on Foundry Hosted Agents: the runtime injects
`OTEL_EXPORTER_OTLP_ENDPOINT` and `configure_tracing()` picks it up.

Source: `src/sentinelmesh_agents/microsoft/tracing.py`

---

## 5. Foundry Hosted Agent packaging

`src/sentinelmesh_agents/microsoft/foundry_host.py` is a runnable
entrypoint that builds a Sentinel-governed MAF agent and serves it through
Foundry's Responses protocol via `agent_framework_foundry_hosting`. The
companion `Dockerfile.foundry` builds a slim image with the `[microsoft]`
and `[otel]` extras + the hosting wrapper.

```bash
docker build -t sentinelmesh-booking-agent -f Dockerfile.foundry .
az foundry agents deploy \
    --image <registry>/sentinelmesh-booking-agent:latest \
    --project-endpoint <foundry-project-endpoint>
```

When `agent_framework_foundry_hosting` isn't installed, the entrypoint
falls back to a local stdin loop so the same script works in dev.

---

## 6. Foundry IQ knowledge-base export

[Foundry IQ][iq-docs] is Microsoft's agent-side knowledge plane: any
Foundry agent can query an IQ-attached knowledge base via the same
Responses API call that handles `file_search`. SentinelMesh exposes its
**policy bundle** and **threat board** as IQ-ingestible markdown / JSONL
documents so a Microsoft-native agent can ask "what does the policy say
about external vendor charges?" with no SentinelMesh-specific client code.

| Endpoint | Format | Purpose |
|---|---|---|
| `GET /api/v1/foundry-iq/policies` | markdown | Active policy bundle, one section per rule |
| `GET /api/v1/foundry-iq/policies.jsonl` | JSONL | Same content, one row per rule |
| `GET /api/v1/foundry-iq/threats` | markdown | Threat-board rollup with category counts |
| `GET /api/v1/foundry-iq/threats.jsonl` | JSONL | Same content, one row per category |

Source: `sentinelmesh-backend/src/main/java/com/sentinelmesh/api/rest/FoundryIqController.java`

---

## Putting it all together

A Microsoft-native demo loop looks like this:

1. Boot the SentinelMesh backend (Spring Boot, Java 21).
2. Run `examples/redteam_compare.py` → produce the **80% → 0% ASR** slide.
3. Run the SentinelMesh-protected MAF agent (`examples/maf_governed_agent.py`)
   with `OTEL_EXPORTER_OTLP_ENDPOINT` pointed at your Foundry collector.
4. Open Foundry Trace explorer → see Sentinel decisions as `execute_tool`
   child spans, in red on BLOCK.
5. Open the SOC dashboard → same decisions, hash-chained audit, threat
   board updating live.
6. `curl /api/v1/foundry-iq/policies > policies.md` → ingest into Foundry
   IQ → ask any Foundry agent "what does the SentinelMesh policy say?".

Every step is independently usable. The combination is the demo.

[maf-docs]: https://learn.microsoft.com/agent-framework/
[hosted-docs]: https://learn.microsoft.com/agent-framework/hosting/foundry-hosted-agent
[maf-otel]: https://learn.microsoft.com/agent-framework/agents/observability
[redteam-docs]: https://learn.microsoft.com/azure/foundry/concepts/ai-red-teaming-agent
[iq-docs]: https://learn.microsoft.com/azure/foundry/foundry-iq/overview
