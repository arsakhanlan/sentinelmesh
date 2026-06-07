"use client";

/**
 * Policy Lab — the YAML editor + simulator page.
 *
 * The premise: "we have policies" is a marketing claim. "We have *testable*
 * policies" is a security-team-buys-it claim. This page is what makes the
 * difference. A security engineer:
 *
 *   1. Sees the active bundle pre-loaded (read from the backend on mount).
 *   2. Edits it freely in the textarea.
 *   3. Hits "Run simulation" → the backend replays the last N hours of
 *      audited decisions through the candidate bundle and returns a diff.
 *   4. Skims the change summary (ALLOW→BLOCK = "false positive risk",
 *      BLOCK→ALLOW = "did you mean to weaken this?") and per-category
 *      sample events to spot-check.
 *
 * Importantly, "Run simulation" is read-only. There is no "deploy" button —
 * the prototype intentionally stops short of mutating the live bundle, so
 * the demo can't accidentally brick the policy engine mid-pitch.
 */

import { useCallback, useEffect, useState } from "react";
import { motion } from "framer-motion";
import { FlaskConical, Play, RotateCcw, AlertTriangle, ChevronDown, ChevronRight, FileText } from "lucide-react";
import { api, type PolicySimulationDiff, type PolicySimulationResult } from "@/lib/api";
import { TopBar } from "@/components/TopBar";
import { Panel } from "@/components/ui";

const WINDOW_OPTIONS = [1, 6, 24, 72, 168] as const;

const DECISION_COLOR: Record<string, string> = {
  ALLOW: "#10b981",
  BLOCK: "#ef4444",
  REWRITE: "#f59e0b",
  REQUIRE_APPROVAL: "#22d3ee",
  QUARANTINE: "#a855f7",
};

const CHANGE_BAND: Record<string, string> = {
  // Lighter rows for "wins" (catching more), darker for "loosenings"
  "ALLOW->BLOCK":           "#ef4444",
  "ALLOW->REQUIRE_APPROVAL":"#f59e0b",
  "ALLOW->REWRITE":         "#f59e0b",
  "ALLOW->QUARANTINE":      "#a855f7",
  "BLOCK->ALLOW":           "#10b981",
  "BLOCK->REWRITE":         "#22d3ee",
  "BLOCK->REQUIRE_APPROVAL":"#22d3ee",
  "REQUIRE_APPROVAL->ALLOW":"#10b981",
  "REQUIRE_APPROVAL->BLOCK":"#ef4444",
  "REWRITE->ALLOW":         "#10b981",
  "REWRITE->BLOCK":         "#ef4444",
};

export default function PoliciesPage() {
  const [originalYaml, setOriginalYaml] = useState<string | null>(null);
  const [yaml, setYaml] = useState("");
  const [windowHours, setWindowHours] = useState<number>(24);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PolicySimulationResult | null>(null);

  useEffect(() => {
    api.policyCurrentSource()
      .then((src) => {
        setOriginalYaml(src);
        setYaml(src);
      })
      .catch((e) => setError(`Failed to load current bundle: ${e.message}`));
  }, []);

  const runSim = useCallback(async () => {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const r = await api.policySimulate(yaml, windowHours);
      setResult(r);
    } catch (e: any) {
      setError(e.message || String(e));
    } finally {
      setRunning(false);
    }
  }, [yaml, windowHours]);

  const reset = useCallback(() => {
    if (originalYaml != null) setYaml(originalYaml);
    setResult(null);
    setError(null);
  }, [originalYaml]);

  const dirty = originalYaml != null && yaml !== originalYaml;

  return (
    <main className="mx-auto flex max-w-[1600px] flex-col gap-4 p-4">
      <TopBar showFirehose={false} />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.1fr_1fr]">
        {/* ----- Editor ----- */}
        <Panel
          title="Candidate Bundle (YAML)"
          icon={<FileText size={14} />}
          accent="#22d3ee"
          right={
            <div className="flex items-center gap-2">
              {dirty && (
                <span className="rounded-md bg-amber-500/15 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-300">
                  modified
                </span>
              )}
              <button
                onClick={reset}
                disabled={!dirty}
                className="flex items-center gap-1 rounded-md border border-white/10 px-2 py-1 text-[10px] font-semibold uppercase tracking-wide text-slate-300 transition hover:border-white/30 disabled:opacity-40"
              >
                <RotateCcw size={11} />
                Reset
              </button>
            </div>
          }
          bodyClassName="!p-0"
        >
          <textarea
            spellCheck={false}
            value={yaml}
            onChange={(e) => setYaml(e.target.value)}
            className="mono h-[560px] w-full resize-none bg-transparent p-4 font-mono text-[12px] leading-relaxed text-slate-200 outline-none placeholder:text-slate-600"
            placeholder={originalYaml == null ? "Loading current bundle…" : ""}
          />
        </Panel>

        {/* ----- Simulation panel ----- */}
        <Panel
          title="What-If Simulation"
          icon={<FlaskConical size={14} />}
          accent="#a855f7"
          right={
            <div className="flex items-center gap-2">
              <label className="text-[10px] uppercase tracking-widest text-slate-500">
                Window
              </label>
              <select
                value={windowHours}
                onChange={(e) => setWindowHours(Number(e.target.value))}
                className="rounded-md border border-white/10 bg-black/40 px-2 py-1 text-[11px] text-slate-200 outline-none"
              >
                {WINDOW_OPTIONS.map((h) => (
                  <option key={h} value={h}>{h}h</option>
                ))}
              </select>
              <button
                onClick={runSim}
                disabled={running || !yaml}
                className="flex items-center gap-1.5 rounded-md bg-fuchsia-500/20 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-fuchsia-200 transition hover:bg-fuchsia-500/30 disabled:opacity-40"
              >
                <Play size={11} />
                {running ? "Running…" : "Run simulation"}
              </button>
            </div>
          }
        >
          <div className="flex h-[560px] flex-col gap-3 overflow-hidden">
            {error && (
              <div className="flex items-start gap-2 rounded-md border border-red-500/30 bg-red-500/10 p-3 text-[12px] text-red-200">
                <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                <div>
                  <div className="font-semibold">Simulation failed</div>
                  <div className="mono mt-1 text-[11px] text-red-300/80">{error}</div>
                </div>
              </div>
            )}

            {!result && !error && !running && (
              <EmptyState />
            )}

            {result && (
              <ResultView result={result} />
            )}
          </div>
        </Panel>
      </div>

      <footer className="flex flex-wrap items-center gap-2 px-1 text-[11px] text-slate-500">
        <span className="rounded bg-white/[0.03] px-2 py-1">
          Read-only · the live bundle is never modified by this page.
        </span>
        <span className="rounded bg-white/[0.03] px-2 py-1">
          Replay uses the boolean signals (over_budget, has_secret, has_pii) persisted with each audited decision.
        </span>
      </footer>
    </main>
  );
}

// -------------------------------------------------------------------- //

function EmptyState() {
  return (
    <div className="m-auto max-w-md text-center text-slate-400">
      <FlaskConical size={36} className="mx-auto mb-3 text-fuchsia-400/70" />
      <p className="text-sm">
        Edit the YAML on the left, then run the simulation. We replay your
        candidate bundle against recent audited decisions and tell you which
        ones would have changed.
      </p>
      <p className="mt-2 text-xs text-slate-500">
        Try raising every <code>risk &gt;= …</code> threshold by 0.1 and see how
        many BLOCKs you'd lose.
      </p>
    </div>
  );
}

function ResultView({ result }: { result: PolicySimulationResult }) {
  const totalChanges = Object.values(result.changeCounts).reduce((a, b) => a + b, 0);
  return (
    <div className="flex flex-col gap-3 overflow-y-auto pr-1">
      <Header result={result} totalChanges={totalChanges} />

      {result.warnings.length > 0 && (
        <div className="rounded-md border border-amber-500/30 bg-amber-500/10 p-2.5 text-[11px] text-amber-200">
          {result.warnings.map((w, i) => <div key={i}>⚠ {w}</div>)}
        </div>
      )}

      {totalChanges === 0 ? (
        <div className="rounded-md border border-white/10 bg-white/[0.02] p-4 text-center text-[12px] text-slate-300">
          No decisions would change under your candidate bundle. The audited
          decisions match exactly. This is the typical result for a no-op edit.
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {Object.keys(result.changeCounts)
            .sort((a, b) => result.changeCounts[b] - result.changeCounts[a])
            .map((bucket) => (
              <ChangeBucket
                key={bucket}
                bucket={bucket}
                count={result.changeCounts[bucket]}
                samples={result.samples[bucket] || []}
              />
            ))}
        </div>
      )}
    </div>
  );
}

function Header({ result, totalChanges }: { result: PolicySimulationResult; totalChanges: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      className="grid grid-cols-3 gap-2 rounded-md border border-white/5 bg-white/[0.02] p-3"
    >
      <Stat label="Events replayed" value={result.eventsConsidered.toString()} />
      <Stat label="Rules in bundle" value={result.ruleCount.toString()} />
      <Stat
        label="Decisions changed"
        value={totalChanges.toString()}
        accent={totalChanges > 0 ? "#f59e0b" : "#10b981"}
      />
    </motion.div>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: string }) {
  return (
    <div>
      <div className="text-[9px] uppercase tracking-widest text-slate-500">{label}</div>
      <div className="mono text-lg font-bold" style={{ color: accent ?? "#e2e8f0" }}>{value}</div>
    </div>
  );
}

function ChangeBucket({
  bucket, count, samples,
}: {
  bucket: string;
  count: number;
  samples: PolicySimulationDiff[];
}) {
  const [open, setOpen] = useState(true);
  const color = CHANGE_BAND[bucket] || "#94a3b8";
  const [oldD, newD] = bucket.split("->");

  return (
    <div className="overflow-hidden rounded-md border border-white/10 bg-white/[0.02]">
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
        style={{ borderLeft: `3px solid ${color}` }}
      >
        {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
        <div className="flex items-center gap-1.5 text-[12px]">
          <DecisionPill d={oldD} />
          <span className="text-slate-500">→</span>
          <DecisionPill d={newD} />
        </div>
        <span className="mono ml-auto text-[12px] font-semibold" style={{ color }}>
          {count}
        </span>
      </button>

      {open && samples.length > 0 && (
        <div className="border-t border-white/5">
          <table className="mono w-full text-[11px]">
            <thead>
              <tr className="text-slate-500">
                <th className="px-3 py-1.5 text-left font-normal">#</th>
                <th className="px-3 py-1.5 text-left font-normal">Tool</th>
                <th className="px-3 py-1.5 text-right font-normal">Risk</th>
                <th className="px-3 py-1.5 text-right font-normal">Blast</th>
                <th className="px-3 py-1.5 text-left font-normal">Old rule</th>
                <th className="px-3 py-1.5 text-left font-normal">New rule</th>
              </tr>
            </thead>
            <tbody>
              {samples.map((s) => (
                <tr key={s.sequence} className="border-t border-white/5 text-slate-300">
                  <td className="px-3 py-1.5 text-slate-500">{s.sequence}</td>
                  <td className="px-3 py-1.5">{s.tool || "—"}</td>
                  <td className="px-3 py-1.5 text-right">{s.risk.toFixed(2)}</td>
                  <td className="px-3 py-1.5 text-right">{s.blast.toFixed(2)}</td>
                  <td className="px-3 py-1.5 text-slate-500">{s.oldRule}</td>
                  <td className="px-3 py-1.5" style={{ color: DECISION_COLOR[s.newDecision] || "#e2e8f0" }}>
                    {s.newRule}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function DecisionPill({ d }: { d: string }) {
  const color = DECISION_COLOR[d] || "#94a3b8";
  return (
    <span
      className="rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide"
      style={{ background: `${color}22`, color }}
    >
      {d}
    </span>
  );
}
