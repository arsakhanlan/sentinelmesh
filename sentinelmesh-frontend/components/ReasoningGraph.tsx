"use client";

import { AnimatePresence, motion } from "framer-motion";
import { useMemo } from "react";
import type { AgentEvent } from "@/lib/types";
import { DECISION_COLOR } from "@/lib/config";

type NodeId = "user" | "planner" | "executor" | "sentinel" | "tool" | "threat" | "approval";

interface NodeDef {
  id: NodeId;
  label: string;
  x: number;
  y: number;
}

const NODES: NodeDef[] = [
  { id: "user", label: "GOAL", x: 60, y: 130 },
  { id: "planner", label: "PLANNER", x: 175, y: 130 },
  { id: "executor", label: "EXECUTOR", x: 300, y: 130 },
  { id: "sentinel", label: "SENTINEL", x: 430, y: 130 },
  { id: "tool", label: "TOOL", x: 560, y: 130 },
  { id: "threat", label: "THREAT", x: 430, y: 40 },
  { id: "approval", label: "APPROVAL", x: 430, y: 220 },
];

const EDGES: [NodeId, NodeId][] = [
  ["user", "planner"],
  ["planner", "executor"],
  ["executor", "sentinel"],
  ["sentinel", "tool"],
  ["sentinel", "threat"],
  ["sentinel", "approval"],
];

function nodeFor(ev?: AgentEvent): NodeId | null {
  if (!ev) return null;
  switch (ev.kind) {
    case "plan":
      return "planner";
    case "tool_call":
      return "executor";
    case "sentinel_decision":
      return "sentinel";
    case "tool_result":
      return "tool";
    case "threat":
      return "threat";
    case "approval_requested":
    case "approval_decided":
      return "approval";
    default:
      return null;
  }
}

export function ReasoningGraph({ events }: { events: AgentEvent[] }) {
  const last = events[events.length - 1];
  const active = nodeFor(last);
  const lastDecision = useMemo(
    () => [...events].reverse().find((e) => e.kind === "sentinel_decision")?.decision,
    [events]
  );

  const pos = (id: NodeId) => NODES.find((n) => n.id === id)!;

  return (
    <div className="h-full w-full">
      <svg viewBox="0 0 620 260" className="h-full w-full" preserveAspectRatio="xMidYMid meet">
        <defs>
          <marker id="arrow" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
            <path d="M0,0 L6,3 L0,6 Z" fill="rgba(148,163,184,0.5)" />
          </marker>
        </defs>

        {EDGES.map(([a, b], i) => {
          const pa = pos(a);
          const pb = pos(b);
          const isLive =
            (active === b && nodeFor(last) === b) ||
            (a === "sentinel" && b === "tool" && active === "tool");
          const edgeColor =
            b === "threat" ? "#ef4444" : b === "approval" ? "#f59e0b" : "#22d3ee";
          return (
            <g key={i}>
              <line
                x1={pa.x}
                y1={pa.y}
                x2={pb.x}
                y2={pb.y}
                stroke="rgba(148,163,184,0.2)"
                strokeWidth={1.5}
                markerEnd="url(#arrow)"
              />
              <AnimatePresence>
                {isLive && (
                  <motion.line
                    x1={pa.x}
                    y1={pa.y}
                    x2={pb.x}
                    y2={pb.y}
                    stroke={edgeColor}
                    strokeWidth={2.5}
                    initial={{ pathLength: 0, opacity: 0.9 }}
                    animate={{ pathLength: 1, opacity: 1 }}
                    exit={{ opacity: 0 }}
                    transition={{ duration: 0.5 }}
                    style={{ filter: `drop-shadow(0 0 4px ${edgeColor})` }}
                  />
                )}
              </AnimatePresence>
            </g>
          );
        })}

        {NODES.map((n) => {
          const isActive = active === n.id;
          const isThreat = n.id === "threat";
          const isApproval = n.id === "approval";
          let color = "#22d3ee";
          if (isThreat) color = "#ef4444";
          else if (isApproval) color = "#f59e0b";
          if (n.id === "sentinel" && lastDecision) color = DECISION_COLOR[lastDecision] ?? color;

          return (
            <g key={n.id}>
              <motion.circle
                cx={n.x}
                cy={n.y}
                r={isActive ? 26 : 22}
                fill={isActive ? `${color}26` : "rgba(16,21,31,0.9)"}
                stroke={color}
                strokeWidth={isActive ? 2.5 : 1.3}
                animate={
                  isActive
                    ? { scale: [1, 1.08, 1], opacity: 1 }
                    : { scale: 1, opacity: 0.7 }
                }
                transition={{ duration: 0.6, repeat: isActive ? Infinity : 0 }}
                style={{ filter: isActive ? `drop-shadow(0 0 8px ${color})` : "none" }}
              />
              <text
                x={n.x}
                y={n.y + 1}
                textAnchor="middle"
                dominantBaseline="middle"
                className="mono"
                fontSize="8.5"
                fontWeight={700}
                fill={isActive ? "#fff" : "#94a3b8"}
              >
                {n.label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
