// Mirrors the backend AgentEvent JSON (kind discriminator + camelCase fields).

export type EventKind =
  | "plan"
  | "tool_call"
  | "tool_result"
  | "sentinel_decision"
  | "threat"
  | "approval_requested"
  | "approval_decided"
  | "state_transition";

export interface RiskScore {
  scores: Record<string, number>;
  composite: number;
}

export interface AgentEvent {
  kind: EventKind;
  eventId: string;
  sessionId: string;
  timestamp: string;
  actor: string;

  // plan
  goal?: string;
  plan?: Record<string, unknown>;

  // tool_call / tool_result
  tool?: string;
  args?: Record<string, unknown>;
  result?: Record<string, unknown>;
  contentSample?: string;

  // sentinel_decision
  decision?: string;
  risk?: RiskScore;
  reason?: string;
  interceptedAction?: Record<string, unknown>;

  // threat
  category?: string;
  severity?: string;
  score?: number;
  evidence?: Record<string, unknown>;

  // approval_requested / approval_decided
  approvalId?: string;
  intent?: string;
  action?: Record<string, unknown>;
  blastRadius?: number;
  approverId?: string;

  // state_transition
  from?: string;
  to?: string;
}

export interface Approval {
  id: string;
  sessionId: string;
  actionId: string;
  intent: string;
  requestedPayload: Record<string, unknown>;
  modifiedPayload?: Record<string, unknown>;
  approverId?: string;
  decision?: string;
  status: string;
  blastRadius: number;
  requestedAt: string;
  decidedAt?: string;
  ttlAt?: string;
}

export interface ScenarioInfo {
  id: string;
  displayName: string;
  description: string;
}

export interface MetricsSummary {
  threats_total: number;
  threats_by_category: Record<string, number>;
  policy_rules_loaded: number;
  detect_latency_ms: {
    samples: number;
    mean?: number;
    max?: number;
    p50?: number;
    p95?: number;
    p99?: number;
  };
}

// ---- Forensics drawer payloads (mirrors SessionForensicsController) -----

export interface BudgetView {
  tools: Record<string, { used: number; cap: number | null }>;
  spendUsedInr: number;
  spendCapInr: number;
}

export interface AuditRow {
  sequence: number;
  eventId: string;
  ts: string;
  kind: string;
  actor: string;
  payload: Record<string, unknown>;
  prevHash: string;
  hash: string;
}

export interface ThreatRow {
  id: string;
  actionId: string;
  ts: string;
  category: string;
  severity: string;
  score: number;
  evidence: Record<string, unknown>;
}

export interface ApprovalRow {
  id: string;
  actionId: string;
  intent: string;
  status: string;
  decision: string | null;
  approverId: string | null;
  blastRadius: number;
  requestedAt: string;
  decidedAt: string | null;
  ttlAt: string | null;
  requestedPayload: Record<string, unknown>;
}

export interface SessionView {
  id: string;
  userId: string;
  goal: string;
  status: string;
  policyBundleId: string;
  capabilityToken: Record<string, unknown>;
  createdAt: string;
  endedAt: string | null;
  tenantId?: string | null;
}

export interface SessionTimeline {
  session: SessionView;
  audit: AuditRow[];
  threats: ThreatRow[];
  approvals: ApprovalRow[];
  budget: BudgetView;
}

export interface SessionVerifyResult {
  session_id: string;
  chain_intact: boolean;
  status: string;
}

export interface TenantToolUsage {
  used24h: number;
  cap24h: number;
  pct: number;
}

export interface RedTeamSummary {
  total_attacks: number;
  blocked: number;
  allowed: number;
  asr: number;
  sessions_24h: number;
  threats_by_category: Record<string, number>;
  policy_rules_loaded: number;
}

export interface TenantSummary {
  tenantId: string;
  name: string;
  sessionsToday: number;
  threatsTotal: number;
  tools: Record<string, TenantToolUsage>;
  spendUsed24hInr: number;
  spendCap24hInr: number;
  spendPct: number;
}
