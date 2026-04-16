# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

import asyncio
import logging
import time
from functools import partial

import trino
from trino.dbapi import connect as trino_connect
from trino.exceptions import TrinoQueryError

from .base import SQLExecutor, QueryResult, TableInfo, ColumnInfo

logger = logging.getLogger(__name__)


class TrinoExecutor(SQLExecutor):
    """
    Executes SQL against a Trino cluster.
    The trino-python-client is synchronous, so queries are dispatched via
    asyncio.run_in_executor to avoid blocking the event loop.
    """

    def __init__(
        self,
        host: str,
        port: int = 8080,
        user: str = "trino",
        password: str = "",
        http_scheme: str = "http",
        verify: bool = True,
    ) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._http_scheme = http_scheme
        self._verify = verify

    # ── internal helpers ─────────────────────────────────────────────────────

    def _get_connection(
        self, catalog: str | None = None, schema: str | None = None
    ) -> trino.dbapi.Connection:
        kwargs: dict = {
            "host": self._host,
            "port": self._port,
            "user": self._user,
            "http_scheme": self._http_scheme,
        }
        if self._password:
            kwargs["auth"] = trino.auth.BasicAuthentication(
                self._user, self._password
            )
        if catalog:
            kwargs["catalog"] = catalog
        if schema:
            kwargs["schema"] = schema
        return trino_connect(**kwargs)

    def _run_query(
        self,
        sql: str,
        catalog: str | None,
        schema: str | None,
        max_rows: int,
        timeout_seconds: int,
    ) -> tuple[list[str], list[list], int]:
        conn = self._get_connection(catalog, schema)
        cursor = conn.cursor()
        cursor.execute(sql)
        desc = cursor.description or []
        columns = [d[0] for d in desc]
        rows = []
        for row in cursor:
            rows.append(list(row))
            if len(rows) >= max_rows:
                break
        return columns, rows, cursor.rowcount or len(rows)

    # ── SQLExecutor interface ─────────────────────────────────────────────────

    async def execute(
        self,
        sql: str,
        catalog: str | None = None,
        schema: str | None = None,
        timeout_seconds: int = 120,
        max_rows: int = 10_000,
    ) -> QueryResult:
        loop = asyncio.get_running_loop()
        t0 = time.monotonic()
        try:
            columns, rows, count = await asyncio.wait_for(
                loop.run_in_executor(
                    None,
                    partial(
                        self._run_query,
                        sql,
                        catalog,
                        schema,
                        max_rows,
                        timeout_seconds,
                    ),
                ),
                timeout=timeout_seconds + 5,
            )
        except asyncio.TimeoutError as exc:
            raise TimeoutError(
                f"Trino query timed out after {timeout_seconds}s"
            ) from exc
        except TrinoQueryError as exc:
            raise RuntimeError(f"Trino query error: {exc}") from exc

        elapsed_ms = int((time.monotonic() - t0) * 1000)
        truncated = len(rows) >= max_rows
        return QueryResult(
            columns=columns,
            rows=rows,
            row_count=count,
            execution_time_ms=elapsed_ms,
            truncated=truncated,
            warnings=["Result set truncated to first %d rows." % max_rows]
            if truncated
            else [],
        )

    async def list_catalogs(self) -> list[str]:
        result = await self.execute("SHOW CATALOGS")
        return [row[0] for row in result.rows]

    async def list_schemas(self, catalog: str) -> list[str]:
        result = await self.execute(f"SHOW SCHEMAS FROM {catalog}")
        return [row[0] for row in result.rows]

    async def list_tables(self, catalog: str, schema: str) -> list[str]:
        result = await self.execute(
            f"SHOW TABLES FROM {catalog}.{schema}"
        )
        return [row[0] for row in result.rows]

    async def describe_table(
        self, catalog: str, schema: str, table: str
    ) -> TableInfo:
        result = await self.execute(
            f"DESCRIBE {catalog}.{schema}.{table}"
        )
        columns = [
            ColumnInfo(
                name=row[0],
                data_type=row[1],
                nullable=True,
                comment=row[3] if len(row) > 3 else "",
            )
            for row in result.rows
        ]
        return TableInfo(
            catalog=catalog,
            schema=schema,
            name=table,
            columns=columns,
        )

    async def health_check(self) -> dict:
        try:
            result = await asyncio.wait_for(self.execute("SELECT 1"), timeout=10)
            if result.rows:
                return {"status": "ok", "detail": f"Trino reachable at {self._host}:{self._port}"}
            return {"status": "error", "detail": "Unexpected empty response from Trino"}
        except Exception as exc:
            return {"status": "error", "detail": str(exc)}

    @property
    def dialect(self) -> str:
        return "trino"
