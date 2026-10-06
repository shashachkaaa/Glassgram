"""Payments the bot created, in SQLite: who pays, how much, and whether the badge was given."""

import time
from dataclasses import dataclass

import aiosqlite

SCHEMA = """
CREATE TABLE IF NOT EXISTS payments (
    transaction_id TEXT PRIMARY KEY,
    order_id       TEXT NOT NULL UNIQUE,
    user_id        INTEGER NOT NULL,
    amount         INTEGER NOT NULL,
    status         TEXT NOT NULL,
    badge_given    INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS payments_user ON payments(user_id);
CREATE INDEX IF NOT EXISTS payments_status ON payments(status);
"""


@dataclass
class Payment:
    transaction_id: str
    order_id: str
    user_id: int
    amount: int
    status: str
    badge_given: bool
    created_at: int


class Storage:
    def __init__(self, path: str):
        self._path = path
        self._db: aiosqlite.Connection | None = None

    async def open(self) -> None:
        self._db = await aiosqlite.connect(self._path)
        self._db.row_factory = aiosqlite.Row
        await self._db.executescript(SCHEMA)
        await self._db.commit()

    async def close(self) -> None:
        if self._db is not None:
            await self._db.close()

    async def add(self, transaction_id: str, order_id: str, user_id: int, amount: int, status: str) -> None:
        now = int(time.time())
        await self._db.execute(
            "INSERT INTO payments (transaction_id, order_id, user_id, amount, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (transaction_id, order_id, user_id, amount, status, now, now),
        )
        await self._db.commit()

    async def get(self, transaction_id: str) -> Payment | None:
        async with self._db.execute("SELECT * FROM payments WHERE transaction_id = ?", (transaction_id,)) as cursor:
            row = await cursor.fetchone()
        return _payment(row) if row else None

    async def set_status(self, transaction_id: str, status: str) -> None:
        await self._db.execute(
            "UPDATE payments SET status = ?, updated_at = ? WHERE transaction_id = ?",
            (status, int(time.time()), transaction_id),
        )
        await self._db.commit()

    async def set_badge_given(self, transaction_id: str, given: bool) -> None:
        await self._db.execute(
            "UPDATE payments SET badge_given = ?, updated_at = ? WHERE transaction_id = ?",
            (1 if given else 0, int(time.time()), transaction_id),
        )
        await self._db.commit()

    async def unsettled(self, newer_than: int) -> list[Payment]:
        """Payments not paid yet, or paid with the badge not given yet (GitHub was down)."""
        async with self._db.execute(
            "SELECT * FROM payments WHERE (status = 'PENDING' OR (status = 'CONFIRMED' AND badge_given = 0))"
            " AND created_at > ? ORDER BY created_at", (newer_than,)
        ) as cursor:
            return [_payment(row) for row in await cursor.fetchall()]

    async def confirmed_for(self, user_id: int) -> list[Payment]:
        async with self._db.execute(
            "SELECT * FROM payments WHERE user_id = ? AND status = 'CONFIRMED'", (user_id,)
        ) as cursor:
            return [_payment(row) for row in await cursor.fetchall()]

    async def latest(self, limit: int) -> list[Payment]:
        async with self._db.execute("SELECT * FROM payments ORDER BY created_at DESC LIMIT ?", (limit,)) as cursor:
            return [_payment(row) for row in await cursor.fetchall()]


def _payment(row) -> Payment:
    return Payment(
        transaction_id=row["transaction_id"],
        order_id=row["order_id"],
        user_id=row["user_id"],
        amount=row["amount"],
        status=row["status"],
        badge_given=bool(row["badge_given"]),
        created_at=row["created_at"],
    )
