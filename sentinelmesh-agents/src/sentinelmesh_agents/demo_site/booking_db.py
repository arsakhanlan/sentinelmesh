"""SQLite-backed persistence for the SkyNest booking system.

Why SQLite for the prototype: zero-config, file-on-disk, survives container
restarts when mounted as a Docker volume, and supports the two features we
actually need from the storage engine — atomic conditional updates (so we
can claim inventory in a single statement) and a UNIQUE constraint for the
idempotency table. The schema is intentionally Postgres-shaped so a future
swap is a connection-string change, not a rewrite.

Concurrency model
-----------------
SQLite serializes writes at the database level, which is exactly what we
want for the prototype's correctness properties:

* **Idempotency** — the ``idempotency_keys`` table has a ``UNIQUE(key)``
  constraint; a second writer's INSERT fails, signalling the duplicate.
* **Inventory race** — ``UPDATE inventory SET available = available - 1
  WHERE hotel_id = ? AND date = ? AND available > 0`` is atomic. Either
  the row was claimed (rowcount == 1) or it was already at zero
  (rowcount == 0). No "check-then-set" race window.
* **Booking state machine** — transitions guarded by a ``WHERE status = ?``
  predicate so a stale client can't cancel an already-cancelled row.
* **Outbox** — the ``events`` table is read by the dispatcher in another
  pass; producers never block on event delivery.

The implementation uses one connection per request (via ``contextmanager``),
``PRAGMA journal_mode=WAL`` for non-blocking reads, and ``BEGIN IMMEDIATE``
for write transactions so two writers don't deadlock on a deferred lock.
"""

from __future__ import annotations

import logging
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

log = logging.getLogger("skynest.db")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS hotels (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    city         TEXT NOT NULL,
    price_inr    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS inventory (
    hotel_id     TEXT NOT NULL,
    date_iso     TEXT NOT NULL,        -- YYYY-MM-DD
    available    INTEGER NOT NULL CHECK (available >= 0),
    capacity     INTEGER NOT NULL,
    PRIMARY KEY (hotel_id, date_iso),
    FOREIGN KEY (hotel_id) REFERENCES hotels(id)
);

CREATE TABLE IF NOT EXISTS bookings (
    id            TEXT PRIMARY KEY,        -- public-facing booking ref (BKN-XXXXXX)
    hotel_id      TEXT NOT NULL,
    guest_name    TEXT NOT NULL,
    guest_email   TEXT NOT NULL,
    check_in      TEXT NOT NULL,           -- YYYY-MM-DD
    check_out     TEXT NOT NULL,
    nights        INTEGER NOT NULL CHECK (nights > 0),
    adults        INTEGER NOT NULL DEFAULT 1 CHECK (adults > 0),
    children      INTEGER NOT NULL DEFAULT 0 CHECK (children >= 0),
    amount_inr    INTEGER NOT NULL CHECK (amount_inr >= 0),
    status        TEXT NOT NULL,           -- PENDING | CONFIRMED | CANCELLED | FAILED
    booked_by_ai  INTEGER NOT NULL DEFAULT 0,
    sentinel_session_id TEXT,              -- correlate booking to the agent session
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    version       INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (hotel_id) REFERENCES hotels(id)
);

CREATE INDEX IF NOT EXISTS idx_bookings_email ON bookings(guest_email);
CREATE INDEX IF NOT EXISTS idx_bookings_session ON bookings(sentinel_session_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key            TEXT PRIMARY KEY,        -- client-supplied UUID
    request_hash   TEXT NOT NULL,           -- SHA-256 of canonical request body
    response_json  TEXT NOT NULL,           -- cached response body
    status_code    INTEGER NOT NULL,
    booking_id     TEXT,                    -- optional FK for traceability
    created_at     TEXT NOT NULL,
    expires_at     TEXT NOT NULL
);

-- Transactional outbox. Producers append; a dispatcher (next iteration) ships
-- to downstream subscribers and marks delivered_at. For the prototype, the
-- table doubles as an inspectable audit trail of what the booking system did.
CREATE TABLE IF NOT EXISTS events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    booking_id   TEXT,
    kind         TEXT NOT NULL,           -- booking.created | booking.confirmed | ...
    payload_json TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    delivered_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_events_undelivered ON events(delivered_at);
"""


class BookingDB:
    """Thin wrapper around a single SQLite file with helpful pragmas applied.

    Threading: each call to :meth:`connect` returns a brand-new connection so
    the FastAPI thread pool / asyncio workers don't share a connection (SQLite
    objects aren't safe to share across threads).
    """

    def __init__(self, path: str | Path):
        self.path = str(path)
        self._init_lock = threading.Lock()
        self._initialized = False

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        # ``check_same_thread=False`` is safe because we never share an open
        # connection across threads — we open one per ``with`` block.
        conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        conn.row_factory = sqlite3.Row
        # WAL: readers don't block writers; great for the booking + listing mix.
        conn.execute("PRAGMA journal_mode=WAL")
        # Foreign keys are off by default in SQLite — we want them.
        conn.execute("PRAGMA foreign_keys=ON")
        # 5s busy-timeout: under contention the engine retries instead of
        # immediately failing with SQLITE_BUSY. Combined with our short
        # transactions this is plenty of headroom for the prototype.
        conn.execute("PRAGMA busy_timeout=5000")
        try:
            yield conn
        finally:
            conn.close()

    def init_schema(self) -> None:
        """Idempotent migration: runs on app startup.

        Also forward-migrates DB files created by older versions of the app
        — adds ``adults`` / ``children`` columns to ``bookings`` if missing.
        SQLite has no ``ADD COLUMN IF NOT EXISTS`` so we introspect first.
        """
        with self._init_lock:
            if self._initialized:
                return
            log.info("Initializing booking DB at %s", self.path)
            with self.connect() as conn:
                conn.executescript(_SCHEMA)
                cols = {row["name"] for row in conn.execute("PRAGMA table_info(bookings)").fetchall()}
                if "adults" not in cols:
                    conn.execute("ALTER TABLE bookings ADD COLUMN adults INTEGER NOT NULL DEFAULT 1")
                if "children" not in cols:
                    conn.execute("ALTER TABLE bookings ADD COLUMN children INTEGER NOT NULL DEFAULT 0")
            self._initialized = True

    @contextmanager
    def write_tx(self) -> Iterator[sqlite3.Connection]:
        """Open a write transaction with BEGIN IMMEDIATE so two writers don't
        deadlock on the lock-upgrade path. Auto-rolls back on exception."""
        with self.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                yield conn
            except Exception:
                conn.execute("ROLLBACK")
                raise
            else:
                conn.execute("COMMIT")
