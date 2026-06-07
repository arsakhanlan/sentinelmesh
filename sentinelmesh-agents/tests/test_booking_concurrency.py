"""Concurrency + idempotency tests for the SkyNest booking service.

These tests are the "show, don't tell" part of the system-design story:
they prove that the load-bearing invariants hold under contention.

Invariants exercised here
-------------------------
1. **No overselling under contention.** When N threads concurrently try
   to book the *single* room left for a night, exactly one wins; the
   rest get a deterministic ``NoInventory`` error. No torn writes, no
   "off by one" inventory.
2. **Idempotency replay.** A retry with the same ``Idempotency-Key`` and
   same body returns the original response with the original booking
   ID — never a second row.
3. **Idempotency mismatch.** A retry with the same key but a *different*
   body is rejected (409), not silently overwritten.
4. **Cancel is idempotent.** Cancelling twice returns CANCELLED both
   times and only restores inventory once.
"""

from __future__ import annotations

import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from sentinelmesh_agents.demo_site.booking_db import BookingDB
from sentinelmesh_agents.demo_site.booking_service import (
    BookingRequest, BookingService, IdempotencyMismatch, NoInventory,
)


@pytest.fixture
def service(tmp_path: Path) -> BookingService:
    db = BookingDB(tmp_path / "test-booking.db")
    db.init_schema()
    svc = BookingService(db)
    # Seed exactly 1 night of capacity-1 inventory for grand-plaza, so the
    # first booking wins and everyone else hits NoInventory deterministically.
    svc.seed_inventory(days=14, capacity=1)
    return svc


def _req(email_seed: str = "alice") -> BookingRequest:
    from datetime import date, timedelta
    today = date.today()
    return BookingRequest(
        hotel_id="grand-plaza",
        guest_name=f"Guest {email_seed}",
        guest_email=f"{email_seed}@example.com",
        check_in=today.isoformat(),
        check_out=(today + timedelta(days=1)).isoformat(),
    )


def test_inventory_race_only_one_winner(service: BookingService) -> None:
    """10 concurrent bookers, 1 room, exactly 1 wins. No oversell."""
    req = _req()
    N = 10
    results: list[tuple[str, str]] = []  # (outcome, detail)

    def attempt(_i: int) -> tuple[str, str]:
        # Each attempt has its own idem key, so they are all *distinct*
        # bookings competing for the same inventory row.
        key = str(uuid.uuid4())
        try:
            body, _ = service.create_booking(key, req)
            return ("ok", body["id"])
        except NoInventory as e:
            return ("sold_out", str(e))

    with ThreadPoolExecutor(max_workers=N) as pool:
        results = list(pool.map(attempt, range(N)))

    wins = [r for r in results if r[0] == "ok"]
    losses = [r for r in results if r[0] == "sold_out"]

    assert len(wins) == 1, f"expected exactly 1 winner, got {len(wins)}: {results}"
    assert len(losses) == N - 1
    # Inventory for that night should be at 0/1 — fully claimed, not negative.
    inv = service.get_inventory("grand-plaza")
    today_row = next(row for row in inv if row["date"] == req.check_in)
    assert today_row["available"] == 0
    assert today_row["capacity"] == 1


def test_idempotency_replay_returns_same_booking(service: BookingService) -> None:
    """Same key + same body → same booking, no second row."""
    req = _req()
    key = str(uuid.uuid4())

    body1, status1 = service.create_booking(key, req)
    body2, status2 = service.create_booking(key, req)

    assert status1 == 201
    assert status2 == 200  # idempotent replay is 200 OK with same body
    assert body1["id"] == body2["id"]
    # Only one booking row created
    listed = service.list_bookings(email=req.guest_email)
    assert len(listed) == 1


def test_idempotency_mismatch_is_rejected(service: BookingService) -> None:
    """Same key, different body → 409, not a silent overwrite."""
    req = _req()
    key = str(uuid.uuid4())
    service.create_booking(key, req)

    different = BookingRequest(
        hotel_id=req.hotel_id, guest_name=req.guest_name,
        guest_email="someone-else@example.com",
        check_in=req.check_in, check_out=req.check_out,
    )
    with pytest.raises(IdempotencyMismatch):
        service.create_booking(key, different)


def test_cancel_is_idempotent_and_restores_inventory(service: BookingService) -> None:
    req = _req()
    key = str(uuid.uuid4())
    body, _ = service.create_booking(key, req)

    inv_after_book = next(row for row in service.get_inventory("grand-plaza")
                          if row["date"] == req.check_in)
    assert inv_after_book["available"] == 0

    cancelled1 = service.cancel_booking(body["id"])
    cancelled2 = service.cancel_booking(body["id"])  # idempotent re-cancel
    assert cancelled1["status"] == "CANCELLED"
    assert cancelled2["status"] == "CANCELLED"

    # Inventory restored exactly once — not bumped to 2 by the second cancel.
    inv_after_cancel = next(row for row in service.get_inventory("grand-plaza")
                            if row["date"] == req.check_in)
    assert inv_after_cancel["available"] == 1
