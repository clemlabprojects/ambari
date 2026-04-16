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
class CatalogEntry:
    name: str
    description: str = ""


@dataclass
class NamespaceEntry:
    catalog: str
    name: str
    description: str = ""


@dataclass
class TableSummary:
    catalog: str
    namespace: str
    name: str
    table_type: str = "TABLE"
    description: str = ""


@dataclass
class ColumnMeta:
    name: str
    data_type: str
    nullable: bool = True
    description: str = ""
    partition_key: bool = False


@dataclass
class TableSchema:
    catalog: str
    namespace: str
    name: str
    columns: list[ColumnMeta] = field(default_factory=list)
    description: str = ""
    format: str = ""
    location: str = ""


class CatalogAdapter(ABC):
    """
    Abstract base for catalog metadata adapters (Polaris MCP, information_schema…).
    Used by the prompt builder to inject schema context and by the schema browser
    to populate the UI tree.  All methods are async to support remote calls
    without blocking the event loop.
    """

    @abstractmethod
    async def list_catalogs(self) -> list[CatalogEntry]:
        """Return all accessible catalogs."""

    @abstractmethod
    async def list_namespaces(self, catalog: str) -> list[NamespaceEntry]:
        """Return all namespaces (schemas) inside a catalog."""

    @abstractmethod
    async def list_tables(
        self, catalog: str, namespace: str
    ) -> list[TableSummary]:
        """Return table summaries inside a namespace."""

    @abstractmethod
    async def get_table_schema(
        self, catalog: str, namespace: str, table: str
    ) -> TableSchema:
        """Return full column metadata for a specific table."""

    @abstractmethod
    async def health_check(self) -> dict:
        """Return {"status": "ok"|"error", "detail": str}."""

    @property
    @abstractmethod
    def adapter_name(self) -> str:
        """Short identifier, e.g. 'polaris_mcp', 'information_schema'."""
