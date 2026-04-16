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

from pyhive import hive as pyhive_hive

from .base import SQLExecutor, QueryResult, TableInfo, ColumnInfo

logger = logging.getLogger(__name__)


class HiveExecutor(SQLExecutor):
    """
    Executes HiveQL against HiveServer2 via PyHive.
    Like TrinoExecutor, the synchronous client is wrapped with run_in_executor.
    """

    def __init__(
        self,
        host: str,
        port: int = 10000,
        user: str = "hive",
        password: str = "",
        auth: str = "KERBEROS",
        kerberos_service_name: str = "hive",
        database: str = "default",
    ) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._auth = auth
        self._kerberos_service_name = kerberos_service_name
        self._database = database

    # ── internal helpers ─────────────────────────────────────────────────────

    def _get_connection(self, schema: str | None = None) -> pyhive_hive.Connection:
        kwargs: dict = {
            "host": self._host,
            "port": self._port,
            "username": self._user,
            "database": schema or self._database,
        }
        if self._auth.upper() == "KERBEROS":
            kwargs["auth"] = "KERBEROS"
            kwargs["kerberos_service_name"] = self._kerberos_service_name
        elif self._password:
            kwargs["auth"] = "CUSTOM"
            kwargs["password"] = self._password
        return pyhive_hive.connect(**kwargs)

    def _run_query(
        self,
        sql: str,
        schema: str | None,
        max_rows: int,
    ) -> tuple[list[str], list[list], int]:
        conn = self._get_connection(schema)
        cursor = conn.cursor()
        cursor.execute(sql)
        desc = cursor.description or []
        columns = [d[0] for d in desc]
        rows = []
        for row in cursor:
            rows.append(list(row))
            if len(rows) >= max_rows:
                break
        return columns, rows, len(rows)

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
                    None, partial(self._run_query, sql, schema, max_rows)
                ),
                timeout=timeout_seconds + 5,
            )
        except asyncio.TimeoutError as exc:
            raise TimeoutError(
                f"Hive query timed out after {timeout_seconds}s"
            ) from exc
        except Exception as exc:
            raise RuntimeError(f"Hive query error: {exc}") from exc

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
        # Hive does not have a catalog concept; return a single virtual entry
        return ["hive"]

    async def list_schemas(self, catalog: str) -> list[str]:
        result = await self.execute("SHOW DATABASES")
        return [row[0] for row in result.rows]

    async def list_tables(self, catalog: str, schema: str) -> list[str]:
        result = await self.execute("SHOW TABLES", schema=schema)
        return [row[0] for row in result.rows]

    async def describe_table(
        self, catalog: str, schema: str, table: str
    ) -> TableInfo:
        result = await self.execute(f"DESCRIBE {schema}.{table}", schema=schema)
        columns = [
            ColumnInfo(
                name=row[0],
                data_type=row[1],
                nullable=True,
                comment=row[2] if len(row) > 2 else "",
            )
            for row in result.rows
            if row[0] and not row[0].startswith("#")
        ]
        return TableInfo(
            catalog=catalog,
            schema=schema,
            name=table,
            columns=columns,
        )

    async def health_check(self) -> dict:
        try:
            result = await asyncio.wait_for(
                self.execute("SELECT 1"), timeout=10
            )
            if result.rows:
                return {
                    "status": "ok",
                    "detail": f"HiveServer2 reachable at {self._host}:{self._port}",
                }
            return {"status": "error", "detail": "Unexpected empty response"}
        except Exception as exc:
            return {"status": "error", "detail": str(exc)}

    @property
    def dialect(self) -> str:
        return "hive"
