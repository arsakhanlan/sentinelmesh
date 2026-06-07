"use client";

import clsx, { type ClassValue } from "clsx";
import { motion } from "framer-motion";
import type { ReactNode } from "react";

export function cn(...inputs: ClassValue[]) {
  return clsx(inputs);
}

export function Panel({
  title,
  icon,
  accent,
  right,
  className,
  bodyClassName,
  children,
}: {
  title: string;
  icon?: ReactNode;
  accent?: string;
  right?: ReactNode;
  className?: string;
  bodyClassName?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn("glass rounded-xl flex flex-col overflow-hidden", className)}>
      <header className="flex items-center gap-2 px-4 py-2.5 border-b border-white/5 shrink-0">
        <span style={{ color: accent ?? "#22d3ee" }} className="flex items-center">
          {icon}
        </span>
        <h2 className="text-[11px] font-semibold tracking-[0.18em] uppercase text-slate-300">
          {title}
        </h2>
        <span
          className="ml-2 h-1 w-1 rounded-full"
          style={{ background: accent ?? "#22d3ee", boxShadow: `0 0 8px ${accent ?? "#22d3ee"}` }}
        />
        <div className="ml-auto">{right}</div>
      </header>
      <div className={cn("flex-1 min-h-0 p-3", bodyClassName)}>{children}</div>
    </section>
  );
}

export function Chip({
  label,
  value,
  color,
}: {
  label: string;
  value: ReactNode;
  color?: string;
}) {
  return (
    <div className="flex flex-col items-start rounded-lg bg-white/[0.03] px-3 py-1.5 border border-white/5 min-w-[78px]">
      <span className="text-[9px] uppercase tracking-widest text-slate-500">{label}</span>
      <span className="mono text-sm font-semibold" style={{ color: color ?? "#e2e8f0" }}>
        {value}
      </span>
    </div>
  );
}

export function Dot({ color, pulse }: { color: string; pulse?: boolean }) {
  return (
    <motion.span
      className="inline-block h-2 w-2 rounded-full"
      style={{ background: color, boxShadow: `0 0 8px ${color}` }}
      animate={pulse ? { opacity: [1, 0.3, 1] } : {}}
      transition={pulse ? { repeat: Infinity, duration: 1.4 } : {}}
    />
  );
}
