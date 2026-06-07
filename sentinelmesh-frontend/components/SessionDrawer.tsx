"use client";

import { AnimatePresence, motion } from "framer-motion";
import {
  AlertOctagon,
  CheckCircle2,
  FileBadge,
  Gauge,
  Hash,
  ShieldCheck,
  ShieldX,
  Wallet,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { DECISION_COLOR, SEVERITY_COLOR } from "@/lib/config";
import type { SessionTimeline, SessionVerifyResult } from "@/lib/types";
import { DecisionBadge } from "./DecisionBadge";
import { Chip } from "./ui";

/**
 * The forensics drawer: a slide-in panel that reconstructs a complete session
 * from the backend's hash-chained audit log, layered with detected threats,
 * approval decisions, and a live capability-budget snapshot.
 *
 * <p>The drawer is the SOC operator's "open the black box" view — what the
 * agent did, why the Sentinel decided what it decided, with one-click
 * cryptographic verification that the record hasn't been tampered with.
 */
export function SessionDrawer({
  sessionId,
  onClose,
}: {
  sessionId: string | null;
  onClose: () => void;
}) {
  const open = !!sessionId;
  const [data, setData] = useState<SessionTimeline | null>(null);
  const [loading, setLoading] = useState(false);
  const [verify, setVerify] = useState<SessionVerifyResult | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async (id: string) => {
    setLoading(true);
    setError(null);
    try {
      const t = await api.sessionTimeline(id);
      setData(t);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load timeline");
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!sessionId) {
      setData(null);
      setVerify(null);
      return;
    }
    reload(sessionId);
    const t = setInterval(() => reload(sessionId), 4000);
    return () => clearInterval(t);
  }, [sessionId, reload]);

  // ESC closes the drawer — standard a11y for a modal-ish surface.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  const runVerify = useCallback(async () => {
    if (!sessionId) return;
    setVerifying(true);
    try {
      const r = await api.sessionVerify(sessionId);
      setVerify(r);
    } catch (e) {
      setVerify({
        session_id: sessionId,
        chain_intact: false,
        status: "ERROR",
      });
    } finally {
      setVerifying(false);
    }
  }, [sessionId]);

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="fixed inset-0 z-40 bg-black/55 backdrop-blur-[2px]"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", stiffness: 260, damping: 32 }}
            className="fixed right-0 top-0 z-50 h-screen w-full overflow-y-auto border-l border-white/10 bg-[#0b1322]/95 backdrop-blur-xl sm:w-[640px] lg:w-[760px]"
          >
            <DrawerHeader
              sessionId={sessionId}
              data={data}
              onClose={onClose}
              onVerify={runVerify}
              verifying={verifying}
              verify={verify}
            />
            <div className="flex flex-col gap-4 p-4 pb-12">
              {error && (
                <div className="rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-xs text-red-300">
                  {error}
                </div>
              )}
              {loading && !data && (
                <div className="mono text-xs tracking-widest text-slate-500 animate-flicker">
                  LOADING SESSION RECONSTRUCTION…
                </div>
              )}
              {data && (
                <>
                  <CapabilityBudgetPanel data={data} />
                  <PlanSection data={data} />
                  <TimelineSection data={data} />
                  <ThreatsSection data={data} />
                  <ApprovalsSection data={data} />
                </>
              )}
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
}

// ----------- header w/ session badge + chain verify button -----------

function DrawerHeader({
  sessionId,
  data,
  onClose,
  onVerify,
  verifying,
  verify,
}: {
  sessionId: string | null;
  data: SessionTimeline | null;
  onClose: () => void;
  onVerify: () => void;
  verifying: boolean;
  verify: SessionVerifyResult | null;
}) {
  const status = data?.session.status ?? "—";
  const statusColor = STATUS_COLORS[status] ?? "#94a3b8";
  return (
    <header className="sticky top-0 z-10 flex flex-col gap-3 border-b border-white/10 bg-[#0b1322]/95 px-4 py-3 backdrop-blur-xl">
      <div className="flex items-start gap-3">
        <FileBadge size={18} className="mt-0.5 text-agent" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-300">
              Forensics
            </h2>
            <span
              className="mono rounded-md px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider"
              style={{ color: statusColor, background: `${statusColor}1a`, border: `1px solid ${statusColor}40` }}
            >
              {status}
            </span>
          </div>
          <div className="mono mt-0.5 truncate text-[10px] text-slate-500">{sessionId}</div>
          {data?.session.goal && (
            <div className="mt-1 line-clamp-2 text-[12px] text-slate-300">
              <span className="text-slate-500">goal:</span> {data.session.goal}
            </div>
          )}
        </div>
        <button
          onClick={onClose}
          aria-label="Close forensics"
          className="rounded-md p-1 text-slate-400 hover:bg-white/5 hover:text-white"
        >
          <X size={16} />
        </button>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button
          onClick={onVerify}
          disabled={verifying}
          className="mono flex items-center gap-1.5 rounded-md border border-white/10 bg-white/5 px-2.5 py-1 text-[10px] uppercase tracking-wider text-slate-200 hover:bg-white/10 disabled:opacity-60"
        >
          <Hash size={11} />
          {verifying ? "Verifying…" : "Verify chain"}
        </button>
        {verify && (
          <span
            className={`mono flex items-center gap-1.5 rounded-md px-2 py-1 text-[10px] uppercase tracking-wider ${
              verify.chain_intact
                ? "border border-emerald-400/40 bg-emerald-400/10 text-emerald-300"
                : "border border-red-400/40 bg-red-400/10 text-red-300"
            }`}
          >
            {verify.chain_intact ? (
              <>
                <ShieldCheck size={11} /> chain intact
              </>
            ) : (
              <>
                <ShieldX size={11} /> chain broken
              </>
            )}
          </span>
        )}
        {data && (
          <span className="mono ml-auto text-[10px] text-slate-500">
            {data.audit.length} audit · {data.threats.length} threats · {data.approvals.length} approvals
          </span>
        )}
      </div>
    </header>
  );
}

const STATUS_COLORS: Record<string, string> = {
  CREATED: "#64748b",
  PLANNING: "#a78bfa",
  EXECUTING: "#22d3ee",
  AWAITING_APPROVAL: "#f59e0b",
  COMPLETED: "#10b981",
  QUARANTINED: "#b91c1c",
  FAILED: "#ef4444",
};

// ----------- capability budget panel -----------

function CapabilityBudgetPanel({ data }: { data: SessionTimeline }) {
  const { budget } = data;
  const tools = Object.entries(budget.tools);
  const spendPct = budget.spendCapInr > 0
    ? Math.min(100, (budget.spendUsedInr / budget.spendCapInr) * 100)
    : 0;
  return (
    <Section icon={<Wallet size={13} />} title="Capability Budget">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {tools.map(([tool, u]) => {
          const cap = u.cap;
          const used = u.used;
          const pct = cap == null ? 0 : Math.min(100, (used / Math.max(1, cap)) * 100);
          const over = cap != null && used > cap;
          const near = pct >= 80 && !over;
          const color = over ? "#ef4444" : near ? "#f59e0b" : "#10b981";
          return (
            <div key={tool} className="rounded-lg border border-white/5 bg-white/[0.03] p-2.5">
              <div className="flex items-center justify-between gap-2">
                <span className="mono text-[11px] text-slate-200">{tool}</span>
                <span className="mono text-[10px]" style={{ color }}>
                  {used}/{cap ?? "∞"}
                </span>
              </div>
              <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/5">
                <div
                  className="h-full transition-all"
                  style={{ width: `${pct}%`, background: color, boxShadow: `0 0 6px ${color}80` }}
                />
              </div>
            </div>
          );
        })}
      </div>
      <div className="mt-3 flex items-center gap-3 rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2">
        <Gauge size={14} className="text-slate-400" />
        <div className="flex-1">
          <div className="flex items-center justify-between">
            <span className="text-[11px] text-slate-300">Spend cap</span>
            <span className="mono text-[11px] text-slate-200">
              ₹{budget.spendUsedInr.toLocaleString()} / ₹{budget.spendCapInr.toLocaleString()}
            </span>
          </div>
          <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/5">
            <div
              className="h-full transition-all"
              style={{
                width: `${spendPct}%`,
                background: spendPct >= 100 ? "#ef4444" : spendPct >= 80 ? "#f59e0b" : "#10b981",
              }}
            />
          </div>
        </div>
      </div>
    </Section>
  );
}

// ----------- plan section (synthesized from audit `plan` row) -----------

function PlanSection({ data }: { data: SessionTimeline }) {
  const planRow = data.audit.find((r) => r.kind === "plan");
  const steps = (planRow?.payload?.steps as unknown) as
    | Array<{ intent?: string; tool?: string; args?: Record<string, unknown> }>
    | undefined;
  if (!steps || steps.length === 0) return null;
  return (
    <Section icon={<FileBadge size={13} />} title={`Plan (${steps.length} steps)`}>
      <ol className="flex flex-col gap-1.5">
        {steps.map((s, i) => (
          <li
            key={i}
            className="flex items-start gap-2 rounded border border-white/5 bg-white/[0.03] px-2.5 py-1.5"
          >
            <span className="mono mt-0.5 w-5 text-[10px] text-slate-500">{i + 1}.</span>
            <div className="min-w-0 flex-1">
              {s.intent && (
                <div className="text-[12px] text-slate-300">{s.intent}</div>
              )}
              <div className="mono text-[10px] text-slate-500">
                <span className="text-agent">{s.tool}</span>
                {s.args && Object.keys(s.args).length > 0 && (
                  <> · {truncate(JSON.stringify(s.args), 100)}</>
                )}
              </div>
            </div>
          </li>
        ))}
      </ol>
    </Section>
  );
}

// ----------- timeline from audit rows -----------

function TimelineSection({ data }: { data: SessionTimeline }) {
  if (data.audit.length === 0) {
    return (
      <Section title="Timeline">
        <div className="rounded border border-dashed border-white/10 px-3 py-4 text-center text-[11px] text-slate-500">
          No audit rows recorded yet.
        </div>
      </Section>
    );
  }
  return (
    <Section title={`Timeline (${data.audit.length})`}>
      <div className="flex flex-col gap-1.5">
        {data.audit.map((r) => (
          <AuditRowView key={r.eventId} row={r} />
        ))}
      </div>
    </Section>
  );
}

function AuditRowView({ row }: { row: { kind: string; ts: string; actor: string; payload: Record<string, unknown>; sequence: number; hash: string } }) {
  const isDecision = row.kind === "sentinel_decision";
  const decision = isDecision ? String(row.payload.decision ?? "") : null;
  const color = decision ? DECISION_COLOR[decision] ?? "#22d3ee" : kindColor(row.kind);
  const reason = isDecision ? String(row.payload.reason ?? "") : "";
  const risk = isDecision
    ? typeof row.payload.risk === "number"
      ? (row.payload.risk as number)
      : typeof row.payload.risk === "object" && row.payload.risk
        ? ((row.payload.risk as { composite?: number }).composite)
        : undefined
    : undefined;
  // L7 attack-memory hit: payload carries `known_attack` + `attack_similarity`.
  // Surface it as a "matched known attack" pill so the operator immediately
  // sees the learning layer working without expanding the JSON blob.
  const knownAttack = isDecision && typeof row.payload.known_attack === "string"
    ? (row.payload.known_attack as string)
    : null;
  const attackSim = isDecision && typeof row.payload.attack_similarity === "number"
    ? (row.payload.attack_similarity as number)
    : null;
  return (
    <div
      className="grid grid-cols-[auto_auto_1fr] items-start gap-2 rounded-lg border px-2.5 py-1.5 text-[11px]"
      style={{ background: `${color}0d`, borderColor: `${color}26` }}
    >
      <span className="mono pt-0.5 text-[9px] text-slate-600">#{row.sequence}</span>
      <span className="mono pt-0.5 text-[9px] tracking-wider" style={{ color }}>
        {row.kind.toUpperCase()}
      </span>
      <div className="min-w-0">
        {isDecision ? (
          <div className="flex flex-wrap items-center gap-2">
            <DecisionBadge decision={decision ?? undefined} />
            {risk != null && (
              <span className="mono text-[10px] text-slate-500">risk {Math.round(risk * 100)}</span>
            )}
            <span className="text-slate-300">{reason}</span>
            {knownAttack && (
              <span
                className="mono ml-auto rounded-md border border-fuchsia-500/40 bg-fuchsia-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wider text-fuchsia-200"
                title={`Matched the '${knownAttack}' attack fingerprint at similarity ${attackSim ?? "?"}`}
              >
                L7 · {knownAttack}{attackSim != null ? ` · ${(attackSim * 100).toFixed(0)}%` : ""}
              </span>
            )}
          </div>
        ) : row.kind === "plan" ? (
          <div className="text-slate-300">
            <span className="text-slate-500">goal:</span> {truncate(String(row.payload.goal ?? ""), 120)}
          </div>
        ) : row.kind === "tool_call" ? (
          <div className="mono text-slate-300">
            <span className="text-agent">{String(row.payload.tool ?? "?")}</span>
            <span className="text-slate-500"> · {truncate(JSON.stringify(row.payload.args ?? {}), 120)}</span>
          </div>
        ) : row.kind === "tool_result" ? (
          <div className="mono text-slate-300">
            <span className="text-allowed">{String(row.payload.tool ?? "?")}</span>
            <span className="text-slate-500"> → {truncate(String(row.payload.sample ?? JSON.stringify(row.payload.result ?? {})), 120)}</span>
          </div>
        ) : (
          <div className="text-slate-400">{truncate(JSON.stringify(row.payload), 140)}</div>
        )}
        <div className="mono mt-0.5 flex items-center gap-2 text-[9px] text-slate-600">
          <span>{ts(row.ts)}</span>
          <span>· actor {row.actor}</span>
          <span className="truncate" title={row.hash}>· hash {row.hash.slice(0, 12)}…</span>
        </div>
      </div>
    </div>
  );
}

function kindColor(kind: string): string {
  switch (kind) {
    case "plan": return "#a78bfa";
    case "tool_call": return "#22d3ee";
    case "tool_result": return "#10b981";
    case "approval_requested":
    case "approval_decided": return "#f59e0b";
    case "state_transition": return "#64748b";
    default: return "#94a3b8";
  }
}

// ----------- threats / approvals -----------

function ThreatsSection({ data }: { data: SessionTimeline }) {
  if (data.threats.length === 0) return null;
  return (
    <Section icon={<AlertOctagon size={13} />} title={`Threats (${data.threats.length})`}>
      <div className="flex flex-col gap-1.5">
        {data.threats.map((t) => {
          const color = SEVERITY_COLOR[t.severity] ?? "#ef4444";
          return (
            <div
              key={t.id}
              className="rounded-lg border px-2.5 py-1.5 text-[11px]"
              style={{ background: `${color}0d`, borderColor: `${color}26` }}
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="mono text-[10px] font-semibold uppercase tracking-wider" style={{ color }}>
                  {t.severity}
                </span>
                <span className="mono text-slate-200">{t.category}</span>
                <span className="mono text-[10px] text-slate-500">score {t.score.toFixed(2)}</span>
                <span className="mono ml-auto text-[9px] text-slate-500">{ts(t.ts)}</span>
              </div>
              <div className="mono mt-0.5 text-[10px] text-slate-400">
                {truncate(JSON.stringify(t.evidence), 160)}
              </div>
            </div>
          );
        })}
      </div>
    </Section>
  );
}

function ApprovalsSection({ data }: { data: SessionTimeline }) {
  if (data.approvals.length === 0) return null;
  return (
    <Section icon={<CheckCircle2 size={13} />} title={`Approvals (${data.approvals.length})`}>
      <div className="flex flex-col gap-1.5">
        {data.approvals.map((a) => {
          const color = a.status === "PENDING"
            ? "#f59e0b"
            : a.decision === "ALLOW"
              ? "#10b981"
              : "#ef4444";
          return (
            <div
              key={a.id}
              className="rounded-lg border px-2.5 py-1.5 text-[11px]"
              style={{ background: `${color}0d`, borderColor: `${color}26` }}
            >
              <div className="flex flex-wrap items-center gap-2">
                <Chip label="status" value={a.status} color={color} />
                <Chip label="blast" value={a.blastRadius.toFixed(2)} />
                {a.decision && <DecisionBadge decision={a.decision} />}
                <span className="mono ml-auto text-[9px] text-slate-500">{ts(a.requestedAt)}</span>
              </div>
              <div className="mt-1 text-slate-300">{a.intent}</div>
              {a.approverId && (
                <div className="mono mt-0.5 text-[10px] text-slate-500">decided by {a.approverId}</div>
              )}
            </div>
          );
        })}
      </div>
    </Section>
  );
}

// ----------- shared bits -----------

function Section({ title, icon, children }: { title: string; icon?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-white/5 bg-white/[0.02] p-3">
      <header className="mb-2 flex items-center gap-2">
        {icon && <span className="text-agent">{icon}</span>}
        <h3 className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-300">{title}</h3>
      </header>
      {children}
    </section>
  );
}

function ts(s: string) {
  try {
    return new Date(s).toLocaleTimeString("en-US", { hour12: false });
  } catch {
    return s;
  }
}

function truncate(s: string, n: number) {
  if (!s) return "";
  return s.length > n ? s.slice(0, n) + "…" : s;
}
