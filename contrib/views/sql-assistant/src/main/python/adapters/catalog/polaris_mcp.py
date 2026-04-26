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
import json
import logging
import time
from typing import Any

import httpx

from auth_context import user_token
from .base import (
    CatalogAdapter,
    CatalogEntry,
    NamespaceEntry,
    TableSummary,
    ColumnMeta,
    TableSchema,
)

logger = logging.getLogger(__name__)


class PolarisMCPAdapter(CatalogAdapter):
    """
    Catalog adapter backed by the Apache Polaris MCP server.

    The Polaris MCP server exposes 7 tools via SSE or HTTP transport:
      - polaris-catalog-request       (list, get, create, …)
      - polaris-namespace-request     (list, get, create, …)
      - polaris-iceberg-table-request (list, get, create, commit, delete)
      - polaris-principal-request
      - polaris-principal-role-request
      - polaris-catalog-role-request
      - polaris-policy-request

    This adapter uses the HTTP REST wrapper that the Polaris REST catalog
    exposes at /api/catalog/v1 (Iceberg REST spec), which the MCP tools
    themselves call. We talk to those same endpoints directly via httpx so
    we do not need a running MCP *process* – only the Polaris server at
    polaris_base_url (typically master03:8181).

    For schema discovery the relevant Iceberg REST catalog endpoints are:
      GET /api/catalog/v1/{prefix}/namespaces
      GET /api/catalog/v1/{prefix}/namespaces/{ns}/tables
      GET /api/catalog/v1/{prefix}/namespaces/{ns}/tables/{table}

    Results are cached for `cache_ttl_seconds` to avoid hammering the server
    on every schema-browser expand or prompt generation.
    """

    def __init__(
        self,
        base_url: str,
        token: str = "",
        client_id: str = "",
        client_secret: str = "",
        cache_ttl_seconds: int = 300,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._static_token = token
        self._client_id = client_id
        self._client_secret = client_secret
        self._cache_ttl = cache_ttl_seconds
        self._cache: dict[str, tuple[Any, float]] = {}
        self._http = httpx.AsyncClient(
            base_url=self._base_url,
            headers={"Content-Type": "application/json"},
            timeout=httpx.Timeout(30),
        )

    # ── cache helpers ────────────────────────────────────────────────────────

    def _cache_get(self, key: str) -> Any | None:
        entry = self._cache.get(key)
        if entry and (time.monotonic() - entry[1]) < self._cache_ttl:
            return entry[0]
        return None

    def _cache_set(self, key: str, value: Any) -> None:
        self._cache[key] = (value, time.monotonic())

    def invalidate(self) -> None:
        self._cache.clear()

    # ── HTTP helpers ─────────────────────────────────────────────────────────

    def _auth_header(self) -> dict[str, str]:
        tok = user_token.get(None) or self._static_token
        return {"Authorization": f"Bearer {tok}"} if tok else {}

    async def _get(self, path: str, params: dict | None = None) -> Any:
        resp = await self._http.get(path, params=params, headers=self._auth_header())
        resp.raise_for_status()
        return resp.json()

    async def _post(self, path: str, body: dict) -> Any:
        resp = await self._http.post(path, json=body, headers=self._auth_header())
        resp.raise_for_status()
        return resp.json()

    # ── CatalogAdapter interface ─────────────────────────────────────────────

    async def list_catalogs(self) -> list[CatalogEntry]:
        key = "catalogs"
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        # Polaris management API lists catalogs
        try:
            data = await self._get("/api/management/v1/catalogs")
            catalogs = [
                CatalogEntry(
                    name=c.get("name", ""),
                    description=c.get("properties", {}).get("comment", ""),
                )
                for c in data.get("catalogs", [])
            ]
        except httpx.HTTPError:
            # Fallback: try Iceberg REST prefix discovery
            catalogs = [CatalogEntry(name="polaris")]

        self._cache_set(key, catalogs)
        return catalogs

    async def list_namespaces(self, catalog: str) -> list[NamespaceEntry]:
        key = f"ns:{catalog}"
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        data = await self._get(
            f"/api/catalog/v1/{catalog}/namespaces"
        )
        namespaces = []
        for ns_parts in data.get("namespaces", []):
            # Iceberg REST returns namespaces as arrays of parts
            if isinstance(ns_parts, list):
                name = ".".join(ns_parts)
            else:
                name = str(ns_parts)
            namespaces.append(NamespaceEntry(catalog=catalog, name=name))

        self._cache_set(key, namespaces)
        return namespaces

    async def list_tables(
        self, catalog: str, namespace: str
    ) -> list[TableSummary]:
        key = f"tables:{catalog}:{namespace}"
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        ns_encoded = namespace.replace(".", "\x1f")  # Iceberg multi-level ns
        data = await self._get(
            f"/api/catalog/v1/{catalog}/namespaces/{namespace}/tables"
        )
        tables = [
            TableSummary(
                catalog=catalog,
                namespace=namespace,
                name=t.get("name", ""),
                table_type=t.get("type", "TABLE"),
            )
            for t in data.get("identifiers", [])
        ]
        self._cache_set(key, tables)
        return tables

    async def get_table_schema(
        self, catalog: str, namespace: str, table: str
    ) -> TableSchema:
        key = f"schema:{catalog}:{namespace}:{table}"
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        data = await self._get(
            f"/api/catalog/v1/{catalog}/namespaces/{namespace}/tables/{table}"
        )
        iceberg_schema = (
            data.get("metadata", {})
            .get("current-schema", data.get("metadata", {}).get("schema", {}))
        )
        fields = iceberg_schema.get("fields", [])

        # Collect partition field names for annotation
        partition_specs = (
            data.get("metadata", {})
            .get("partition-specs", [{}])[0]
            .get("fields", [])
        )
        partition_source_ids = {ps.get("source-id") for ps in partition_specs}

        columns = [
            ColumnMeta(
                name=f.get("name", ""),
                data_type=_iceberg_type_to_str(f.get("type", "unknown")),
                nullable=not f.get("required", False),
                description=f.get("doc", ""),
                partition_key=f.get("field-id") in partition_source_ids,
            )
            for f in fields
        ]
        location = data.get("metadata", {}).get("location", "")
        fmt = (
            data.get("metadata", {})
            .get("format-version", "")
        )
        schema_obj = TableSchema(
            catalog=catalog,
            namespace=namespace,
            name=table,
            columns=columns,
            location=location,
            format=f"iceberg-v{fmt}" if fmt else "iceberg",
        )
        self._cache_set(key, schema_obj)
        return schema_obj

    async def health_check(self) -> dict:
        try:
            resp = await self._http.get(
                "/api/catalog/v1/config", timeout=5, headers=self._auth_header()
            )
            if resp.status_code == 200:
                return {
                    "status": "ok",
                    "detail": f"Polaris REST catalog reachable at {self._base_url}",
                }
            return {
                "status": "error",
                "detail": f"Polaris returned HTTP {resp.status_code}",
            }
        except Exception as exc:
            return {"status": "error", "detail": str(exc)}

    @property
    def adapter_name(self) -> str:
        return "polaris_mcp"


def _iceberg_type_to_str(t: Any) -> str:
    """Convert an Iceberg type definition (may be a dict for nested types) to a
    human-readable string."""
    if isinstance(t, str):
        return t
    if isinstance(t, dict):
        type_id = t.get("type", "")
        if type_id == "struct":
            return "struct"
        if type_id == "list":
            elem = _iceberg_type_to_str(t.get("element-type", "unknown"))
            return f"array<{elem}>"
        if type_id == "map":
            k = _iceberg_type_to_str(t.get("key-type", "unknown"))
            v = _iceberg_type_to_str(t.get("value-type", "unknown"))
            return f"map<{k},{v}>"
        return type_id or "unknown"
    return str(t)
