"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  Activity,
  Crosshair,
  FileBadge,
  GitBranch,
  Gauge,
  ShieldAlert,
  Siren,
  Trash2,
} from "lucide-react";
import { useFirehose } from "@/lib/useFirehose";
import { TopBar } from "@/components/TopBar";
import { MetricsBar } from "@/components/MetricsBar";
import { AdversaryConsole } from "@/components/AdversaryConsole";
import { LiveTheater } from "@/components/LiveTheater";
import { ReasoningGraph } from "@/components/ReasoningGraph";
import { ThreatFeed } from "@/components/ThreatFeed";
import { ApprovalCenter } from "@/components/ApprovalCenter";
import { RiskGauge } from "@/components/RiskGauge";
import { ScreenFx } from "@/components/ScreenFx";
import { SessionDrawer } from "@/components/SessionDrawer";
import { SessionPicker } from "@/components/SessionPicker";
import { Panel } from "@/components/ui";

function useIsDesktop() {
  const [desktop, setDesktop] = useState(true);
  useEffect(() => {
    const mq = window.matchMedia("(min-width: 1024px)");
    const on = () => setDesktop(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return desktop;
}

type Tab = "theater" | "threats" | "approvals" | "console";

export default function Home() {
  const { events, status, clear } = useFirehose();
  const desktop = useIsDesktop();
  const [tab, setTab] = useState<Tab>("theater");
  const [drawerSessionId, setDrawerSessionId] = useState<string | null>(null);

  // Deep-link support: ?session=<uuid> opens the forensics drawer directly.
  // This is what the SkyNest concierge links to so a user can jump straight
  // from "I booked something" to "here's the security replay of that booking".
  useEffect(() => {
    if (typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    const sid = params.get("session");
    if (sid) setDrawerSessionId(sid);
  }, []);

  const [refreshSignal, setRefreshSignal] = useState(0);
  const [shock, setShock] = useState(0);
  const [shockLabel, setShockLabel] = useState<string | undefined>();
  const seen = useRef(0);

  const latestRisk = useMemo(() => {
    const d = [...events].reverse().find((e) => e.kind === "sentinel_decision");
    return d?.risk?.composite ?? 0;
  }, [events]);

  // React to newly-arrived events: refresh side panels + trigger alert FX.
  useEffect(() => {
    if (events.length <= seen.current) {
      seen.current = events.length;
      return;
    }
    const fresh = events.slice(seen.current);
    seen.current = events.length;
    let bump = false;
    for (const e of fresh) {
      if (e.kind === "approval_requested" || e.kind === "approval_decided" || e.kind === "sentinel_decision") {
        bump = true;
      }
      if (e.kind === "threat" && (e.severity === "CRITICAL" || e.severity === "HIGH")) {
        setShock((s) => s + 1);
        setShockLabel(`${e.severity} THREAT · ${e.category ?? ""}`);
      }
      if (e.kind === "sentinel_decision" && (e.decision === "BLOCK" || e.decision === "QUARANTINE")) {
        setShock((s) => s + 1);
        setShockLabel(`${e.decision} ENFORCED`);
      }
    }
    if (bump) setRefreshSignal((s) => s + 1);
  }, [events]);

  const pendingApprovals = useMemo(() => {
    const req = new Set<string>();
    for (const e of events) {
      if (e.kind === "approval_requested" && e.approvalId) req.add(e.approvalId);
      if (e.kind === "approval_decided" && e.approvalId) req.delete(e.approvalId);
    }
    return req.size;
  }, [events]);

  const theater = (
    <Panel
      title="Live Agent Theater"
      icon={<Activity size={13} />}
      className="min-h-0"
      bodyClassName="p-2"
      right={
        <button
          onClick={clear}
          className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] text-slate-500 hover:text-slate-300"
        >
          <Trash2 size={11} /> clear
        </button>
      }
    >
      <LiveTheater events={events} onOpenSession={setDrawerSessionId} />
    </Panel>
  );

  const sessions = (
    <Panel
      title="Sessions"
      icon={<FileBadge size={13} />}
      className="min-h-0"
      bodyClassName="p-2"
    >
      <SessionPicker events={events} selected={drawerSessionId} onSelect={setDrawerSessionId} />
    </Panel>
  );

  const graph = (
    <Panel title="Reasoning Pipeline" icon={<GitBranch size={13} />} className="shrink-0" bodyClassName="p-1 h-[180px]">
      <ReasoningGraph events={events} />
    </Panel>
  );

  const threats = (
    <Panel title="Threat Feed" icon={<Siren size={13} />} accent="#ef4444" className="min-h-0">
      <ThreatFeed events={events} />
    </Panel>
  );

  const approvals = (
    <Panel
      title="Approval Center"
      icon={<ShieldAlert size={13} />}
      accent="#f59e0b"
      className="min-h-0"
      right={
        pendingApprovals > 0 ? (
          <span className="mono rounded-full bg-approve/20 px-2 py-0.5 text-[10px] font-bold text-approve">
            {pendingApprovals}
          </span>
        ) : null
      }
    >
      <ApprovalCenter refreshSignal={refreshSignal} />
    </Panel>
  );

  const console = (
    <Panel title="Adversary Console" icon={<Crosshair size={13} />} accent="#ef4444" className="min-h-0">
      <AdversaryConsole />
    </Panel>
  );

  const gauge = (
    <Panel title="Risk Index" icon={<Gauge size={13} />} className="shrink-0" bodyClassName="flex items-center justify-center py-3">
      <RiskGauge value={latestRisk} />
    </Panel>
  );

  return (
    <main className="mx-auto flex min-h-screen max-w-[1700px] flex-col gap-3 p-3 sm:p-4">
      <ScreenFx trigger={shock} label={shockLabel} />
      <TopBar status={status} />

      <div className="glass flex items-center gap-3 overflow-x-auto rounded-xl px-4 py-2">
        <MetricsBar refreshSignal={refreshSignal} />
      </div>

      {desktop ? (
        <div className="grid min-h-0 flex-1 grid-cols-12 gap-3">
          <div className="col-span-3 flex min-h-0 flex-col gap-3">
            {console}
            {sessions}
            {gauge}
          </div>
          <div className="col-span-6 flex min-h-0 flex-col gap-3">
            {graph}
            {theater}
          </div>
          <div className="col-span-3 flex min-h-0 flex-col gap-3">
            {threats}
            {approvals}
          </div>
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col gap-3">
          <div className="grid grid-cols-2 gap-3">
            {gauge}
            {graph}
          </div>

          <div className="glass sticky top-2 z-20 grid grid-cols-4 gap-1 rounded-xl p-1">
            {([
              ["theater", "Theater", Activity],
              ["threats", "Threats", Siren],
              ["approvals", "Approvals", ShieldAlert],
              ["console", "Attack", Crosshair],
            ] as const).map(([id, label, Icon]) => (
              <button
                key={id}
                onClick={() => setTab(id)}
                className={`flex flex-col items-center gap-0.5 rounded-lg py-1.5 text-[10px] font-semibold uppercase tracking-wider transition ${
                  tab === id ? "bg-agent/15 text-agent" : "text-slate-500"
                }`}
              >
                <Icon size={15} />
                {label}
                {id === "approvals" && pendingApprovals > 0 && (
                  <span className="mono absolute -mt-1 ml-7 rounded-full bg-approve px-1 text-[9px] text-black">
                    {pendingApprovals}
                  </span>
                )}
              </button>
            ))}
          </div>

          <div className="flex min-h-[60vh] flex-1 flex-col">
            {tab === "theater" && theater}
            {tab === "threats" && threats}
            {tab === "approvals" && approvals}
            {tab === "console" && console}
          </div>
        </div>
      )}

      <SessionDrawer sessionId={drawerSessionId} onClose={() => setDrawerSessionId(null)} />
    </main>
  );
}
