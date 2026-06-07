"""Outbox dispatcher tests.

The transactional outbox is only useful if a consumer actually drains it.
These tests prove three properties:

1. **Producer-consumer atomicity.** When ``create_booking`` succeeds, an
   event row is visible to the dispatcher *immediately* (same tx).
2. **At-least-once delivery.** The dispatcher drains a pending event and
   marks it ``delivered_at``.
3. **Webhook failure ⇒ retry.** A failing webhook does *not* mark the
   event delivered; the next poll picks it up again.
"""

from __future__ import annotations

import asyncio
import uuid
from pathlib import Path

import pytest

from sentinelmesh_agents.demo_site.booking_db import BookingDB
from sentinelmesh_agents.demo_site.booking_service import BookingRequest, BookingService
from sentinelmesh_agents.demo_site.outbox_dispatcher import OutboxDispatcher


@pytest.fixture
def db_and_service(tmp_path: Path) -> tuple[BookingDB, BookingService]:
    db = BookingDB(tmp_path / "outbox-test.db")
    db.init_schema()
    svc = BookingService(db)
    svc.seed_inventory(days=7, capacity=2)
    return db, svc


def _make_booking(svc: BookingService) -> str:
    from datetime import date, timedelta
    today = date.today()
    req = BookingRequest(
        hotel_id="grand-plaza", guest_name="Outbox Tester",
        guest_email="outbox@example.com",
        check_in=today.isoformat(),
        check_out=(today + timedelta(days=1)).isoformat(),
    )
    body, _ = svc.create_booking(str(uuid.uuid4()), req)
    return body["id"]


@pytest.mark.asyncio
async def test_outbox_drains_event_after_booking(db_and_service):
    db, svc = db_and_service
    _make_booking(svc)
    dispatcher = OutboxDispatcher(db, webhook_url=None)

    # Drain once — should deliver 1 event (the booking.confirmed).
    drained = await dispatcher._drain_once()
    assert drained == 1
    assert dispatcher.stats["delivered"] == 1
    # And the row is now flagged delivered, so a second drain delivers nothing.
    drained_again = await dispatcher._drain_once()
    assert drained_again == 0


@pytest.mark.asyncio
async def test_failing_webhook_leaves_event_pending_for_retry(db_and_service, monkeypatch):
    db, svc = db_and_service
    _make_booking(svc)
    dispatcher = OutboxDispatcher(db, webhook_url="http://nowhere.invalid/hook")

    # First attempt should fail (the webhook host is unreachable).
    drained = await dispatcher._drain_once()
    assert drained == 0
    assert dispatcher.stats["delivered"] == 0
    assert dispatcher.stats["failed"] == 1

    # The event is still pending in the table.
    with db.connect() as conn:
        pending = conn.execute(
            "SELECT COUNT(*) AS n FROM events WHERE delivered_at IS NULL"
        ).fetchone()["n"]
    assert pending == 1

    # Swap to a working "subscriber" (log-only) and drain again — succeeds.
    dispatcher.webhook_url = None
    drained2 = await dispatcher._drain_once()
    assert drained2 == 1


@pytest.mark.asyncio
async def test_background_task_starts_and_stops_cleanly(db_and_service):
    db, _svc = db_and_service
    dispatcher = OutboxDispatcher(db, webhook_url=None)
    await dispatcher.start()
    # Give the loop one tick to settle, then shut down.
    await asyncio.sleep(0.1)
    await dispatcher.stop()
    # No exception means clean shutdown — the loop exited on the stop event.
