"use client";

import { motion } from "framer-motion";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { MetricsSummary } from "@/lib/types";
import { Chip } from "./ui";

function useCountUp(value: number) {
  const [display, setDisplay] = useState(value);
  useEffect(() => {
    let raf = 0;
    const start = display;
    const diff = value - start;
    if (diff === 0) return;
    const t0 = performance.now();
    const dur = 500;
    const tick = (t: number) => {
      const p = Math.min(1, (t - t0) / dur);
      setDisplay(Math.round(start + diff * (1 - Math.pow(1 - p, 3))));
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  return display;
}

export function MetricsBar({ refreshSignal }: { refreshSignal: number }) {
  const [m, setM] = useState<MetricsSummary | null>(null);

  const load = useCallback(async () => {
    try {
      setM(await api.metrics());
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    if (refreshSignal > 0) {
      const t = setTimeout(load, 600);
      return () => clearTimeout(t);
    }
  }, [refreshSignal, load]);

  const threats = useCountUp(m?.threats_total ?? 0);
  const rules = useCountUp(m?.policy_rules_loaded ?? 0);
  const p50 = m?.detect_latency_ms?.p50;
  const p95 = m?.detect_latency_ms?.p95;
  const categories = Object.keys(m?.threats_by_category ?? {}).length;

  return (
    <div className="flex flex-wrap items-stretch gap-2">
      <Chip label="Threats" value={threats} color="#ef4444" />
      <Chip label="Categories" value={categories} color="#fb923c" />
      <Chip label="Policies" value={rules} color="#a78bfa" />
      <Chip label="p50 ms" value={p50 != null ? p50.toFixed(1) : "—"} color="#22d3ee" />
      <Chip label="p95 ms" value={p95 != null ? p95.toFixed(1) : "—"} color="#22d3ee" />
      <motion.div
        key={threats}
        initial={{ opacity: 0.4 }}
        animate={{ opacity: 1 }}
        className="hidden items-center text-[10px] text-slate-600 sm:flex"
      >
        <span className="mono">live · {m?.detect_latency_ms?.samples ?? 0} samples</span>
      </motion.div>
    </div>
  );
}
