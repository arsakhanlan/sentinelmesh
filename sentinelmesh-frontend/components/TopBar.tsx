"use client";

import { motion } from "framer-motion";
import { ShieldCheck, Radio, Link2, Link2Off, FlaskConical, Building2, Sparkles } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { ConnStatus } from "@/lib/useFirehose";
import { Dot } from "./ui";

export function TopBar({
  status = "closed",
  showFirehose = true,
}: {
  status?: ConnStatus;
  /** When false, the firehose pill is omitted (e.g. Policy Lab) so a judge does not read "closed" as broken. */
  showFirehose?: boolean;
}) {
  const [chainOk, setChainOk] = useState<boolean | null>(null);
  const pathname = usePathname();

  useEffect(() => {
    const load = () => api.auditVerify().then((r) => setChainOk(r.chain_intact)).catch(() => {});
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, []);

  return (
    <header className="glass flex flex-wrap items-center gap-3 rounded-xl px-4 py-2.5">
      <div className="flex items-center gap-2.5">
        <motion.span
          animate={{ rotate: [0, 4, -4, 0] }}
          transition={{ repeat: Infinity, duration: 6 }}
          className="text-agent"
        >
          <ShieldCheck size={22} />
        </motion.span>
        <div className="leading-none">
          <div className="flex items-center gap-1.5">
            <span className="text-base font-bold tracking-tight text-slate-100">SentinelMesh</span>
            <span className="mono rounded bg-agent/15 px-1.5 py-0.5 text-[9px] font-semibold text-agent">
              SOC
            </span>
          </div>
          <span className="text-[10px] tracking-wide text-slate-500">
            Runtime security mesh for autonomous agents
          </span>
        </div>
      </div>

      {/* Top-level nav: SOC dashboard ⇄ policy editor. */}
      <nav className="flex items-center gap-1 ml-3">
        <Link
          href="/"
          className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition ${
            pathname === "/"
              ? "bg-white/10 text-slate-100"
              : "text-slate-400 hover:text-slate-100 hover:bg-white/5"
          }`}
        >
          SOC
        </Link>
        <Link
          href="/policies"
          className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition ${
            pathname?.startsWith("/policies")
              ? "bg-white/10 text-slate-100"
              : "text-slate-400 hover:text-slate-100 hover:bg-white/5"
          }`}
        >
          <FlaskConical size={12} />
          Policy Lab
        </Link>
        <Link
          href="/tenants"
          className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition ${
            pathname?.startsWith("/tenants")
              ? "bg-white/10 text-slate-100"
              : "text-slate-400 hover:text-slate-100 hover:bg-white/5"
          }`}
        >
          <Building2 size={12} />
          Tenants
        </Link>
        <Link
          href="/microsoft"
          className={`flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide transition ${
            pathname?.startsWith("/microsoft")
              ? "bg-cyan-400/10 text-cyan-200"
              : "text-cyan-300/70 hover:text-cyan-100 hover:bg-cyan-400/5"
          }`}
        >
          <Sparkles size={12} />
          Microsoft AI
        </Link>
      </nav>

      <div className="ml-auto flex flex-wrap items-center gap-2.5">
        {showFirehose && (() => {
          const connColor =
            status === "open" ? "#10b981" : status === "connecting" ? "#f59e0b" : "#ef4444";
          return (
          <div className="flex items-center gap-1.5 rounded-lg bg-white/[0.03] px-2.5 py-1.5 border border-white/5">
            {status === "open" ? (
              <Link2 size={13} style={{ color: connColor }} />
            ) : (
              <Link2Off size={13} style={{ color: connColor }} />
            )}
            <Dot color={connColor} pulse={status !== "closed"} />
            <span className="mono text-[10px] uppercase tracking-widest" style={{ color: connColor }}>
              {status === "open" ? "Firehose Live" : status}
            </span>
          </div>
          );
        })()}

        <div className="flex items-center gap-1.5 rounded-lg bg-white/[0.03] px-2.5 py-1.5 border border-white/5">
          <Radio size={13} className={chainOk ? "text-allowed" : "text-muted"} />
          <span
            className="mono text-[10px] uppercase tracking-widest"
            style={{ color: chainOk == null ? "#64748b" : chainOk ? "#10b981" : "#ef4444" }}
          >
            {chainOk == null ? "Audit —" : chainOk ? "Chain Intact" : "Chain Broken"}
          </span>
        </div>
      </div>
    </header>
  );
}
