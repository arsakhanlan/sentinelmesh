// Runtime config. NEXT_PUBLIC_* values are baked at build time; the browser
// (running on the host) talks directly to the host-mapped backend/agent ports.
export const CONFIG = {
  backendUrl: process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080",
  wsUrl: process.env.NEXT_PUBLIC_WS_URL || "ws://localhost:8080",
  agentUrl: process.env.NEXT_PUBLIC_AGENT_URL || "http://localhost:8090",
  apiKey: process.env.NEXT_PUBLIC_API_KEY || "dev-api-key-change-me",
};

export const DECISION_COLOR: Record<string, string> = {
  ALLOW: "#10b981",
  REWRITE: "#22d3ee",
  REQUIRE_APPROVAL: "#f59e0b",
  BLOCK: "#ef4444",
  QUARANTINE: "#b91c1c",
};

export const SEVERITY_COLOR: Record<string, string> = {
  INFO: "#64748b",
  LOW: "#22d3ee",
  MEDIUM: "#f59e0b",
  HIGH: "#fb923c",
  CRITICAL: "#ef4444",
};
