# SentinelMesh — Performance Benchmarks

End-to-end latency through the full security pipeline, captured with
[k6](https://k6.io/) against the dev compose stack (Postgres + Redis +
backend in containers, k6 driving from the host on `localhost:8080`).

These numbers include **everything**: the 7-layer scanner pipeline, policy
engine evaluation, and the cryptographic audit-chain append (Day 1's
Postgres advisory-lock-protected write). Nothing is mocked.

## How to reproduce

```bash
# Boot the stack (from sentinelmesh-agents/)
docker-compose up -d backend postgres redis

# Run benchmarks (from sentinelmesh-backend/tools/perf/)
docker run --rm --network host -v "$(pwd)":/scripts -w /scripts \
  grafana/k6 run --summary-trend-stats="p(50),p(95),p(99),max" \
  inspect_throughput.js
docker run --rm --network host -v "$(pwd)":/scripts -w /scripts \
  grafana/k6 run --summary-trend-stats="p(50),p(95),p(99),max" \
  inspect_stress.js
docker run --rm --network host -v "$(pwd)":/scripts -w /scripts \
  grafana/k6 run --summary-trend-stats="p(50),p(95),p(99),max" \
  simulate_throughput.js
```

## Scenario 1 — Inspect throughput (`/api/v1/sentinel/inspect`)

Ramping arrival rate, 0 → 100 RPS over 30s, hold at 100 RPS for 60s,
ramp-down for 10s. **Half clean, half attack-bearing payloads.** Audit
append runs on every successful inspection, so the cost of the
multi-writer chain is fully baked into these numbers.

| Metric           | Value      |
|------------------|-----------:|
| Requests         | 8,014      |
| Duration         | 100s       |
| Avg RPS achieved | 80         |
| Errors           | 0          |
| **p50**          | **6.53 ms** |
| p90              | 8.30 ms    |
| **p95**          | **9.11 ms** |
| **p99**          | **13.10 ms** |
| Max              | 42.64 ms   |

## Scenario 2 — Inspect stress (`/api/v1/sentinel/inspect`)

Constant arrival rate, **500 RPS sustained for 60s**. This finds the clean
ceiling on the dev box; at 600 RPS the server lags slightly (581 RPS
delivered, p99 climbs into the hundreds of ms — VU-pool starvation, not
backend regression).

| Metric           | Value         |
|------------------|--------------:|
| Requests         | 30,001        |
| Duration         | 60s           |
| Sustained RPS    | 500           |
| Errors           | 0             |
| **p50**          | **7.16 ms**   |
| **p95**          | **15.38 ms**  |
| **p99**          | **55.42 ms**  |
| Max              | 140.05 ms     |

The p99 jump at 500 RPS is the advisory-lock contention point — every
audit append serializes through `pg_advisory_xact_lock`, and at this
arrival rate Postgres's lock-acquire round trip starts to show. This is
the honest cost of "any N backend instances can write to the same chain
without breaking it." We trade ~30 ms p99 for horizontal scale.

## Scenario 3 — Policy simulation (`/api/v1/policies/simulate`)

Constant arrival rate, **10 RPS sustained for 30s**, replaying the last
24h of audited inspections through a candidate bundle. The simulator's
cost is dominated by the audit-event scan, not rule count — these numbers
will grow ~linearly with chain size.

| Metric           | Value         |
|------------------|--------------:|
| Requests         | 300           |
| Duration         | 30s           |
| Sustained RPS    | 10            |
| Errors           | 0             |
| **p50**          | **69.5 ms**   |
| p90              | 104.79 ms     |
| **p95**          | **116.7 ms**  |
| **p99**          | **138.61 ms** |
| Max              | 168.77 ms     |

## Test environment

- Host: 96-core, 378 GiB RAM Linux (rootless Docker 27.5.1 — *not* a
  representative production VM; numbers will vary).
- Backend: single instance, JVM 21, default Spring Boot tuning.
- Postgres: `postgres:16-alpine`, default Hikari pool (max 10), single instance.
- Redis: `redis:7-alpine`.
- Audit table: ~6.5k rows (post-Day-1 concurrency test) at the time
  benchmarks were captured.

## What these numbers buy us

- **Inspect**: under 15 ms p99 at 100 RPS (the realistic per-instance
  workload for a security gateway), under 60 ms p99 at 500 RPS sustained.
  Comfortably below the 100 ms "human-visible" bar.
- **Multi-writer audit chain**: zero errors at 500 RPS sustained, with
  every request taking the advisory-lock path. The chain stays
  byte-for-byte verifiable.
- **Policy simulation**: 70 ms p50 to replay 24 h of decisions through a
  candidate bundle is fast enough to feel synchronous in the Policy Lab UI.
