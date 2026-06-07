"use client";

import { AnimatePresence, motion } from "framer-motion";
import { useEffect, useState } from "react";

/**
 * Full-viewport red alert flash. Fires whenever `trigger` changes
 * (page increments it on CRITICAL threats / BLOCK / QUARANTINE).
 */
export function ScreenFx({ trigger, label }: { trigger: number; label?: string }) {
  const [show, setShow] = useState(false);

  useEffect(() => {
    if (trigger <= 0) return;
    setShow(true);
    const t = setTimeout(() => setShow(false), 1100);
    return () => clearTimeout(t);
  }, [trigger]);

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          key={trigger}
          className="pointer-events-none fixed inset-0 z-50 flex items-start justify-center"
          initial={{ opacity: 0 }}
          animate={{ opacity: [0, 1, 0.6, 0] }}
          exit={{ opacity: 0 }}
          transition={{ duration: 1.1, times: [0, 0.15, 0.5, 1] }}
        >
          <div
            className="absolute inset-0"
            style={{
              boxShadow: "inset 0 0 200px 40px rgba(239,68,68,0.55)",
              background: "radial-gradient(circle at 50% 0%, rgba(239,68,68,0.18), transparent 60%)",
            }}
          />
          {label && (
            <motion.div
              initial={{ y: -30, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              className="mono mt-6 rounded-md border border-threat/50 bg-black/70 px-4 py-1.5 text-sm font-bold uppercase tracking-[0.3em] text-threat"
              style={{ textShadow: "0 0 12px rgba(239,68,68,0.8)" }}
            >
              {label}
            </motion.div>
          )}
        </motion.div>
      )}
    </AnimatePresence>
  );
}
