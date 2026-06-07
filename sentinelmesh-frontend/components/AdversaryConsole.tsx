"use client";

import { motion } from "framer-motion";
import { Crosshair, Loader2, Play, Bot } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { ScenarioInfo } from "@/lib/types";

export function AdversaryConsole() {
  const [scenarios, setScenarios] = useState<ScenarioInfo[]>([]);
  const [firing, setFiring] = useState<string | null>(null);
  const [goal, setGoal] = useState("");
  const [agentBusy, setAgentBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    api.scenarios().then(setScenarios).catch(() => setScenarios([]));
  }, []);

  const flash = (m: string) => {
    setToast(m);
    setTimeout(() => setToast(null), 2600);
  };

  const fire = async (s: ScenarioInfo) => {
    setFiring(s.id);
    try {
      await api.fire(s.id);
      flash(`Fired: ${s.displayName}`);
    } catch (e) {
      flash(`Failed: ${String(e).slice(0, 60)}`);
    } finally {
      setFiring(null);
    }
  };

  const runAgent = async () => {
    if (goal.trim().length < 3) return;
    setAgentBusy(true);
    try {
      const r = await api.runAgent(goal.trim());
      flash(`Agent dispatched · ${r.status}`);
      setGoal("");
    } catch (e) {
      flash(`Agent error: ${String(e).slice(0, 60)}`);
    } finally {
      setAgentBusy(false);
    }
  };

  return (
    <div className="flex h-full flex-col gap-3">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {scenarios.length === 0 && (
          <span className="mono col-span-full text-[11px] text-slate-600">Loading scenarios…</span>
        )}
        {scenarios.map((s) => (
          <motion.button
            key={s.id}
            whileTap={{ scale: 0.97 }}
            onClick={() => fire(s)}
            disabled={firing === s.id}
            className="group flex items-start gap-2 rounded-lg border border-white/8 bg-white/[0.03] p-2.5 text-left transition hover:border-threat/40 hover:bg-threat/[0.06] disabled:opacity-50"
          >
            <span className="mt-0.5 text-threat/80 group-hover:text-threat">
              {firing === s.id ? <Loader2 size={14} className="animate-spin" /> : <Crosshair size={14} />}
            </span>
            <span className="min-w-0">
              <span className="block text-[12px] font-semibold text-slate-200">{s.displayName}</span>
              <span className="block truncate text-[10px] text-slate-500">{s.description}</span>
            </span>
          </motion.button>
        ))}
      </div>

      <div className="mt-auto rounded-lg border border-agent/20 bg-agent/[0.04] p-2.5">
        <div className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-agent">
          <Bot size={12} /> Run a live agent
        </div>
        <div className="flex gap-2">
          <input
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && runAgent()}
            placeholder="e.g. Book a hotel in Lisbon for next weekend"
            className="mono min-w-0 flex-1 rounded-md border border-white/10 bg-black/30 px-2.5 py-1.5 text-[12px] text-slate-200 placeholder:text-slate-600 focus:border-agent/50 focus:outline-none"
          />
          <button
            onClick={runAgent}
            disabled={agentBusy || goal.trim().length < 3}
            className="flex items-center gap-1.5 rounded-md bg-agent/15 px-3 text-[11px] font-semibold uppercase tracking-wider text-agent transition hover:bg-agent/25 disabled:opacity-40"
          >
            {agentBusy ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            Run
          </button>
        </div>
      </div>

      {toast && (
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="mono rounded-md bg-black/50 px-3 py-1.5 text-[11px] text-agent"
        >
          {toast}
        </motion.div>
      )}
    </div>
  );
}
