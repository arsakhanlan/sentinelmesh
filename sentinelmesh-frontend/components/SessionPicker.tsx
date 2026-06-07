"use client";

import { FileBadge } from "lucide-react";
import { useMemo } from "react";
import type { AgentEvent } from "@/lib/types";
import { DECISION_COLOR } from "@/lib/config";

interface Row {
  sessionId: string;
  lastTs: string;
  count: number;
  lastDecision: string | null;
  riskMax: number;
  goal: string | null;
  blocked: boolean;
}

/**
 * Compact list of distinct sessions seen on the firehose, newest first.
 * Each row is clickable and opens the forensics drawer for that session.
 *
 * Lightweight: derives state purely from the in-memory event buffer the
 * useFirehose hook already maintains — no extra REST round-trips.
 */
export function SessionPicker({
  events,
  selected,
  onSelect,
}: {
  events: AgentEvent[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const rows = useMemo(() => {
    const map = new Map<string, Row>();
    for (const ev of events) {
      const sid = ev.sessionId;
      if (!sid) continue;
      const cur = map.get(sid) ?? {
        sessionId: sid,
        lastTs: ev.timestamp,
        count: 0,
        lastDecision: null,
        riskMax: 0,
        goal: null,
        blocked: false,
      };
      cur.count += 1;
      if (ev.timestamp > cur.lastTs) cur.lastTs = ev.timestamp;
      if (ev.kind === "plan" && ev.goal) cur.goal = ev.goal;
      if (ev.kind === "sentinel_decision" && ev.decision) {
        cur.lastDecision = ev.decision;
        if (ev.decision === "BLOCK" || ev.decision === "QUARANTINE") cur.blocked = true;
      }
      const r = ev.risk?.composite;
      if (typeof r === "number" && r > cur.riskMax) cur.riskMax = r;
      map.set(sid, cur);
    }
    return Array.from(map.values()).sort((a, b) => b.lastTs.localeCompare(a.lastTs));
  }, [events]);

  if (rows.length === 0) {
    return (
      <div className="mono py-4 text-center text-[10px] tracking-wider text-slate-600">
        no sessions yet
      </div>
    );
  }

  return (
    <ul className="flex max-h-full flex-col gap-1 overflow-y-auto pr-1">
      {rows.map((r) => {
        const isSel = r.sessionId === selected;
        const color = r.lastDecision ? DECISION_COLOR[r.lastDecision] ?? "#64748b" : "#22d3ee";
        return (
          <li key={r.sessionId}>
            <button
              onClick={() => onSelect(r.sessionId)}
              className={`group flex w-full items-start gap-2 rounded-lg border px-2 py-1.5 text-left transition ${
                isSel
                  ? "border-agent/60 bg-agent/10"
                  : "border-white/5 bg-white/[0.02] hover:bg-white/[0.05]"
              }`}
            >
              <FileBadge size={12} className="mt-0.5 text-slate-400 group-hover:text-agent" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-1.5">
                  <span className="mono truncate text-[10px] text-slate-300">
                    {r.sessionId.slice(0, 8)}…{r.sessionId.slice(-4)}
                  </span>
                  {r.lastDecision && (
                    <span
                      className="mono ml-auto rounded px-1 py-px text-[8px] uppercase tracking-wider"
                      style={{ color, background: `${color}1a` }}
                    >
                      {r.lastDecision}
                    </span>
                  )}
                </div>
                {r.goal ? (
                  <div className="mt-0.5 line-clamp-1 text-[10px] text-slate-400">{r.goal}</div>
                ) : (
                  <div className="mono mt-0.5 text-[9px] text-slate-600">
                    {r.count} events · risk {Math.round(r.riskMax * 100)}
                  </div>
                )}
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
