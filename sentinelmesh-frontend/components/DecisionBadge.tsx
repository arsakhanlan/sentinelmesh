"use client";

import { DECISION_COLOR } from "@/lib/config";
import { cn } from "./ui";

export function DecisionBadge({ decision, size = "sm" }: { decision?: string; size?: "sm" | "md" }) {
  if (!decision) return null;
  const color = DECISION_COLOR[decision] ?? "#64748b";
  return (
    <span
      className={cn(
        "mono inline-flex items-center rounded-md font-semibold uppercase tracking-wider",
        size === "sm" ? "px-1.5 py-0.5 text-[10px]" : "px-2.5 py-1 text-xs"
      )}
      style={{
        color,
        background: `${color}1a`,
        border: `1px solid ${color}40`,
      }}
    >
      {decision.replace(/_/g, " ")}
    </span>
  );
}
