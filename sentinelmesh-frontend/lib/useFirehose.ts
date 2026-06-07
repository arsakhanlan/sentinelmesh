"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CONFIG } from "./config";
import type { AgentEvent } from "./types";

export type ConnStatus = "connecting" | "open" | "closed";

const MAX_EVENTS = 400;

/**
 * Subscribes to the authenticated SentinelMesh firehose
 * (`ws://host/ws/events?token=KEY`) with auto-reconnect + backoff.
 *
 * Demo note: query-string tokens show up in access logs and browser history.
 * Production should use a short-lived WS ticket (see frontend README).
 */
export function useFirehose() {
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [status, setStatus] = useState<ConnStatus>("connecting");
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(0);
  const closedByUs = useRef(false);

  const connect = useCallback(() => {
    const base = `${CONFIG.wsUrl}/ws/events`;
    const qs = CONFIG.apiKey ? `?token=${encodeURIComponent(CONFIG.apiKey)}` : "";
    const url = base + qs;
    setStatus("connecting");
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      scheduleReconnect();
      return;
    }
    wsRef.current = ws;

    ws.onopen = () => {
      retryRef.current = 0;
      setStatus("open");
    };

    ws.onmessage = (msg) => {
      try {
        const data = JSON.parse(msg.data) as AgentEvent;
        if (!data || !data.kind) return;
        setEvents((prev) => {
          const next = [...prev, data];
          return next.length > MAX_EVENTS ? next.slice(next.length - MAX_EVENTS) : next;
        });
      } catch {
        /* ignore malformed frames */
      }
    };

    ws.onclose = () => {
      setStatus("closed");
      if (!closedByUs.current) scheduleReconnect();
    };

    ws.onerror = () => {
      try { ws.close(); } catch { /* noop */ }
    };
  }, []);

  const scheduleReconnect = useCallback(() => {
    const attempt = retryRef.current++;
    const delay = Math.min(1000 * 2 ** attempt, 8000);
    setTimeout(() => {
      if (!closedByUs.current) connect();
    }, delay);
  }, [connect]);

  useEffect(() => {
    closedByUs.current = false;
    connect();
    return () => {
      closedByUs.current = true;
      try { wsRef.current?.close(); } catch { /* noop */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const clear = useCallback(() => setEvents([]), []);

  return { events, status, clear };
}
