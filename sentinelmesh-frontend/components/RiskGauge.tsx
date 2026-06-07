"use client";

import { motion } from "framer-motion";

function riskColor(v: number) {
  if (v >= 0.8) return "#ef4444";
  if (v >= 0.6) return "#fb923c";
  if (v >= 0.4) return "#f59e0b";
  if (v >= 0.2) return "#22d3ee";
  return "#10b981";
}

function riskLabel(v: number) {
  if (v >= 0.8) return "CRITICAL";
  if (v >= 0.6) return "HIGH";
  if (v >= 0.4) return "ELEVATED";
  if (v >= 0.2) return "GUARDED";
  return "NOMINAL";
}

/** Radial threat gauge. `value` is composite risk 0..1. */
export function RiskGauge({ value }: { value: number }) {
  const v = Math.max(0, Math.min(1, value || 0));
  const size = 132;
  const stroke = 10;
  const r = (size - stroke) / 2;
  const circ = 2 * Math.PI * r;
  // 270° sweep gauge
  const sweep = 0.75;
  const dash = circ * sweep;
  const color = riskColor(v);

  return (
    <div className="relative flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-[225deg]">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke="rgba(148,163,184,0.12)"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circ}`}
        />
        <motion.circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${dash * v} ${circ}`}
          initial={false}
          animate={{ strokeDasharray: `${dash * v} ${circ}` }}
          transition={{ type: "spring", stiffness: 90, damping: 18 }}
          style={{ filter: `drop-shadow(0 0 6px ${color})` }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <motion.span
          key={Math.round(v * 100)}
          initial={{ scale: 0.8, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          className="mono text-3xl font-bold"
          style={{ color }}
        >
          {Math.round(v * 100)}
        </motion.span>
        <span className="text-[9px] tracking-[0.22em] text-slate-500">RISK INDEX</span>
        <span className="mono mt-0.5 text-[10px] font-semibold tracking-widest" style={{ color }}>
          {riskLabel(v)}
        </span>
      </div>
    </div>
  );
}
