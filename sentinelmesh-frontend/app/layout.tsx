import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SentinelMesh — Agent Security Operations",
  description:
    "Runtime security & governance mesh for autonomous AI agents. Live threat detection, policy enforcement, and human-in-the-loop approvals.",
};

export const viewport: Viewport = {
  themeColor: "#070a10",
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-grid min-h-screen antialiased">
        <div className="scanline" />
        <div className="relative z-10">{children}</div>
      </body>
    </html>
  );
}
