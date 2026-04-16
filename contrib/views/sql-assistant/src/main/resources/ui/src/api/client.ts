/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// ui/src/api/client.ts

import type {
  CatalogEntry,
  NamespaceEntry,
  TableSummary,
  TableSchema,
  NlToSqlRequest,
  NlToSqlResponse,
  ExecuteRequest,
  ExecuteResponse,
  HistoryListResponse,
  HealthResponse,
  ServiceConfig,
  ViewConfig,
} from '../types';

/**
 * Resolve the Ambari view API base URL dynamically from the page URL so
 * version or instance name changes in view.xml never break the frontend.
 * Falls back to a dev-mode default when running outside Ambari.
 */
const resolveApiBase = (): string => {
  const fallback = `/api/v1/views/SQL-ASSISTANT-VIEW/versions/1.0.0.0/instances/SQL_ASSISTANT_INSTANCE/resources/api`;
  if (typeof window === 'undefined') return fallback;
  try {
    const path = window.location.pathname + window.location.hash;
    const match = path.match(/views\/([^/]+)\/([^/]+)\/([^/#?]+)/);
    if (!match) return fallback;
    const [, viewName, version, instance] = match;
    return `/api/v1/views/${viewName}/versions/${version}/instances/${instance}/resources/api`;
  } catch {
    return fallback;
  }
};

export const API_BASE = resolveApiBase();

// ── HTTP primitives ───────────────────────────────────────────────────────────

const HEADERS = { 'X-Requested-By': 'ambari', 'Content-Type': 'application/json' };

async function fetchJson<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const url = `${API_BASE}/${path}`;
  const resp = await fetch(url, { ...options, headers: { ...HEADERS, ...options.headers } });
  const text = await resp.text();
  if (!resp.ok) {
    let detail = text;
    try {
      const parsed = JSON.parse(text) as { error?: string; detail?: string };
      detail = parsed.error ?? parsed.detail ?? text;
    } catch { /* keep raw text */ }
    throw new ApiError(resp.status, detail);
  }
  return text ? (JSON.parse(text) as T) : ({} as T);
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ── NL-to-SQL ─────────────────────────────────────────────────────────────────

export const generateSql = (req: NlToSqlRequest): Promise<NlToSqlResponse> =>
  fetchJson('nl-to-sql', { method: 'POST', body: JSON.stringify(req) });

export const executeSql = (req: ExecuteRequest): Promise<ExecuteResponse> =>
  fetchJson('execute', { method: 'POST', body: JSON.stringify(req) });

// ── Schema ────────────────────────────────────────────────────────────────────

export const listCatalogs = (): Promise<{ catalogs: CatalogEntry[] }> =>
  fetchJson('schema/catalogs');

export const listNamespaces = (
  catalog: string,
): Promise<{ namespaces: NamespaceEntry[] }> =>
  fetchJson(`schema/catalogs/${encodeURIComponent(catalog)}/namespaces`);

export const listTables = (
  catalog: string,
  namespace: string,
): Promise<{ tables: TableSummary[] }> =>
  fetchJson(
    `schema/catalogs/${encodeURIComponent(catalog)}/namespaces/${encodeURIComponent(namespace)}/tables`,
  );

export const getTableSchema = (
  catalog: string,
  namespace: string,
  table: string,
): Promise<TableSchema> =>
  fetchJson(
    `schema/catalogs/${encodeURIComponent(catalog)}/namespaces/${encodeURIComponent(namespace)}/tables/${encodeURIComponent(table)}`,
  );

export const refreshSchema = (): Promise<{ status: string }> =>
  fetchJson('schema/refresh', { method: 'POST', body: '{}' });

// ── History ───────────────────────────────────────────────────────────────────

export const listHistory = (
  params: { limit?: number; offset?: number; search?: string; dialect?: string } = {},
): Promise<HistoryListResponse> => {
  const qs = new URLSearchParams();
  if (params.limit !== undefined) qs.set('limit', String(params.limit));
  if (params.offset !== undefined) qs.set('offset', String(params.offset));
  if (params.search) qs.set('search', params.search);
  if (params.dialect) qs.set('dialect', params.dialect);
  const query = qs.toString();
  return fetchJson(`history${query ? '?' + query : ''}`);
};

export const deleteHistoryEntry = (id: string): Promise<{ status: string }> =>
  fetchJson(`history/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const clearHistory = (): Promise<{ status: string }> =>
  fetchJson('history', { method: 'DELETE' });

// ── Health / Config ───────────────────────────────────────────────────────────

export const getHealth = (): Promise<HealthResponse> =>
  fetchJson('health');

export const getConfig = (): Promise<ServiceConfig> =>
  fetchJson('config');

export const getViewConfig = (): Promise<ViewConfig> =>
  fetchJson('view-config');
