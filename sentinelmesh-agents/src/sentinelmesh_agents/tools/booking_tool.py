"""First-party SkyNest booking tool.

Provides a low-blast-radius alternative to ``payments.charge`` for the
common "book a hotel on SkyNest" goal. Hits the demo site's own
``POST /api/bookings`` endpoint — which is internal, idempotent, and
inventory-aware — so a successful happy-path booking flow does NOT need to
touch a real payment processor at all.

Why this matters for SentinelMesh:

* ``payments.charge`` is intentionally rated as a high-blast tool (irreversible
  external money movement) → policy raises ``REQUIRE_APPROVAL``.
* ``bookings.create`` writes a row in the first-party SkyNest backend → low
  blast, no real money, fully reversible via ``cancel_booking``. The mesh
  treats it as a normal allow-grade action, so happy-path bookings flow
  through cleanly. Risky cross-vendor charges still go through
  ``payments.charge`` and still pause for human approval.

Idempotency:

The tool always sends an ``Idempotency-Key`` header so duplicate retries
collapse to one booking — same property the website's checkout form gets.
If the caller doesn't supply one we generate a stable key from the booking
inputs (hotel + guest + dates) so even an LLM that re-emits the same step
twice doesn't double-book a room.
"""

from __future__ import annotations

import hashlib
import json
import logging
from datetime import date, timedelta
from typing import Any
from urllib.parse import urljoin

import httpx

from sentinelmesh_agents.config import get_settings
from sentinelmesh_agents.tools.registry import Tool

log = logging.getLogger(__name__)


_DEFAULT_NIGHTS = 3


def _coerce_dates(args: dict[str, Any]) -> tuple[str, str]:
    """Resolve check-in / check-out, defaulting to a sensible 3-night stay
    if the planner left them blank — common with terse goals like
    "book a hotel in Mumbai"."""
    today = date.today()
    raw_in = (args.get("check_in") or "").strip()
    raw_out = (args.get("check_out") or "").strip()
    try:
        check_in = date.fromisoformat(raw_in) if raw_in else today
    except ValueError:
        check_in = today
    try:
        check_out = (
            date.fromisoformat(raw_out)
            if raw_out
            else check_in + timedelta(days=_DEFAULT_NIGHTS)
        )
    except ValueError:
        check_out = check_in + timedelta(days=_DEFAULT_NIGHTS)
    if check_out <= check_in:
        check_out = check_in + timedelta(days=_DEFAULT_NIGHTS)
    return check_in.isoformat(), check_out.isoformat()


def _stable_key(payload: dict[str, Any]) -> str:
    """Derive a deterministic idempotency key from the booking inputs.
    Same inputs → same key → duplicate calls collapse to one booking.
    Includes adults / children so a "same hotel & dates but different party
    size" follow-up actually produces a new booking instead of being silently
    folded into the original."""
    canonical = json.dumps(
        {
            "hotel_id": payload.get("hotel_id"),
            "guest_email": payload.get("guest_email"),
            "check_in": payload.get("check_in"),
            "check_out": payload.get("check_out"),
            "adults": int(payload.get("adults", 1)),
            "children": int(payload.get("children", 0)),
        },
        sort_keys=True,
    ).encode("utf-8")
    return "agent-" + hashlib.sha256(canonical).hexdigest()[:24]


def _coerce_count(raw: Any, *, default: int, lo: int, hi: int) -> int:
    """Best-effort int coercion clamped to a sane range."""
    if raw is None:
        return default
    try:
        n = int(raw)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


async def _bookings_create(args: dict[str, Any]) -> dict[str, Any]:
    settings = get_settings()
    base = settings.demo_site_base_url.rstrip("/")
    url = urljoin(base + "/", "api/bookings")

    hotel_id = (args.get("hotel_id") or "").strip()
    if not hotel_id:
        raise ValueError("bookings.create: hotel_id is required")

    check_in, check_out = _coerce_dates(args)
    payload: dict[str, Any] = {
        "hotel_id": hotel_id,
        "guest_name": (args.get("guest_name") or "SkyNest Guest").strip()[:120],
        "guest_email": (args.get("guest_email") or "guest@skynest.example").strip()[:200],
        "check_in": check_in,
        "check_out": check_out,
        "adults": _coerce_count(args.get("adults"), default=1, lo=1, hi=12),
        "children": _coerce_count(args.get("children"), default=0, lo=0, hi=8),
        "booked_by_ai": True,
    }
    if args.get("sentinel_session_id"):
        payload["sentinel_session_id"] = str(args["sentinel_session_id"])

    headers = {
        "Idempotency-Key": str(args.get("idempotency_key") or _stable_key(payload)),
        "Content-Type": "application/json",
    }

    async with httpx.AsyncClient(timeout=10.0, follow_redirects=False) as c:
        r = await c.post(url, json=payload, headers=headers)
        body: Any
        try:
            body = r.json()
        except Exception:  # noqa: BLE001 — non-JSON response is still useful
            body = {"raw": r.text[:500]}
        log.info(
            "bookings.create %s → %d (hotel=%s, key=%s)",
            url, r.status_code, hotel_id, headers["Idempotency-Key"],
        )
        return {
            "url": url,
            "status": r.status_code,
            "ok": 200 <= r.status_code < 300,
            "booking": body if isinstance(body, dict) else {"raw": body},
            "idempotency_key": headers["Idempotency-Key"],
        }


def bookings_create_tool() -> Tool:
    return Tool(
        name="bookings.create",
        description=(
            "Create a SkyNest hotel booking. First-party, idempotent, "
            "inventory-aware — prefer this over payments.charge for any "
            "booking goal on the SkyNest demo site. Args: hotel_id (required), "
            "guest_name, guest_email, check_in (YYYY-MM-DD), check_out (YYYY-MM-DD)."
        ),
        args_schema={
            "hotel_id": "string",
            "guest_name": "string?",
            "guest_email": "string?",
            "check_in": "string? (YYYY-MM-DD, default: today)",
            "check_out": "string? (YYYY-MM-DD, default: check_in + 3 days)",
            "adults": "int? (1-12, default: 1)",
            "children": "int? (0-8, default: 0)",
        },
        fn=_bookings_create,
    )
