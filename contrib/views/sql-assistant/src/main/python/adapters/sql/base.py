# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class QueryResult:
    columns: list[str]
    rows: list[list]
    row_count: int
    execution_time_ms: int
    truncated: bool = False
    warnings: list[str] = field(default_factory=list)


@dataclass
class TableInfo:
    catalog: str
    schema: str
    name: str
    columns: list[ColumnInfo] = field(default_factory=list)


@dataclass
class ColumnInfo:
    name: str
    data_type: str
    nullable: bool = True
    comment: str = ""


class SQLExecutor(ABC):
    """
    Abstract base for SQL engine adapters (Trino, Hive, …).
    Schema-introspection methods are separate from execution so the schema
    browser can load catalog trees without running queries.
    """

    @abstractmethod
    async def execute(
        self,
        sql: str,
        catalog: str | None = None,
        schema: str | None = None,
        timeout_seconds: int = 120,
    ) -> QueryResult:
        """Execute a SQL statement and return results."""

    @abstractmethod
    async def list_catalogs(self) -> list[str]:
        """Return available catalog names."""

    @abstractmethod
    async def list_schemas(self, catalog: str) -> list[str]:
        """Return schema names inside a catalog."""

    @abstractmethod
    async def list_tables(self, catalog: str, schema: str) -> list[str]:
        """Return table names inside a schema."""

    @abstractmethod
    async def describe_table(
        self, catalog: str, schema: str, table: str
    ) -> TableInfo:
        """Return column metadata for a table."""

    @abstractmethod
    async def health_check(self) -> dict:
        """Return {"status": "ok"|"error", "detail": str}."""

    @property
    @abstractmethod
    def dialect(self) -> str:
        """SQL dialect identifier, e.g. 'trino' or 'hive'."""
