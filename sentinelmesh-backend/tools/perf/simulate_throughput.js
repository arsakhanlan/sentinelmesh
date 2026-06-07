// SentinelMesh — k6 throughput benchmark for /api/v1/policies/simulate.
//
// This endpoint is bursty: each call replays the last N hours of audited
// inspections through a candidate bundle. Latency depends on the size of
// the audit table at the time of the call, so we report p50/p95/p99 against
// whatever the dev DB has on hand.

import http from "k6/http";
import { check } from "k6";

export const options = {
  scenarios: {
    policy_simulate: {
      executor: "constant-arrival-rate",
      rate: 10,            // 10 simulations / second
      timeUnit: "1s",
      duration: "30s",
      preAllocatedVUs: 8,
      maxVUs: 32,
    },
  },
  thresholds: {
    http_req_failed:   ["rate<0.01"],
    http_req_duration: ["p(99)<5000"],   // simulator can be heavy; allow 5s
  },
};

const API_KEY = __ENV.API_KEY || "dev-api-key-change-me";
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// A trivial candidate bundle. The simulator's cost is dominated by the
// audit-event scan, not the rule count, so this is fair.
const candidateBundle = `
name: bench
rules:
  - name: block-high
    priority: 1
    when: "risk >= 0.5"
    then: BLOCK
    reason: "bench"
  - name: default-allow
    priority: 100
    when: "risk < 0.5"
    then: ALLOW
    reason: ""
`;

export default function () {
  const body = JSON.stringify({
    bundle_yaml: candidateBundle,
    window_hours: 24,
  });
  const res = http.post(`${BASE_URL}/api/v1/policies/simulate`, body, {
    headers: { "Content-Type": "application/json", "X-API-Key": API_KEY },
  });
  check(res, {
    "status 200": (r) => r.status === 200,
    "events considered": (r) => r.json("eventsConsidered") >= 0,
  });
}
