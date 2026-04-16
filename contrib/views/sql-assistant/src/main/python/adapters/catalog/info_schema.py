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

from adapters.sql.base import SQLExecutor
from .base import (
    CatalogAdapter,
    CatalogEntry,
    NamespaceEntry,
    TableSummary,
    ColumnMeta,
    TableSchema,
)

logger = logging.getLogger(__name__)


class InformationSchemaAdapter(CatalogAdapter):
    """
    Fallback catalog adapter that reads metadata directly from the SQL engine's
    information_schema. Used when Polaris is not available or for non-Iceberg
    tables in Hive.

    Delegates to a SQLExecutor instance to run the introspection queries.
    """

    def __init__(self, executor: SQLExecutor) -> None:
        self._executor = executor

    async def list_catalogs(self) -> list[CatalogEntry]:
        try:
            names = await self._executor.list_catalogs()
            return [CatalogEntry(name=n) for n in names]
        except Exception as exc:
            logger.warning("list_catalogs via info_schema failed: %s", exc)
            return []

    async def list_namespaces(self, catalog: str) -> list[NamespaceEntry]:
        try:
            schemas = await self._executor.list_schemas(catalog)
            return [NamespaceEntry(catalog=catalog, name=s) for s in schemas]
        except Exception as exc:
            logger.warning("list_namespaces via info_schema failed: %s", exc)
            return []

    async def list_tables(
        self, catalog: str, namespace: str
    ) -> list[TableSummary]:
        try:
            tables = await self._executor.list_tables(catalog, namespace)
            return [
                TableSummary(catalog=catalog, namespace=namespace, name=t)
                for t in tables
            ]
        except Exception as exc:
            logger.warning("list_tables via info_schema failed: %s", exc)
            return []

    async def get_table_schema(
        self, catalog: str, namespace: str, table: str
    ) -> TableSchema:
        info = await self._executor.describe_table(catalog, namespace, table)
        columns = [
            ColumnMeta(
                name=c.name,
                data_type=c.data_type,
                nullable=c.nullable,
                description=c.comment,
            )
            for c in info.columns
        ]
        return TableSchema(
            catalog=catalog,
            namespace=namespace,
            name=table,
            columns=columns,
        )

    async def health_check(self) -> dict:
        return await self._executor.health_check()

    @property
    def adapter_name(self) -> str:
        return "information_schema"
