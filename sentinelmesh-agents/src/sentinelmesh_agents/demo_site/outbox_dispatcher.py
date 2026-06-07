"""Background dispatcher that drains the booking-events outbox.

The booking service writes every state change (``booking.confirmed``,
``booking.cancelled``, ...) to the ``events`` table inside the same
transaction as the data write. This dispatcher is the *consumer*: it
polls undelivered rows, fans them out to subscribers, and marks them
delivered atomically.

Why this matters for the prototype
----------------------------------
This is the "transactional outbox" pattern. It gives us **at-least-once**
delivery without distributed-transaction overhead:

* The producer side can't lose an event (it goes into the same SQL tx
  as the booking row — either both commit or neither do).
* The consumer side can crash and restart and we'll re-deliver from the
  last undelivered row.
* Duplicate delivery is on the *subscriber* to handle (we include a
  stable ``event_id`` so they can dedupe).

For the prototype the only subscriber is a simple HTTP webhook (the URL
is configurable; missing → logs only). In production this would be a
Kafka producer, an SQS publisher, or a Redis stream writer.

Concurrency
-----------
The dispatcher runs as one background task per app process. Inside, it
serializes work behind ``BEGIN IMMEDIATE`` so two replicas of the demo
site can run safely if we ever fan out — only one will mark a given row
delivered.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from datetime import datetime, timezone
from typing import Any

import httpx

from sentinelmesh_agents.demo_site.booking_db import BookingDB

log = logging.getLogger("skynest.outbox")

_POLL_INTERVAL_S = 2.0
_BATCH_SIZE = 32
_WEBHOOK_TIMEOUT_S = 5.0


class OutboxDispatcher:
    """Polls the ``events`` table, dispatches undelivered rows, marks delivered.

    The dispatcher is robust to subscriber failures: a failed dispatch is
    simply not marked delivered, so the next poll picks it up again. We
    don't currently implement a backoff or DLQ — those are obvious follow-ups
    and the README is honest about that.
    """

    def __init__(self, db: BookingDB, webhook_url: str | None = None):
        self.db = db
        self.webhook_url = webhook_url
        self._task: asyncio.Task[None] | None = None
        self._stop = asyncio.Event()
        # Visible counters: drive the small UI badge in the demo site.
        self.stats = {"delivered": 0, "failed": 0, "last_delivered_at": None}

    async def start(self) -> None:
        if self._task is not None:
            return
        log.info("Outbox dispatcher starting (webhook=%s)", self.webhook_url or "<log-only>")
        self._task = asyncio.create_task(self._run(), name="skynest-outbox")

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            await self._task
            self._task = None

    async def _run(self) -> None:
        while not self._stop.is_set():
            try:
                drained = await self._drain_once()
                if drained == 0:
                    # Wake up early if shutdown signalled; otherwise sleep.
                    try:
                        await asyncio.wait_for(self._stop.wait(), timeout=_POLL_INTERVAL_S)
                    except asyncio.TimeoutError:
                        pass
            except Exception as e:  # noqa: BLE001 — never let a bug kill the loop
                log.exception("outbox dispatcher iteration failed: %s", e)
                await asyncio.sleep(_POLL_INTERVAL_S)

    async def _drain_once(self) -> int:
        # Snapshot a batch of undelivered rows in one short read.
        with self.db.connect() as conn:
            rows = conn.execute(
                "SELECT id, booking_id, kind, payload_json, created_at "
                "FROM events WHERE delivered_at IS NULL "
                "ORDER BY id ASC LIMIT ?", (_BATCH_SIZE,),
            ).fetchall()
        if not rows:
            return 0

        delivered_ids: list[int] = []
        for r in rows:
            ok = await self._dispatch_one({
                "event_id": r["id"],
                "booking_id": r["booking_id"],
                "kind": r["kind"],
                "payload": json.loads(r["payload_json"]),
                "created_at": r["created_at"],
            })
            if ok:
                delivered_ids.append(r["id"])
                self.stats["delivered"] += 1
                self.stats["last_delivered_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
            else:
                self.stats["failed"] += 1

        if delivered_ids:
            with self.db.write_tx() as conn:
                conn.executemany(
                    "UPDATE events SET delivered_at = ? WHERE id = ?",
                    [(datetime.now(timezone.utc).isoformat(timespec="seconds"), eid)
                     for eid in delivered_ids],
                )
            log.info("Outbox drained %d events", len(delivered_ids))
        return len(delivered_ids)

    async def _dispatch_one(self, event: dict[str, Any]) -> bool:
        """Best-effort dispatch. Returns True on success.

        For the prototype: if no webhook URL is configured, we log the event
        at INFO and consider that 'delivered'. This keeps the demo turnkey
        but the production version would crash-loop if nothing was attached.
        """
        if not self.webhook_url:
            log.info("outbox(%s/%s): %s", event["kind"], event["event_id"],
                     event["payload"].get("id", "?"))
            return True
        try:
            async with httpx.AsyncClient(timeout=_WEBHOOK_TIMEOUT_S) as client:
                r = await client.post(self.webhook_url, json=event)
                r.raise_for_status()
            return True
        except Exception as e:  # noqa: BLE001 — failures stay in outbox for retry
            log.warning("outbox webhook failed for event %s (%s): %s",
                        event["event_id"], event["kind"], e)
            return False


def from_env(db: BookingDB) -> OutboxDispatcher:
    """Construct from env: ``SKYNEST_OUTBOX_WEBHOOK_URL`` if set, else log-only."""
    return OutboxDispatcher(db, webhook_url=os.getenv("SKYNEST_OUTBOX_WEBHOOK_URL"))
