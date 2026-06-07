"use client";

import { AnimatePresence, motion } from "framer-motion";
import { Check, X, ShieldAlert } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Approval } from "@/lib/types";

function blastColor(b: number) {
  if (b >= 0.7) return "#ef4444";
  if (b >= 0.4) return "#f59e0b";
  return "#22d3ee";
}

export function ApprovalCenter({ refreshSignal }: { refreshSignal: number }) {
  const [pending, setPending] = useState<Approval[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const all = await api.approvals();
      setPending(all.filter((a) => a.status === "PENDING"));
    } catch {
      /* backend may be warming up */
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 3500);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    if (refreshSignal > 0) load();
  }, [refreshSignal, load]);

  const decide = async (id: string, decision: "ALLOW" | "BLOCK") => {
    setBusy(id);
    try {
      await api.decide(id, decision);
      setPending((p) => p.filter((a) => a.id !== id));
    } catch {
      /* ignore */
    } finally {
      setBusy(null);
      load();
    }
  };

  return (
    <div className="h-full overflow-y-auto pr-1 flex flex-col gap-2">
      {pending.length === 0 && (
        <div className="flex h-full min-h-[120px] flex-col items-center justify-center gap-1 text-slate-600">
          <ShieldAlert size={18} className="opacity-40" />
          <span className="mono text-[11px] tracking-widest">QUEUE CLEAR</span>
        </div>
      )}
      <AnimatePresence initial={false}>
        {pending.map((a) => {
          const bc = blastColor(a.blastRadius);
          return (
            <motion.div
              key={a.id}
              layout
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95 }}
              className="rounded-lg border border-approve/40 bg-approve/[0.07] p-3 animate-pulseRing"
              style={{ animationDuration: "2.4s" }}
            >
              <div className="flex items-start gap-2">
                <span className="text-[12px] font-semibold text-slate-100 leading-snug">{a.intent}</span>
                <span
                  className="ml-auto mono shrink-0 rounded px-1.5 py-0.5 text-[9px] uppercase tracking-widest"
                  style={{ color: bc, background: `${bc}1f` }}
                >
                  blast {Math.round(a.blastRadius * 100)}
                </span>
              </div>

              {a.requestedPayload && (
                <pre className="mono mt-2 max-h-24 overflow-y-auto whitespace-pre-wrap break-words rounded bg-black/30 p-2 text-[10px] leading-snug text-slate-400">
                  {JSON.stringify(a.requestedPayload, null, 2)}
                </pre>
              )}

              <div className="mt-2.5 flex gap-2">
                <button
                  disabled={busy === a.id}
                  onClick={() => decide(a.id, "ALLOW")}
                  className="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-allowed/15 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-allowed transition hover:bg-allowed/25 disabled:opacity-40"
                >
                  <Check size={13} /> Approve
                </button>
                <button
                  disabled={busy === a.id}
                  onClick={() => decide(a.id, "BLOCK")}
                  className="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-threat/15 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-threat transition hover:bg-threat/25 disabled:opacity-40"
                >
                  <X size={13} /> Deny
                </button>
              </div>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
