"use client";

import { AnimatePresence, motion } from "framer-motion";
import { Brain } from "lucide-react";
import { useMemo } from "react";
import type { AgentEvent } from "@/lib/types";
import { SEVERITY_COLOR } from "@/lib/config";

export function ThreatFeed({ events }: { events: AgentEvent[] }) {
  const threats = useMemo(
    () => events.filter((e) => e.kind === "threat").slice(-30).reverse(),
    [events]
  );

  return (
    <div className="h-full overflow-y-auto pr-1 flex flex-col gap-2">
      {threats.length === 0 && (
        <div className="flex h-full min-h-[120px] items-center justify-center text-slate-600 text-[11px] mono tracking-widest">
          NO THREATS DETECTED
        </div>
      )}
      <AnimatePresence initial={false}>
        {threats.map((t) => {
          const color = SEVERITY_COLOR[t.severity ?? "MEDIUM"] ?? "#ef4444";
          // L7 attack-memory hits carry a 'known_attack' label + 'similarity'
          // score in their evidence. Surface those as a first-class badge so
          // operators immediately see "this was caught by the learning layer".
          const evidence = t.evidence ?? {};
          const knownAttack =
            typeof evidence.known_attack === "string"
              ? (evidence.known_attack as string)
              : null;
          const similarity =
            typeof evidence.similarity === "number"
              ? (evidence.similarity as number)
              : null;
          return (
            <motion.div
              key={t.eventId}
              layout
              initial={{ opacity: 0, scale: 0.96, y: -8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0 }}
              className="rounded-lg border p-2.5"
              style={{ background: `${color}12`, borderColor: `${color}40` }}
            >
              <div className="flex items-center gap-2">
                <span
                  className="h-2 w-2 rounded-full shrink-0"
                  style={{ background: color, boxShadow: `0 0 8px ${color}` }}
                />
                <span className="mono text-xs font-semibold" style={{ color }}>
                  {t.category}
                </span>
                <span
                  className="ml-auto mono text-[9px] uppercase tracking-widest rounded px-1.5 py-0.5"
                  style={{ color, background: `${color}1f` }}
                >
                  {t.severity}
                </span>
                {t.score != null && (
                  <span className="mono text-[10px] text-slate-500">{Math.round(t.score * 100)}</span>
                )}
              </div>
              {knownAttack && (
                <div
                  className="mt-2 flex items-center gap-1.5 rounded-md border border-fuchsia-500/40 bg-fuchsia-500/10 px-2 py-1 text-[10px] font-semibold text-fuchsia-200"
                  title={`Matched a known attack fingerprint at similarity ${similarity ?? "?"}`}
                >
                  <Brain size={11} />
                  <span className="mono uppercase tracking-widest">L7 match · {knownAttack}</span>
                  {similarity != null && (
                    <span className="mono ml-auto text-fuchsia-300">{(similarity * 100).toFixed(0)}%</span>
                  )}
                </div>
              )}
              {t.evidence && !knownAttack && (
                <pre className="mono mt-1.5 max-h-20 overflow-y-auto whitespace-pre-wrap break-words text-[10px] leading-snug text-slate-400">
                  {JSON.stringify(t.evidence, null, 0)}
                </pre>
              )}
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
