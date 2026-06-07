"use client";

import { useEffect, useState } from "react";
import { Building2 } from "lucide-react";
import { api } from "@/lib/api";
import type { TenantSummary } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { useFirehose } from "@/lib/useFirehose";

export default function TenantsPage() {
  const { status } = useFirehose();
  const [rows, setRows] = useState<TenantSummary[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api
      .tenantsSummary()
      .then(setRows)
      .catch((e) => setErr(String(e)));
    const t = setInterval(() => {
      api.tenantsSummary().then(setRows).catch(() => {});
    }, 6000);
    return () => clearInterval(t);
  }, []);

  return (
    <main className="mx-auto max-w-5xl space-y-4 p-4">
      <TopBar status={status} />
      <div className="flex items-center gap-2">
        <Building2 className="text-agent" size={22} />
        <div>
          <h1 className="text-lg font-bold text-slate-100">Tenants</h1>
          <p className="text-xs text-slate-500">
            Rolling 24h tool usage vs org daily caps, spend vs tenant cap, sessions started today (UTC).
          </p>
        </div>
      </div>

      {err && (
        <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">{err}</div>
      )}

      {!rows && !err && <p className="text-xs text-slate-500">Loading…</p>}

      {rows && (
        <div className="overflow-x-auto rounded-xl border border-white/10">
          <table className="w-full text-left text-xs">
            <thead className="bg-white/5 text-[10px] uppercase tracking-wider text-slate-400">
              <tr>
                <th className="px-3 py-2">Tenant</th>
                <th className="px-3 py-2">Sessions today</th>
                <th className="px-3 py-2">Threats</th>
                <th className="px-3 py-2">Spend 24h</th>
                <th className="px-3 py-2">Tool bars (24h / cap)</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.tenantId} className="border-t border-white/5">
                  <td className="px-3 py-2 font-semibold text-slate-200">{r.name}</td>
                  <td className="mono px-3 py-2 text-slate-300">{r.sessionsToday}</td>
                  <td className="mono px-3 py-2 text-slate-300">{r.threatsTotal}</td>
                  <td className="px-3 py-2 text-slate-300">
                    <span className="mono">{r.spendUsed24hInr}</span>
                    <span className="text-slate-500"> / </span>
                    <span className="mono">{r.spendCap24hInr}</span>
                    <span className="ml-2 text-[10px] text-slate-500">({r.spendPct}%)</span>
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-2">
                      {Object.entries(r.tools).map(([tool, u]) => (
                        <div key={tool} className="min-w-[120px]">
                          <div className="flex justify-between text-[10px] text-slate-500">
                            <span className="truncate">{tool}</span>
                            <span className="mono">
                              {u.used24h}/{u.cap24h}
                            </span>
                          </div>
                          <div className="mt-0.5 h-1.5 overflow-hidden rounded bg-white/10">
                            <div
                              className="h-full rounded bg-agent"
                              style={{ width: `${Math.min(100, u.pct)}%` }}
                            />
                          </div>
                        </div>
                      ))}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}
