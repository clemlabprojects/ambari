# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone

import aiosqlite

logger = logging.getLogger(__name__)

DDL = """
CREATE TABLE IF NOT EXISTS query_history (
    id          TEXT PRIMARY KEY,
    created_at  TEXT NOT NULL,
    question    TEXT NOT NULL,
    sql         TEXT NOT NULL,
    dialect     TEXT NOT NULL,
    catalog     TEXT,
    namespace   TEXT,
    model       TEXT,
    status      TEXT NOT NULL DEFAULT 'success',
    error       TEXT,
    generation_time_ms  INTEGER DEFAULT 0,
    execution_time_ms   INTEGER DEFAULT 0,
    row_count           INTEGER DEFAULT 0,
    user_name           TEXT
);
CREATE INDEX IF NOT EXISTS idx_created_at ON query_history (created_at DESC);
"""


@dataclass
class HistoryEntry:
    id: str
    created_at: str
    question: str
    sql: str
    dialect: str
    catalog: str | None
    namespace: str | None
    model: str | None
    status: str
    error: str | None
    generation_time_ms: int
    execution_time_ms: int
    row_count: int
    user_name: str | None


class HistoryStore:
    """
    Async SQLite-backed store for query history.
    The database file is created on first use; the schema is migrated
    automatically via DDL idempotent statements.
    """

    def __init__(self, db_path: str, max_entries: int = 1000) -> None:
        self._db_path = db_path
        self._max_entries = max_entries

    async def init(self) -> None:
        import os
        os.makedirs(os.path.dirname(self._db_path), exist_ok=True)
        async with aiosqlite.connect(self._db_path) as db:
            await db.executescript(DDL)
            await db.commit()

    async def save(self, entry: HistoryEntry) -> None:
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute(
                """
                INSERT OR REPLACE INTO query_history
                (id, created_at, question, sql, dialect, catalog, namespace,
                 model, status, error, generation_time_ms, execution_time_ms,
                 row_count, user_name)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    entry.id,
                    entry.created_at,
                    entry.question,
                    entry.sql,
                    entry.dialect,
                    entry.catalog,
                    entry.namespace,
                    entry.model,
                    entry.status,
                    entry.error,
                    entry.generation_time_ms,
                    entry.execution_time_ms,
                    entry.row_count,
                    entry.user_name,
                ),
            )
            await db.commit()
            # Prune oldest entries beyond max_entries
            await db.execute(
                """
                DELETE FROM query_history WHERE id IN (
                  SELECT id FROM query_history
                  ORDER BY created_at DESC
                  LIMIT -1 OFFSET ?
                )
                """,
                (self._max_entries,),
            )
            await db.commit()

    async def list(
        self,
        limit: int = 50,
        offset: int = 0,
        search: str | None = None,
        dialect: str | None = None,
    ) -> tuple[list[HistoryEntry], int]:
        clauses: list[str] = []
        params: list = []
        if search:
            clauses.append(
                "(question LIKE ? OR sql LIKE ?)"
            )
            like = f"%{search}%"
            params += [like, like]
        if dialect:
            clauses.append("dialect = ?")
            params.append(dialect)

        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        async with aiosqlite.connect(self._db_path) as db:
            db.row_factory = aiosqlite.Row
            cur = await db.execute(
                f"SELECT COUNT(*) FROM query_history {where}", params
            )
            total = (await cur.fetchone())[0]
            cur = await db.execute(
                f"""
                SELECT * FROM query_history {where}
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                params + [limit, offset],
            )
            rows = await cur.fetchall()
        return [_row_to_entry(r) for r in rows], total

    async def get(self, entry_id: str) -> HistoryEntry | None:
        async with aiosqlite.connect(self._db_path) as db:
            db.row_factory = aiosqlite.Row
            cur = await db.execute(
                "SELECT * FROM query_history WHERE id = ?", (entry_id,)
            )
            row = await cur.fetchone()
        return _row_to_entry(row) if row else None

    async def delete(self, entry_id: str) -> bool:
        async with aiosqlite.connect(self._db_path) as db:
            cur = await db.execute(
                "DELETE FROM query_history WHERE id = ?", (entry_id,)
            )
            await db.commit()
        return cur.rowcount > 0

    async def clear(self) -> None:
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("DELETE FROM query_history")
            await db.commit()


def _row_to_entry(row: aiosqlite.Row) -> HistoryEntry:
    return HistoryEntry(
        id=row["id"],
        created_at=row["created_at"],
        question=row["question"],
        sql=row["sql"],
        dialect=row["dialect"],
        catalog=row["catalog"],
        namespace=row["namespace"],
        model=row["model"],
        status=row["status"],
        error=row["error"],
        generation_time_ms=row["generation_time_ms"] or 0,
        execution_time_ms=row["execution_time_ms"] or 0,
        row_count=row["row_count"] or 0,
        user_name=row["user_name"],
    )
