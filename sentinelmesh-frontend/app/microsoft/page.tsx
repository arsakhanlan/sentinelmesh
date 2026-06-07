"use client";

/**
 * /microsoft — Microsoft AI ecosystem integration page.
 *
 * The visual centerpiece for the demo. Shows judges, in one scroll:
 *
 *   1. Hero ASR number — naked agent ASR vs SentinelMesh-protected ASR,
 *      measured by our PyRIT-style red-team harness (Microsoft's metric).
 *   2. Three-line MAF integration code snippet.
 *   3. Three-line Semantic Kernel filter snippet — same shape, proves we
 *      cover SK too even though MAF is the new blessed framework.
 *   4. Live preview of the Foundry IQ markdown export (policy bundle as
 *      an ingestible knowledge document).
 *   5. OTel emission status + the gen_ai semantic-convention attributes
 *      Sentinel decisions add to every Foundry trace.
 *   6. Per-strategy ASR breakdown with the documented PyRIT strategies.
 *
 * Data sources, with graceful fallback so the page always renders:
 *   - /microsoft/redteam-report.json — baked at build/demo time by
 *     `python -m examples.redteam_compare --out public/microsoft/...`.
 *   - {backend}/api/v1/foundry-iq/policies — live policy markdown when the
 *     Spring backend is up; falls back to /microsoft/policies-sample.md.
 */

import { useEffect, useMemo, useState } from "react";
import {
  Activity, BarChart3, BrainCircuit, ExternalLink, FileBadge, FlaskConical,
  Network, Shield, Sparkles, TerminalSquare,
} from "lucide-react";

import { CONFIG } from "@/lib/config";
import type { MetricsSummary } from "@/lib/types";
import { Panel } from "@/components/ui";
import { TopBar } from "@/components/TopBar";

type StrategyStats = { attempts: number; successes: number; asr: number };

type RedTeamReport = {
  comparison: {
    naked_asr: number;
    sentinel_asr: number;
    absolute_drop: number;
    relative_reduction: number;
    by_strategy: Record<string, { naked_asr: number; sentinel_asr: number }>;
  };
  naked: {
    total_attempts: number;
    successes: number;
    asr: number;
    by_strategy: Record<string, StrategyStats>;
    by_risk_category: Record<string, StrategyStats>;
  };
  sentinel: {
    total_attempts: number;
    successes: number;
    asr: number;
  };
};

const MAF_SNIPPET = `from agent_framework import Agent
from agent_framework.openai import OpenAIChatClient
from sentinelmesh_agents.microsoft.maf_middleware import attach_sentinel

sentinel = await attach_sentinel(goal="book a hotel in Bangalore")

agent = Agent(
    name="skynest_concierge",
    client=OpenAIChatClient(model="gpt-4o-mini"),
    instructions="You book hotels for travellers, safely.",
    tools=[book_hotel, send_email],
    middleware=[sentinel],          # ← the whole integration
)`;

const SK_SNIPPET = `from semantic_kernel import Kernel
from semantic_kernel.filters import FilterTypes
from sentinelmesh_agents.microsoft.maf_middleware import (
    SentinelMiddleware, attach_sentinel,
)

kernel = Kernel()
sentinel = await attach_sentinel(goal="book a hotel in Bangalore")
kernel.add_filter(FilterTypes.FUNCTION_INVOCATION, sentinel)
# Same middleware works against MAF and SK — the
# FunctionInvocationContext shape is identical.`;

const OTEL_ATTRS: Array<{ key: string; meaning: string }> = [
  { key: "gen_ai.system",          meaning: "Set to 'sentinelmesh' so Foundry trace explorer groups them" },
  { key: "gen_ai.operation.name",  meaning: "Always 'execute_tool' (matches MAF child-span convention)" },
  { key: "gen_ai.tool.name",       meaning: "<plugin>.<function> — what the agent tried to call" },
  { key: "sentinelmesh.decision",  meaning: "ALLOW | REWRITE | REQUIRE_APPROVAL | BLOCK | QUARANTINE" },
  { key: "sentinelmesh.composite_risk", meaning: "Risk score in [0,1] — span goes red on BLOCK" },
  { key: "sentinelmesh.scanner.<L1|L2|...>", meaning: "Per-layer scanner score (DLP/CAP scored too)" },
];

const STRATEGY_ICONS: Record<string, string> = {
  Baseline: "—",
  Base64: "B64",
  ROT13: "R13",
  Flip: "<>",
  Morse: "·-",
  "Compose(Base64,ROT13)": "B64∘R13",
};

function pct(x: number): string {
  return `${(x * 100).toFixed(1)}%`;
}

export default function MicrosoftPage() {
  const [report, setReport] = useState<RedTeamReport | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [policiesMd, setPoliciesMd] = useState<string>("");
  const [policiesSource, setPoliciesSource] = useState<"live" | "sample" | "loading">("loading");
  const [liveMetrics, setLiveMetrics] = useState<MetricsSummary | null>(null);

  // Load the baked red-team report from /public/microsoft/redteam-report.json.
  useEffect(() => {
    fetch("/microsoft/redteam-report.json")
      .then(async (r) => {
        if (!r.ok) throw new Error(`${r.status}`);
        return r.json();
      })
      .then((data: RedTeamReport) => setReport(data))
      .catch((e) => setReportError(String(e)));
  }, []);

  // Try the live backend first; fall back to the bundled sample so the
  // page always shows something even when the backend is offline.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const r = await fetch(`${CONFIG.backendUrl}/api/v1/foundry-iq/policies`, {
          headers: { "X-API-Key": CONFIG.apiKey },
        });
        if (!r.ok) throw new Error(`${r.status}`);
        const text = await r.text();
        if (!cancelled) {
          setPoliciesMd(text);
          setPoliciesSource("live");
        }
      } catch {
        try {
          const r = await fetch("/microsoft/policies-sample.md");
          const text = await r.text();
          if (!cancelled) {
            setPoliciesMd(text);
            setPoliciesSource("sample");
          }
        } catch {
          if (!cancelled) {
            setPoliciesMd("# Foundry IQ export unavailable\n\nNeither the backend nor the bundled sample loaded.");
            setPoliciesSource("sample");
          }
        }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Poll live operational metrics so the page reflects activity from the SOC.
  useEffect(() => {
    let cancelled = false;
    const poll = () => {
      if (cancelled) return;
      fetch(`${CONFIG.backendUrl}/api/v1/metrics/summary`)
        .then(async (r) => {
          if (!r.ok) throw new Error(`${r.status}`);
          return r.json() as Promise<MetricsSummary>;
        })
        .then((m) => { if (!cancelled) setLiveMetrics(m); })
        .catch(() => { /* backend may be restarting; retry on next interval */ });
    };
    poll();
    const id = setInterval(poll, 4000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  const strategies = useMemo(() => {
    if (!report) return [];
    return Object.entries(report.comparison.by_strategy)
      .map(([name, s]) => ({ name, ...s }))
      .sort((a, b) => b.naked_asr - a.naked_asr);
  }, [report]);

  return (
    <main className="mx-auto flex min-h-screen max-w-[1400px] flex-col gap-4 p-4">
      <TopBar />

      <header className="glass rounded-2xl p-6">
        <div className="flex items-start gap-3 mb-2">
          <Sparkles size={20} className="text-cyan-300 mt-1" />
          <div>
            <h1 className="text-xl font-bold tracking-tight">Microsoft AI Integration</h1>
            <p className="text-sm text-slate-400 mt-1 max-w-3xl">
              SentinelMesh plugs natively into Microsoft Agent Framework (MAF), Azure AI Content
              Safety, the AI Red Teaming Agent, Foundry tracing, and Foundry IQ — three lines of
              code wrap any MAF or Semantic Kernel agent in our seven-layer policy engine.
            </p>
          </div>
        </div>
      </header>

      {/* Live operational metrics — updates every 4s from the backend */}
      {liveMetrics && (
        <div className="glass rounded-xl px-5 py-3 flex flex-wrap items-center gap-x-6 gap-y-1 text-[11px]">
          <span className="text-slate-400 uppercase tracking-widest text-[10px]">Live</span>
          <span className="text-slate-300">
            Threats{" "}
            <span className="mono text-red-400 font-semibold">{liveMetrics.threats_total}</span>
          </span>
          <span className="text-slate-300">
            Categories{" "}
            <span className="mono text-amber-400 font-semibold">
              {Object.keys(liveMetrics.threats_by_category).length}
            </span>
          </span>
          <span className="text-slate-300">
            Policies{" "}
            <span className="mono text-violet-400 font-semibold">{liveMetrics.policy_rules_loaded}</span>
          </span>
          <span className="text-slate-500 text-[10px]">
            p50 {liveMetrics.detect_latency_ms?.p50 != null ? `${liveMetrics.detect_latency_ms.p50.toFixed(1)}ms` : "—"}
            {" · "}
            p95 {liveMetrics.detect_latency_ms?.p95 != null ? `${liveMetrics.detect_latency_ms.p95.toFixed(1)}ms` : "—"}
          </span>
          <span className="text-slate-500 text-[10px] ml-auto">
            {liveMetrics.detect_latency_ms?.samples ?? 0} samples
          </span>
        </div>
      )}

      {/* ---- Hero ASR comparison ---- */}
      <Panel
        title="Attack Success Rate — naked vs SentinelMesh-protected"
        icon={<BarChart3 size={13} />}
        accent="#10b981"
        right={
          <a
            href="https://learn.microsoft.com/azure/foundry/concepts/ai-red-teaming-agent"
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1 text-[10px] text-slate-400 hover:text-cyan-300"
          >
            Microsoft AI Red Teaming Agent <ExternalLink size={11} />
          </a>
        }
      >
        {report ? (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="rounded-lg border border-red-500/30 bg-red-500/5 p-5">
              <div className="text-[10px] font-semibold uppercase tracking-widest text-red-300/80">
                Naked agent
              </div>
              <div className="mono text-4xl font-bold text-red-400 mt-1">
                {pct(report.comparison.naked_asr)}
              </div>
              <div className="text-[11px] text-slate-400 mt-2">
                {report.naked.successes} of {report.naked.total_attempts} attacks succeeded
              </div>
            </div>
            <div className="rounded-lg border border-emerald-500/40 bg-emerald-500/5 p-5">
              <div className="text-[10px] font-semibold uppercase tracking-widest text-emerald-300/80">
                SentinelMesh-protected
              </div>
              <div className="mono text-4xl font-bold text-emerald-400 mt-1">
                {pct(report.comparison.sentinel_asr)}
              </div>
              <div className="text-[11px] text-slate-400 mt-2">
                {report.sentinel.successes} of {report.sentinel.total_attempts} attacks succeeded
              </div>
            </div>
            <div className="rounded-lg border border-cyan-500/40 bg-cyan-500/5 p-5">
              <div className="text-[10px] font-semibold uppercase tracking-widest text-cyan-300/80">
                Reduction
              </div>
              <div className="mono text-4xl font-bold text-cyan-300 mt-1">
                {pct(report.comparison.relative_reduction)}
              </div>
              <div className="text-[11px] text-slate-400 mt-2">
                Absolute drop: {pct(report.comparison.absolute_drop)} pp · measured by
                Microsoft's Attack Success Rate metric
              </div>
            </div>
          </div>
        ) : reportError ? (
          <div className="text-sm text-red-300">
            Could not load red-team report: {reportError}.{" "}
            Run <code className="bg-white/5 px-1 rounded mono text-[11px]">python -m examples.redteam_compare</code> to generate one.
          </div>
        ) : (
          <div className="text-sm text-slate-500">Loading…</div>
        )}

        {strategies.length > 0 && (
          <div className="mt-5">
            <div className="text-[11px] uppercase tracking-widest text-slate-400 mb-2">
              Per-strategy breakdown (PyRIT obfuscation transforms)
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-2">
              {strategies.map((s) => (
                <div key={s.name} className="rounded-lg border border-white/5 bg-white/[0.02] p-3">
                  <div className="flex items-center gap-2">
                    <span className="mono text-[10px] text-slate-500 bg-white/[0.04] px-1.5 py-0.5 rounded">
                      {STRATEGY_ICONS[s.name] ?? "?"}
                    </span>
                    <div className="text-[11px] font-semibold text-slate-300 truncate">{s.name}</div>
                  </div>
                  <div className="mt-2 space-y-1">
                    <div className="flex items-center justify-between text-[10px]">
                      <span className="text-red-400">naked</span>
                      <span className="mono text-red-400">{pct(s.naked_asr)}</span>
                    </div>
                    <div className="flex items-center justify-between text-[10px]">
                      <span className="text-emerald-400">sentinel</span>
                      <span className="mono text-emerald-400">{pct(s.sentinel_asr)}</span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </Panel>

      {/* ---- Integration code snippets ---- */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <Panel
          title="Microsoft Agent Framework — three-line integration"
          icon={<BrainCircuit size={13} />}
          accent="#22d3ee"
          right={
            <a
              href="https://learn.microsoft.com/agent-framework/"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-[10px] text-slate-400 hover:text-cyan-300"
            >
              MAF docs <ExternalLink size={11} />
            </a>
          }
        >
          <pre className="mono text-[11px] leading-snug text-slate-300 bg-black/30 rounded-lg p-3 overflow-auto max-h-[280px]">
            {MAF_SNIPPET}
          </pre>
          <div className="text-[11px] text-slate-400 mt-2">
            Every tool call now flows through L1–L7 + CAP + DLP, gets policy-engine
            verdict, hash-chained audit, and live SOC dashboard event.
          </div>
        </Panel>

        <Panel
          title="Semantic Kernel — same middleware as a function-invocation filter"
          icon={<Shield size={13} />}
          accent="#a78bfa"
          right={
            <a
              href="https://learn.microsoft.com/semantic-kernel/concepts/enterprise-readiness/filters"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-[10px] text-slate-400 hover:text-cyan-300"
            >
              SK filter docs <ExternalLink size={11} />
            </a>
          }
        >
          <pre className="mono text-[11px] leading-snug text-slate-300 bg-black/30 rounded-lg p-3 overflow-auto max-h-[280px]">
            {SK_SNIPPET}
          </pre>
          <div className="text-[11px] text-slate-400 mt-2">
            SK is now Microsoft Agent Framework, but legacy SK deployments work too —
            <code className="bg-white/5 px-1 rounded mono text-[10px]">SentinelMiddleware</code> is duck-typed
            against the shared <code className="bg-white/5 px-1 rounded mono text-[10px]">FunctionInvocationContext</code> shape.
          </div>
        </Panel>
      </div>

      {/* ---- Foundry IQ + OTel ---- */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
        <Panel
          title="Foundry IQ knowledge-base export"
          icon={<FileBadge size={13} />}
          accent="#f59e0b"
          className="lg:col-span-3"
          right={
            <span
              className={`text-[10px] mono px-2 py-0.5 rounded ${
                policiesSource === "live"
                  ? "bg-emerald-500/15 text-emerald-300"
                  : policiesSource === "sample"
                  ? "bg-amber-500/15 text-amber-300"
                  : "bg-white/5 text-slate-400"
              }`}
            >
              {policiesSource === "live" ? "live · backend" : policiesSource === "sample" ? "sample · offline" : "loading…"}
            </span>
          }
        >
          <div className="text-[11px] text-slate-400 mb-2">
            <code className="bg-white/5 px-1 rounded mono text-[10px]">GET /api/v1/foundry-iq/policies</code> — markdown
            ingestible into a Foundry IQ knowledge base. Any Foundry-hosted agent can
            then ask <em>"what does the SentinelMesh policy say about external vendor
            charges?"</em> via the same Responses API call that handles file_search.
          </div>
          <pre className="mono text-[10px] leading-snug text-slate-300 bg-black/30 rounded-lg p-3 overflow-auto max-h-[420px] whitespace-pre-wrap">
            {policiesMd || "Loading…"}
          </pre>
        </Panel>

        <Panel
          title="OTel — gen_ai conventions on every Sentinel decision"
          icon={<Network size={13} />}
          accent="#22d3ee"
          className="lg:col-span-2"
          right={
            <a
              href="https://learn.microsoft.com/agent-framework/agents/observability"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-[10px] text-slate-400 hover:text-cyan-300"
            >
              Foundry tracing <ExternalLink size={11} />
            </a>
          }
        >
          <div className="text-[11px] text-slate-400 mb-3">
            <code className="bg-white/5 px-1 rounded mono text-[10px]">InstrumentedSentinelClient</code> emits one
            OTel span per inspect with the gen_ai semantic conventions, so Sentinel
            decisions show up natively in Foundry's Trace explorer next to the
            agent's <code className="bg-white/5 px-1 rounded mono text-[10px]">execute_tool</code> spans.
          </div>
          <ul className="space-y-1.5">
            {OTEL_ATTRS.map((a) => (
              <li key={a.key} className="text-[11px] flex items-start gap-2">
                <code className="mono text-[10px] bg-white/5 px-1.5 py-0.5 rounded shrink-0 text-cyan-300">
                  {a.key}
                </code>
                <span className="text-slate-400">{a.meaning}</span>
              </li>
            ))}
          </ul>
          <div className="mt-4 text-[10px] text-slate-500">
            Configure with{" "}
            <code className="bg-white/5 px-1 rounded mono">OTEL_EXPORTER_OTLP_ENDPOINT</code>{" "}
            (Foundry Hosted Agents inject this automatically).
          </div>
        </Panel>
      </div>

      {/* ---- Footer with what-runs-where ---- */}
      <Panel title="What runs where" icon={<TerminalSquare size={13} />} accent="#94a3b8">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-[11px]">
          <div>
            <div className="text-cyan-300 font-semibold mb-1 flex items-center gap-1.5">
              <Activity size={11} /> Run the comparison demo
            </div>
            <pre className="mono text-[10px] bg-black/30 rounded p-2 leading-snug">
{`python -m examples.redteam_compare \\
    --objectives-per-category 3 \\
    --out artifacts/redteam-report.json`}
            </pre>
          </div>
          <div>
            <div className="text-cyan-300 font-semibold mb-1 flex items-center gap-1.5">
              <FlaskConical size={11} /> Run the MAF agent under Sentinel
            </div>
            <pre className="mono text-[10px] bg-black/30 rounded p-2 leading-snug">
{`OPENAI_API_KEY=... \\
    python -m examples.maf_governed_agent`}
            </pre>
          </div>
        </div>
      </Panel>
    </main>
  );
}
