"use client";

import { AnimatePresence, motion } from "framer-motion";
import { FileBadge } from "lucide-react";
import { useEffect, useRef } from "react";
import type { AgentEvent } from "@/lib/types";
import { DecisionBadge } from "./DecisionBadge";
import { SEVERITY_COLOR } from "@/lib/config";

function ts(s?: string) {
  if (!s) return "";
  try {
    return new Date(s).toLocaleTimeString("en-US", { hour12: false });
  } catch {
    return "";
  }
}

function short(obj: unknown, n = 160) {
  if (obj == null) return "";
  const s = typeof obj === "string" ? obj : JSON.stringify(obj);
  return s.length > n ? s.slice(0, n) + "…" : s;
}

function Row({ ev, onOpenSession }: { ev: AgentEvent; onOpenSession?: (id: string) => void }) {
  const base =
    "flex gap-2.5 px-3 py-2 rounded-lg border text-[12px] leading-relaxed";
  let accent = "#22d3ee";
  let label = ev.kind.toUpperCase();
  let body: React.ReactNode = null;

  switch (ev.kind) {
    case "plan":
      accent = "#a78bfa";
      label = "PLAN";
      body = <span className="text-slate-300">{short(ev.goal, 120)}</span>;
      break;
    case "tool_call":
      accent = "#22d3ee";
      label = "TOOL CALL";
      body = (
        <span>
          <span className="mono text-agent">{ev.tool}</span>
          <span className="text-slate-500"> · {short(ev.args, 120)}</span>
        </span>
      );
      break;
    case "tool_result":
      accent = "#10b981";
      label = "RESULT";
      body = (
        <span>
          <span className="mono text-allowed">{ev.tool}</span>
          <span className="text-slate-400"> → {short(ev.contentSample ?? ev.result, 140)}</span>
        </span>
      );
      break;
    case "sentinel_decision":
      accent = "#22d3ee";
      label = "SENTINEL";
      body = (
        <span className="flex flex-wrap items-center gap-2">
          <DecisionBadge decision={ev.decision} />
          <span className="text-slate-400">{short(ev.reason, 140)}</span>
          {ev.risk?.composite != null && (
            <span className="mono text-[10px] text-slate-500">
              risk {Math.round((ev.risk.composite || 0) * 100)}
            </span>
          )}
        </span>
      );
      break;
    case "threat":
      accent = SEVERITY_COLOR[ev.severity ?? "MEDIUM"] ?? "#ef4444";
      label = "THREAT";
      body = (
        <span className="flex flex-wrap items-center gap-2">
          <span className="mono font-semibold" style={{ color: accent }}>
            {ev.category}
          </span>
          <span className="text-[10px] uppercase tracking-wider" style={{ color: accent }}>
            {ev.severity}
          </span>
          <span className="text-slate-400">{short(ev.evidence, 120)}</span>
        </span>
      );
      break;
    case "approval_requested":
      accent = "#f59e0b";
      label = "APPROVAL REQ";
      body = <span className="text-slate-300">{short(ev.intent, 130)}</span>;
      break;
    case "approval_decided":
      accent = "#f59e0b";
      label = "APPROVAL";
      body = (
        <span className="flex items-center gap-2">
          <DecisionBadge decision={ev.decision} />
          <span className="text-slate-500">by {ev.approverId}</span>
        </span>
      );
      break;
    case "state_transition":
      accent = "#64748b";
      label = "STATE";
      body = (
        <span className="mono text-slate-400">
          {ev.from} → <span className="text-slate-200">{ev.to}</span>
        </span>
      );
      break;
  }

  return (
    <motion.div
      layout
      initial={{ opacity: 0, x: -12 }}
      animate={{ opacity: 1, x: 0 }}
      className={base}
      style={{ background: `${accent}0d`, borderColor: `${accent}26` }}
    >
      <span className="mono text-[9px] text-slate-600 pt-0.5 shrink-0 w-14">{ts(ev.timestamp)}</span>
      <span
        className="mono text-[9px] font-bold tracking-wider shrink-0 w-[78px] pt-0.5"
        style={{ color: accent }}
      >
        {label}
      </span>
      <div className="min-w-0 flex-1 break-words">{body}</div>
      {onOpenSession && ev.sessionId && (
        <button
          onClick={() => onOpenSession(ev.sessionId)}
          className="mono flex shrink-0 items-center gap-1 self-start rounded px-1.5 py-0.5 text-[9px] uppercase tracking-wider text-slate-500 hover:bg-white/5 hover:text-agent"
          title="Open forensics drawer for this session"
        >
          <FileBadge size={10} />
          forensics
        </button>
      )}
    </motion.div>
  );
}

export function LiveTheater({
  events,
  onOpenSession,
}: {
  events: AgentEvent[];
  onOpenSession?: (id: string) => void;
}) {
  const endRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [events.length]);

  return (
    <div className="h-full overflow-y-auto pr-1 flex flex-col gap-1.5">
      {events.length === 0 && (
        <div className="flex h-full min-h-[200px] flex-col items-center justify-center gap-2 text-center text-slate-600">
          <span className="mono text-xs tracking-widest animate-flicker">AWAITING AGENT ACTIVITY…</span>
          <span className="text-[11px] text-slate-700">Fire a scenario or run a live agent to begin.</span>
        </div>
      )}
      <AnimatePresence initial={false}>
        {events.map((ev) => (
          <Row key={ev.eventId} ev={ev} onOpenSession={onOpenSession} />
        ))}
      </AnimatePresence>
      <div ref={endRef} />
    </div>
  );
}
