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

// ui/src/types/index.ts

export type SqlDialect = 'trino' | 'hive';
export type LlmAdapter = 'ollama' | 'anthropic' | 'openai';
export type QueryStatus = 'idle' | 'generating' | 'executing' | 'success' | 'error';
export type HealthStatus = 'ok' | 'degraded' | 'error' | 'loading';

// ── Schema ──────────────────────────────────────────────────────────────────

export interface CatalogEntry {
  name: string;
  description?: string;
}

export interface NamespaceEntry {
  name: string;
}

export interface TableSummary {
  name: string;
  type?: string;
  description?: string;
}

export interface ColumnMeta {
  name: string;
  data_type: string;
  nullable: boolean;
  description?: string;
  partition_key?: boolean;
}

export interface TableSchema {
  catalog: string;
  namespace: string;
  name: string;
  columns: ColumnMeta[];
  description?: string;
  format?: string;
  location?: string;
}

// ── Query ────────────────────────────────────────────────────────────────────

export interface NlToSqlRequest {
  question: string;
  dialect: SqlDialect;
  catalog?: string;
  namespace?: string;
  execute: boolean;
  user_name?: string;
}

export interface NlToSqlResponse {
  request_id: string;
  sql: string;
  model: string;
  adapter: string;
  dialect: string;
  generation_time_ms: number;
  execution_time_ms?: number;
  columns?: string[];
  rows?: unknown[][];
  row_count?: number;
  truncated: boolean;
  warnings: string[];
  error?: string;
}

export interface ExecuteRequest {
  sql: string;
  dialect: SqlDialect;
  catalog?: string;
  namespace?: string;
}

export interface ExecuteResponse {
  columns: string[];
  rows: unknown[][];
  row_count: number;
  execution_time_ms: number;
  truncated: boolean;
  warnings: string[];
}

// ── History ──────────────────────────────────────────────────────────────────

export interface HistoryEntry {
  id: string;
  created_at: string;
  question: string;
  sql: string;
  dialect: SqlDialect;
  catalog?: string;
  namespace?: string;
  model?: string;
  status: 'success' | 'error';
  error?: string;
  generation_time_ms: number;
  execution_time_ms: number;
  row_count: number;
}

export interface HistoryListResponse {
  items: HistoryEntry[];
  total: number;
}

// ── Health / Config ───────────────────────────────────────────────────────────

export interface ComponentHealth {
  status: 'ok' | 'error';
  detail: string;
  available_models?: string[];
}

export interface HealthResponse {
  status: HealthStatus;
  components: Record<string, ComponentHealth>;
}

export interface ServiceConfig {
  llm_adapter: LlmAdapter;
  llm_model: string;
  catalog_adapter: string;
  polaris_url: string;
  trino_host: string;
  trino_port: number;
  hive_host: string;
  hive_port: number;
}

export interface ViewConfig {
  serviceUrl: string;
  configured: boolean;
}
