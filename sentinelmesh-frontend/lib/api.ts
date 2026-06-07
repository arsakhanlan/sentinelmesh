import { CONFIG } from "./config";
import type {
  Approval, MetricsSummary, ScenarioInfo,
  SessionTimeline, SessionVerifyResult, TenantSummary,
} from "./types";

function headers(): HeadersInit {
  return {
    "Content-Type": "application/json",
    "X-API-Key": CONFIG.apiKey,
  };
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText} ${text}`.trim());
  }
  return res.json() as Promise<T>;
}

export const api = {
  async scenarios(): Promise<ScenarioInfo[]> {
    return json(await fetch(`${CONFIG.backendUrl}/api/v1/adversary/scenarios`, { headers: headers() }));
  },

  async fire(scenarioId: string, sessionId?: string): Promise<{ sessionId: string }> {
    return json(
      await fetch(`${CONFIG.backendUrl}/api/v1/adversary/fire`, {
        method: "POST",
        headers: headers(),
        body: JSON.stringify({ scenarioId, sessionId: sessionId ?? null }),
      })
    );
  },

  async approvals(sessionId?: string): Promise<Approval[]> {
    const qs = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
    return json(await fetch(`${CONFIG.backendUrl}/api/v1/approvals${qs}`, { headers: headers() }));
  },

  async decide(id: string, decision: "ALLOW" | "BLOCK", approverId = "operator"): Promise<Approval> {
    return json(
      await fetch(`${CONFIG.backendUrl}/api/v1/approvals/${id}/decide`, {
        method: "POST",
        headers: headers(),
        body: JSON.stringify({ decision, approverId }),
      })
    );
  },

  async metrics(): Promise<MetricsSummary> {
    return json(await fetch(`${CONFIG.backendUrl}/api/v1/metrics/summary`, { headers: headers() }));
  },

  async auditVerify(): Promise<{ chain_intact: boolean }> {
    return json(await fetch(`${CONFIG.backendUrl}/api/v1/audit/verify`, { headers: headers() }));
  },

  async sessionTimeline(sessionId: string): Promise<SessionTimeline> {
    return json(
      await fetch(`${CONFIG.backendUrl}/api/v1/sessions/${sessionId}/timeline`, {
        headers: headers(),
      })
    );
  },

  async sessionVerify(sessionId: string): Promise<SessionVerifyResult> {
    return json(
      await fetch(`${CONFIG.backendUrl}/api/v1/sessions/${sessionId}/verify`, {
        headers: headers(),
      })
    );
  },

  async tenantsSummary(): Promise<TenantSummary[]> {
    return json(await fetch(`${CONFIG.backendUrl}/api/v1/tenants/summary`, { headers: headers() }));
  },

  // Fires a real LangGraph agent (stub LLM by default) against the agent service.
  async runAgent(goal: string): Promise<{ session_id: string; status: string }> {
    return json(
      await fetch(`${CONFIG.agentUrl}/goals`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ goal }),
      })
    );
  },

  // --- Policy editor / simulator ------------------------------------ //

  async policyCurrentSource(): Promise<string> {
    const r = await fetch(`${CONFIG.backendUrl}/api/v1/policies/current/source`, {
      headers: headers(),
    });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.text();
  },

  async policySimulate(bundleYaml: string, windowHours = 24): Promise<PolicySimulationResult> {
    return json(
      await fetch(`${CONFIG.backendUrl}/api/v1/policies/simulate`, {
        method: "POST",
        headers: headers(),
        body: JSON.stringify({ bundle_yaml: bundleYaml, window_hours: windowHours }),
      })
    );
  },
};

// Shape returned by /api/v1/policies/simulate.
export type PolicySimulationResult = {
  eventsConsidered: number;
  windowHours: number;
  ruleCount: number;
  changeCounts: Record<string, number>;
  samples: Record<string, PolicySimulationDiff[]>;
  warnings: string[];
};

export type PolicySimulationDiff = {
  sequence: number;
  tool: string;
  risk: number;
  blast: number;
  oldDecision: string;
  oldRule: string;
  newDecision: string;
  newRule: string;
  newReason: string;
};
