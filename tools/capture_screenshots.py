"""Capture screenshots of the running SentinelMesh stack for the submission deck.

Run from the host with the full docker-compose stack up. Uses the host-mapped
ports so the frontend's baked-in localhost URLs work for client-side fetches.
"""
from __future__ import annotations

import os
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import Page, sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
DEMO_URL = os.environ.get("DEMO_URL", "http://localhost:9000")
API_KEY = os.environ.get("API_KEY", "dev-api-key-change-me")

OUT_DIR = Path(os.environ.get("SHOT_DIR", "docs/deck-screenshots"))
OUT_DIR.mkdir(parents=True, exist_ok=True)


def fire_scenarios_live(stop_event: threading.Event) -> None:
    """Drip a few adversary scenarios so the SOC firehose has fresh data."""
    scenarios = ["hidden_prompt_injection", "credential_theft", "unauthorized_payment",
                 "capability_escalation", "phishing_email"]
    idx = 0
    while not stop_event.is_set():
        sid = scenarios[idx % len(scenarios)]
        idx += 1
        body = b'{"scenarioId":"' + sid.encode() + b'"}'
        req = urllib.request.Request(
            f"{BACKEND_URL}/api/v1/adversary/fire", data=body,
            headers={"Content-Type": "application/json", "X-API-Key": API_KEY},
            method="POST")
        try:
            urllib.request.urlopen(req, timeout=4).read()
        except (urllib.error.URLError, TimeoutError):
            pass
        if stop_event.wait(0.7):
            return


def grab(page: Page, url: str, settle_ms: int, out: Path,
         with_scenarios: bool = False) -> None:
    print(f"-> {url}", flush=True)
    page.goto(url, wait_until="domcontentloaded", timeout=20_000)
    if with_scenarios:
        stop = threading.Event()
        t = threading.Thread(target=fire_scenarios_live, args=(stop,), daemon=True)
        t.start()
        page.wait_for_timeout(settle_ms)
        stop.set()
        t.join(timeout=2)
    else:
        page.wait_for_timeout(settle_ms)
    page.screenshot(path=str(out), full_page=False)
    print(f"   wrote {out} ({out.stat().st_size} bytes)", flush=True)


TARGETS = [
    # SOC layout with all panels visible (no events firing yet).
    ("00_soc_layout.png", f"{FRONTEND_URL}/", 3500, False),
    # SOC dashboard, with live adversary scenarios firing — captures the timeline.
    ("01_soc_live_theater.png", f"{FRONTEND_URL}/", 7000, True),
    # Policy Lab — YAML editor + simulator.
    ("02_policy_lab.png", f"{FRONTEND_URL}/policies", 4500, False),
    # Microsoft AI integration page — code snippets + ASR numbers.
    ("03_microsoft.png", f"{FRONTEND_URL}/microsoft", 4500, False),
    # Tenant utilization page.
    ("04_tenants.png", f"{FRONTEND_URL}/tenants", 4500, False),
    # SkyNest landing.
    ("05_skynest_home.png", f"{DEMO_URL}/", 2500, False),
    # SkyNest hotels listing.
    ("06_skynest_hotels.png", f"{DEMO_URL}/hotels", 3000, False),
]


def capture() -> None:
    failed: list[tuple[str, str]] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
        for filename, url, settle_ms, with_scen in TARGETS:
            try:
                ctx = browser.new_context(viewport={"width": 1600, "height": 900})
                page = ctx.new_page()
                grab(page, url, settle_ms, OUT_DIR / filename, with_scen)
                ctx.close()
            except Exception as ex:
                print(f"   FAILED ({filename}): {ex}", flush=True)
                failed.append((filename, str(ex)))
        browser.close()
    if failed:
        print(f"\n{len(failed)} target(s) failed:", file=sys.stderr)
        for name, err in failed:
            print(f"  - {name}: {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    capture()
