import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
    "./lib/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        ink: {
          900: "#070a10",
          800: "#0a0e16",
          700: "#10151f",
          600: "#161c28",
          500: "#1e2533",
        },
        agent: "#22d3ee",     // cyan
        approve: "#f59e0b",   // amber
        threat: "#ef4444",    // red
        allowed: "#10b981",   // emerald
        muted: "#64748b",
      },
      fontFamily: {
        mono: ["var(--font-mono)", "ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
        sans: ["var(--font-sans)", "ui-sans-serif", "system-ui", "sans-serif"],
      },
      boxShadow: {
        glow: "0 0 0 1px rgba(34,211,238,0.15), 0 0 24px -6px rgba(34,211,238,0.35)",
        "glow-red": "0 0 0 1px rgba(239,68,68,0.25), 0 0 40px -6px rgba(239,68,68,0.55)",
        "glow-amber": "0 0 0 1px rgba(245,158,11,0.25), 0 0 30px -6px rgba(245,158,11,0.45)",
      },
      keyframes: {
        pulseRing: {
          "0%": { boxShadow: "0 0 0 0 rgba(239,68,68,0.55)" },
          "70%": { boxShadow: "0 0 0 14px rgba(239,68,68,0)" },
          "100%": { boxShadow: "0 0 0 0 rgba(239,68,68,0)" },
        },
        scan: {
          "0%": { transform: "translateY(-100%)" },
          "100%": { transform: "translateY(100%)" },
        },
        flicker: {
          "0%,100%": { opacity: "1" },
          "50%": { opacity: "0.85" },
        },
      },
      animation: {
        pulseRing: "pulseRing 1.6s ease-out infinite",
        scan: "scan 6s linear infinite",
        flicker: "flicker 3s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};

export default config;
