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
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Literal

from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from config import settings
from adapters.llm.base import LLMAdapter, SchemaContext, TableContext, ColumnContext
from adapters.sql.base import SQLExecutor
from adapters.catalog.base import CatalogAdapter
from history.store import HistoryStore, HistoryEntry

# ── Logging ──────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=settings.log_level.upper(),
    format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
)
logger = logging.getLogger("sql_assistant")

# ── Adapter factory ───────────────────────────────────────────────────────────

def _build_llm_adapter() -> LLMAdapter:
    if settings.llm_adapter == "ollama":
        from adapters.llm.ollama import OllamaAdapter
        return OllamaAdapter(
            base_url=settings.ollama_base_url,
            model=settings.ollama_model,
            timeout_seconds=settings.ollama_timeout_seconds,
            num_ctx=settings.ollama_num_ctx,
        )
    if settings.llm_adapter == "anthropic":
        from adapters.llm.anthropic import AnthropicAdapter
        return AnthropicAdapter(
            api_key=settings.anthropic_api_key,
            model=settings.anthropic_model,
            max_tokens=settings.anthropic_max_tokens,
        )
    raise ValueError(f"Unknown llm_adapter: {settings.llm_adapter}")


def _build_sql_executors() -> dict[str, SQLExecutor]:
    from adapters.sql.trino import TrinoExecutor
    from adapters.sql.hive import HiveExecutor
    return {
        "trino": TrinoExecutor(
            host=settings.trino_host,
            port=settings.trino_port,
            user=settings.trino_user,
            password=settings.trino_password,
            http_scheme=settings.trino_http_scheme,
        ),
        "hive": HiveExecutor(
            host=settings.hive_host,
            port=settings.hive_port,
            user=settings.hive_user,
            password=settings.hive_password,
            auth=settings.hive_auth,
            kerberos_service_name=settings.hive_kerberos_service_name,
        ),
    }


def _build_catalog_adapter(executors: dict[str, SQLExecutor]) -> CatalogAdapter:
    if settings.catalog_adapter == "polaris_mcp":
        from adapters.catalog.polaris_mcp import PolarisMCPAdapter
        return PolarisMCPAdapter(
            base_url=settings.polaris_mcp_url,
            token=settings.polaris_mcp_token,
            client_id=settings.polaris_mcp_client_id,
            client_secret=settings.polaris_mcp_client_secret,
            cache_ttl_seconds=settings.polaris_schema_cache_ttl_seconds,
        )
    # fallback to information_schema via Trino
    from adapters.catalog.info_schema import InformationSchemaAdapter
    return InformationSchemaAdapter(executors["trino"])


# ── App state ─────────────────────────────────────────────────────────────────

class AppState:
    llm: LLMAdapter
    executors: dict[str, SQLExecutor]
    catalog: CatalogAdapter
    history: HistoryStore


app_state = AppState()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("SQL Assistant service starting …")
    app_state.executors = _build_sql_executors()
    app_state.llm = _build_llm_adapter()
    app_state.catalog = _build_catalog_adapter(app_state.executors)
    app_state.history = HistoryStore(
        db_path=settings.history_db_path,
        max_entries=settings.history_max_entries,
    )
    await app_state.history.init()
    logger.info(
        "Adapters loaded: llm=%s  catalog=%s",
        app_state.llm.adapter_name,
        app_state.catalog.adapter_name,
    )
    yield
    logger.info("SQL Assistant service stopping.")


app = FastAPI(
    title="SQL Assistant Semantic Service",
    version="1.0.0",
    description="Natural-language to SQL powered by Polaris MCP catalog + LLM",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request ID middleware ─────────────────────────────────────────────────────

@app.middleware("http")
async def add_request_id(request: Request, call_next):
    request_id = request.headers.get("X-Request-ID", str(uuid.uuid4()))
    response: Response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    return response


# ── Pydantic models ───────────────────────────────────────────────────────────

class NlToSqlRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=4096)
    dialect: Literal["trino", "hive"] = "trino"
    catalog: str | None = None
    namespace: str | None = None
    execute: bool = False
    user_name: str | None = None


class ColumnInfo(BaseModel):
    name: str
    data_type: str
    nullable: bool = True
    description: str = ""
    partition_key: bool = False


class TableSchemaResponse(BaseModel):
    catalog: str
    namespace: str
    name: str
    columns: list[ColumnInfo]
    description: str = ""
    format: str = ""
    location: str = ""


class NlToSqlResponse(BaseModel):
    request_id: str
    sql: str
    model: str
    adapter: str
    dialect: str
    generation_time_ms: int
    execution_time_ms: int | None = None
    columns: list[str] | None = None
    rows: list[list] | None = None
    row_count: int | None = None
    truncated: bool = False
    warnings: list[str] = []
    error: str | None = None


class HealthStatus(BaseModel):
    status: Literal["ok", "degraded", "error"]
    components: dict[str, Any]


class HistoryEntryResponse(BaseModel):
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


class HistoryListResponse(BaseModel):
    items: list[HistoryEntryResponse]
    total: int


# ── Routes ───────────────────────────────────────────────────────────────────

@app.get("/health", response_model=HealthStatus, tags=["ops"])
async def health():
    """Detailed health check – probes each adapter component."""
    results: dict[str, Any] = {}
    results["llm"] = await app_state.llm.health_check()
    results["catalog"] = await app_state.catalog.health_check()
    for name, exc in app_state.executors.items():
        results[f"sql_{name}"] = await exc.health_check()

    overall = "ok"
    for v in results.values():
        if v.get("status") == "error":
            overall = "degraded"
            break
    return HealthStatus(status=overall, components=results)


@app.post("/api/v1/nl-to-sql", response_model=NlToSqlResponse, tags=["query"])
async def nl_to_sql(req: NlToSqlRequest, request: Request):
    """
    Translate a natural-language question to SQL and optionally execute it.
    """
    request_id = request.headers.get("X-Request-ID", str(uuid.uuid4()))
    logger.info(
        "nl_to_sql question=%r dialect=%s execute=%s",
        req.question[:80],
        req.dialect,
        req.execute,
    )

    # 1. Fetch schema context
    schema_ctx = SchemaContext(
        catalog=req.catalog or "",
        namespace=req.namespace or "",
    )
    try:
        if req.catalog and req.namespace:
            tables_meta = await app_state.catalog.list_tables(
                req.catalog, req.namespace
            )
            for t in tables_meta[:30]:  # cap to avoid oversized prompts
                try:
                    ts = await app_state.catalog.get_table_schema(
                        req.catalog, req.namespace, t.name
                    )
                    schema_ctx.tables.append(
                        TableContext(
                            name=t.name,
                            description=ts.description,
                            columns=[
                                ColumnContext(
                                    name=c.name,
                                    data_type=c.data_type,
                                    nullable=c.nullable,
                                    description=c.description,
                                )
                                for c in ts.columns
                            ],
                        )
                    )
                except Exception:
                    schema_ctx.tables.append(TableContext(name=t.name))
    except Exception as exc:
        logger.warning("Schema fetch failed: %s", exc)

    # 2. Generate SQL
    try:
        llm_result = await app_state.llm.generate_sql(
            question=req.question,
            schema=schema_ctx,
            dialect=req.dialect,
            request_id=request_id,
        )
    except Exception as exc:
        logger.error("LLM generation failed: %s", exc)
        raise HTTPException(status_code=502, detail=f"LLM error: {exc}") from exc

    # 3. Optionally execute
    exec_time = None
    columns = None
    rows = None
    row_count = None
    truncated = False
    warnings: list[str] = []
    exec_error: str | None = None

    if req.execute and llm_result.sql and not llm_result.sql.startswith("--"):
        executor = app_state.executors.get(req.dialect)
        if executor:
            try:
                qr = await executor.execute(
                    sql=llm_result.sql,
                    catalog=req.catalog,
                    schema=req.namespace,
                    timeout_seconds=settings.query_timeout_seconds,
                    max_rows=settings.max_result_rows,
                )
                exec_time = qr.execution_time_ms
                columns = qr.columns
                rows = qr.rows
                row_count = qr.row_count
                truncated = qr.truncated
                warnings = qr.warnings
            except Exception as exc:
                exec_error = str(exc)
                logger.warning("SQL execution failed: %s", exc)

    # 4. Persist to history
    entry = HistoryEntry(
        id=request_id,
        created_at=datetime.now(tz=timezone.utc).isoformat(),
        question=req.question,
        sql=llm_result.sql,
        dialect=req.dialect,
        catalog=req.catalog,
        namespace=req.namespace,
        model=llm_result.model,
        status="error" if exec_error else "success",
        error=exec_error,
        generation_time_ms=llm_result.generation_time_ms,
        execution_time_ms=exec_time or 0,
        row_count=row_count or 0,
        user_name=req.user_name,
    )
    try:
        await app_state.history.save(entry)
    except Exception as exc:
        logger.warning("History save failed (non-fatal): %s", exc)

    return NlToSqlResponse(
        request_id=request_id,
        sql=llm_result.sql,
        model=llm_result.model,
        adapter=llm_result.adapter,
        dialect=req.dialect,
        generation_time_ms=llm_result.generation_time_ms,
        execution_time_ms=exec_time,
        columns=columns,
        rows=rows,
        row_count=row_count,
        truncated=truncated,
        warnings=warnings,
        error=exec_error,
    )


@app.post("/api/v1/execute", tags=["query"])
async def execute_sql(
    body: dict,
    request: Request,
):
    """Execute an arbitrary SQL statement (user-edited SQL)."""
    sql = body.get("sql", "").strip()
    dialect = body.get("dialect", "trino")
    catalog = body.get("catalog")
    namespace = body.get("namespace")

    if not sql:
        raise HTTPException(status_code=400, detail="sql is required")

    executor = app_state.executors.get(dialect)
    if not executor:
        raise HTTPException(
            status_code=400, detail=f"Unknown dialect: {dialect}"
        )
    try:
        qr = await executor.execute(
            sql=sql,
            catalog=catalog,
            schema=namespace,
            timeout_seconds=settings.query_timeout_seconds,
            max_rows=settings.max_result_rows,
        )
    except TimeoutError as exc:
        raise HTTPException(status_code=408, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {
        "columns": qr.columns,
        "rows": qr.rows,
        "row_count": qr.row_count,
        "execution_time_ms": qr.execution_time_ms,
        "truncated": qr.truncated,
        "warnings": qr.warnings,
    }


@app.get("/api/v1/schema/catalogs", tags=["schema"])
async def list_catalogs():
    items = await app_state.catalog.list_catalogs()
    return {"catalogs": [{"name": c.name, "description": c.description} for c in items]}


@app.get("/api/v1/schema/catalogs/{catalog}/namespaces", tags=["schema"])
async def list_namespaces(catalog: str):
    items = await app_state.catalog.list_namespaces(catalog)
    return {"namespaces": [{"name": n.name} for n in items]}


@app.get("/api/v1/schema/catalogs/{catalog}/namespaces/{namespace}/tables", tags=["schema"])
async def list_tables(catalog: str, namespace: str):
    items = await app_state.catalog.list_tables(catalog, namespace)
    return {
        "tables": [
            {"name": t.name, "type": t.table_type, "description": t.description}
            for t in items
        ]
    }


@app.get(
    "/api/v1/schema/catalogs/{catalog}/namespaces/{namespace}/tables/{table}",
    response_model=TableSchemaResponse,
    tags=["schema"],
)
async def get_table_schema(catalog: str, namespace: str, table: str):
    ts = await app_state.catalog.get_table_schema(catalog, namespace, table)
    return TableSchemaResponse(
        catalog=ts.catalog,
        namespace=ts.namespace,
        name=ts.name,
        columns=[
            ColumnInfo(
                name=c.name,
                data_type=c.data_type,
                nullable=c.nullable,
                description=c.description,
                partition_key=c.partition_key,
            )
            for c in ts.columns
        ],
        description=ts.description,
        format=ts.format,
        location=ts.location,
    )


@app.post("/api/v1/schema/refresh", tags=["schema"])
async def refresh_schema():
    """Invalidate the schema cache so next requests re-fetch from Polaris."""
    if hasattr(app_state.catalog, "invalidate"):
        app_state.catalog.invalidate()
    return {"status": "ok", "message": "Schema cache invalidated."}


@app.get("/api/v1/history", response_model=HistoryListResponse, tags=["history"])
async def list_history(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    search: str | None = Query(None),
    dialect: str | None = Query(None),
):
    items, total = await app_state.history.list(
        limit=limit, offset=offset, search=search, dialect=dialect
    )
    return HistoryListResponse(
        items=[_entry_to_response(e) for e in items],
        total=total,
    )


@app.delete("/api/v1/history/{entry_id}", tags=["history"])
async def delete_history_entry(entry_id: str):
    deleted = await app_state.history.delete(entry_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Entry not found")
    return {"status": "ok"}


@app.delete("/api/v1/history", tags=["history"])
async def clear_history():
    await app_state.history.clear()
    return {"status": "ok"}


@app.get("/api/v1/config", tags=["config"])
async def get_config():
    """Return current adapter configuration (no secrets)."""
    return {
        "llm_adapter": settings.llm_adapter,
        "llm_model": app_state.llm.model_name,
        "catalog_adapter": settings.catalog_adapter,
        "polaris_url": settings.polaris_mcp_url,
        "trino_host": settings.trino_host,
        "trino_port": settings.trino_port,
        "hive_host": settings.hive_host,
        "hive_port": settings.hive_port,
    }


# ── Helpers ───────────────────────────────────────────────────────────────────

def _entry_to_response(e: HistoryEntry) -> HistoryEntryResponse:
    return HistoryEntryResponse(
        id=e.id,
        created_at=e.created_at,
        question=e.question,
        sql=e.sql,
        dialect=e.dialect,
        catalog=e.catalog,
        namespace=e.namespace,
        model=e.model,
        status=e.status,
        error=e.error,
        generation_time_ms=e.generation_time_ms,
        execution_time_ms=e.execution_time_ms,
        row_count=e.row_count,
    )


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
        reload=False,
    )
