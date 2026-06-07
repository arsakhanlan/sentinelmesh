"""HTTP API for the SkyNest booking backend.

Exposes a small, RESTful surface that the website (form submit), the AI
concierge (agent tool call), and external clients all share. Status codes
are chosen to be meaningful for client retry logic:

* ``201 Created`` — first successful creation.
* ``200 OK``      — idempotent replay of an already-created booking.
* ``404``         — missing hotel or booking.
* ``409``         — conflict: out of inventory, idempotency-key mismatch,
                    or illegal state transition. Clients should NOT retry.
* ``400``         — malformed input (bad date range, missing field, etc.).
* ``429``         — rate-limited (mirrors the concierge guard).

Every booking-creating request **must** include an ``Idempotency-Key``
header. The booking site auto-generates one in the browser; the agent
includes its session id + step counter. This is the single property that
makes retries safe — without it, network blips would double-book rooms.
"""

from __future__ import annotations

import logging
import re
import uuid
from typing import Any

from fastapi import APIRouter, Body, Header, HTTPException, Query
from fastapi.responses import JSONResponse

from sentinelmesh_agents.demo_site.booking_service import (
    BookingError, BookingNotFound, BookingRequest, BookingService, HotelNotFound,
    IdempotencyMismatch, IllegalTransition, NoInventory,
)

log = logging.getLogger("skynest.api")

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def build_router(service: BookingService) -> APIRouter:
    """Construct the API router. Factored out as a function so tests and the
    main app can each create their own router bound to their own service."""
    router = APIRouter(prefix="/api", tags=["bookings"])

    # ----- listings ------------------------------------------------- #

    @router.get("/hotels")
    async def list_hotels(
        city: str | None = Query(None),
        max_price: int | None = Query(None, ge=0),
        min_rating: float | None = Query(None, ge=0, le=5),
        limit: int = Query(25, ge=1, le=50),
    ) -> JSONResponse:
        """Filterable hotel catalogue.

        Used by the AI Concierge for "list / search" intents — the agent
        plans an http.get with the right query params instead of jumping
        straight to bookings.create when the user only asked to see options.

        Filters compose with AND semantics. Returns a structured envelope
        ``{filters, count, hotels}`` so a downstream notes.append step can
        surface "Listed N hotels in <city> under ₹<max>" back to the user
        without re-parsing the body.
        """
        from sentinelmesh_agents.demo_site.data import HOTELS

        rows = list(HOTELS)
        if city:
            cl = city.strip().lower()
            rows = [h for h in rows
                    if cl in h["city"].lower() or cl in h.get("area", "").lower()]
        if max_price is not None:
            rows = [h for h in rows if int(h["price_inr"]) <= int(max_price)]
        if min_rating is not None:
            rows = [h for h in rows if float(h.get("rating", 0)) >= float(min_rating)]
        rows = rows[:limit]
        return JSONResponse({
            "filters": {"city": city, "max_price": max_price, "min_rating": min_rating},
            "count": len(rows),
            "hotels": [
                {"id": h["id"], "name": h["name"], "city": h["city"],
                 "area": h.get("area"), "price_inr": h["price_inr"],
                 "rating": h.get("rating"), "url": f"/hotels/{h['id']}"}
                for h in rows
            ],
        })

    @router.get("/inventory/{hotel_id}")
    async def get_inventory(hotel_id: str) -> JSONResponse:
        return JSONResponse({"hotel_id": hotel_id,
                             "nights": service.get_inventory(hotel_id)})

    # ----- bookings ------------------------------------------------- #

    @router.post("/bookings")
    async def create_booking(
        body: dict[str, Any] = Body(...),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ) -> JSONResponse:
        # Generate one if the client didn't — typical for casual curl users
        # so they don't get a confusing 400 on their first try. Clients that
        # want safe retries MUST supply their own and reuse it on retry.
        key = idempotency_key or str(uuid.uuid4())
        try:
            req = _parse_request(body)
            result, status = service.create_booking(key, req)
            return JSONResponse(result, status_code=status,
                                headers={"Idempotency-Key": key})
        except BookingError as e:
            return _error_response(e)

    @router.get("/bookings/{booking_id}")
    async def get_booking(booking_id: str) -> JSONResponse:
        try:
            return JSONResponse(service.get_booking(booking_id))
        except BookingError as e:
            return _error_response(e)

    @router.get("/bookings")
    async def list_bookings(
        email: str | None = Query(None),
        session_id: str | None = Query(None, alias="sessionId"),
        limit: int = Query(50, ge=1, le=200),
    ) -> JSONResponse:
        return JSONResponse(
            service.list_bookings(email=email, session_id=session_id, limit=limit)
        )

    @router.post("/bookings/{booking_id}/cancel")
    async def cancel_booking(booking_id: str) -> JSONResponse:
        try:
            return JSONResponse(service.cancel_booking(booking_id))
        except BookingError as e:
            return _error_response(e)

    return router


# ---- helpers ---------------------------------------------------------- #

def _parse_request(body: dict[str, Any]) -> BookingRequest:
    required = ("hotel_id", "guest_name", "guest_email", "check_in", "check_out")
    for k in required:
        if not body.get(k):
            raise BookingError(f"missing field: {k}")
    email = body["guest_email"].strip()
    if not _EMAIL_RE.match(email):
        raise BookingError("guest_email must be a valid email address")
    if not _DATE_RE.match(body["check_in"]) or not _DATE_RE.match(body["check_out"]):
        raise BookingError("check_in and check_out must be YYYY-MM-DD")
    if body["check_out"] <= body["check_in"]:
        raise BookingError("check_out must be after check_in")
    adults = _coerce_count(body.get("adults"), default=1, lo=1, hi=12)
    children = _coerce_count(body.get("children"), default=0, lo=0, hi=8)
    return BookingRequest(
        hotel_id=str(body["hotel_id"]),
        guest_name=str(body["guest_name"])[:120],
        guest_email=email[:200],
        check_in=str(body["check_in"]),
        check_out=str(body["check_out"]),
        adults=adults,
        children=children,
        booked_by_ai=bool(body.get("booked_by_ai", False)),
        sentinel_session_id=(str(body["sentinel_session_id"])
                             if body.get("sentinel_session_id") else None),
    )


def _coerce_count(raw: Any, *, default: int, lo: int, hi: int) -> int:
    """Best-effort int coercion clamped to a sane range. Strings, floats, or
    junk all collapse to ``default`` rather than raising — this is a public
    HTTP surface and we don't want a typo to break the booking."""
    if raw is None:
        return default
    try:
        n = int(raw)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


def _error_response(e: BookingError) -> JSONResponse:
    """Map domain errors to a stable JSON shape that clients can branch on."""
    code = {
        NoInventory: "no_inventory",
        IdempotencyMismatch: "idempotency_mismatch",
        HotelNotFound: "hotel_not_found",
        BookingNotFound: "booking_not_found",
        IllegalTransition: "illegal_transition",
    }.get(type(e), "bad_request")
    log.info("Booking API error %s: %s", code, e)
    return JSONResponse({"error": code, "message": str(e)}, status_code=e.status_code)
