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

// ui/src/pages/QueryPage.tsx

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Button,
  Select,
  Input,
  Space,
  Tooltip,
  Typography,
  Divider,
  Tag,
  Spin,
  message,
  Splitter,
} from 'antd';
import {
  ThunderboltOutlined,
  PlayCircleOutlined,
  CopyOutlined,
  ClearOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  BulbOutlined,
} from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import SchemaBrowser from '../components/schema/SchemaBrowser';
import ResultsTable from '../components/query/ResultsTable';
import { generateSql, executeSql, listCatalogs, listNamespaces } from '../api/client';
import type { NlToSqlResponse, ExecuteResponse, SqlDialect } from '../types';
import './Page.css';

const { TextArea } = Input;
const { Option } = Select;
const { Text } = Typography;

const DIALECT_OPTIONS: { value: SqlDialect; label: string }[] = [
  { value: 'trino', label: 'Trino' },
  { value: 'hive', label: 'Hive' },
];

const QueryPage: React.FC = () => {
  // ── Selection state ───────────────────────────────────────────────────────
  const [dialect, setDialect] = useState<SqlDialect>('trino');
  const [catalogs, setCatalogs] = useState<string[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [selectedCatalog, setSelectedCatalog] = useState<string | undefined>();
  const [selectedNamespace, setSelectedNamespace] = useState<string | undefined>();

  // ── Prompt / SQL ─────────────────────────────────────────────────────────
  const [question, setQuestion] = useState('');
  const [sql, setSql] = useState('-- Generated SQL will appear here\n');
  const [generationTime, setGenerationTime] = useState<number | undefined>();

  // ── Results ───────────────────────────────────────────────────────────────
  const [results, setResults] = useState<NlToSqlResponse | ExecuteResponse | null>(null);
  const [resultError, setResultError] = useState<string | undefined>();

  // ── Loading states ────────────────────────────────────────────────────────
  const [generating, setGenerating] = useState(false);
  const [executing, setExecuting] = useState(false);

  const promptRef = useRef<HTMLTextAreaElement>(null);

  // ── Load catalogs / namespaces ────────────────────────────────────────────

  useEffect(() => {
    listCatalogs()
      .then((d) => setCatalogs(d.catalogs.map((c) => c.name)))
      .catch(() => { /* ignore – schema browser will show error */ });
  }, []);

  useEffect(() => {
    if (!selectedCatalog) { setNamespaces([]); return; }
    listNamespaces(selectedCatalog)
      .then((d) => setNamespaces(d.namespaces.map((n) => n.name)))
      .catch(() => setNamespaces([]));
  }, [selectedCatalog]);

  // ── Generate SQL ─────────────────────────────────────────────────────────

  const handleGenerate = useCallback(async () => {
    if (!question.trim()) {
      void message.warning('Please enter a question first.');
      return;
    }
    setGenerating(true);
    setResultError(undefined);
    try {
      const res = await generateSql({
        question,
        dialect,
        catalog: selectedCatalog,
        namespace: selectedNamespace,
        execute: false,
      });
      setSql(res.sql);
      setGenerationTime(res.generation_time_ms);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      void message.error(`Generation failed: ${msg}`);
    } finally {
      setGenerating(false);
    }
  }, [question, dialect, selectedCatalog, selectedNamespace]);

  // ── Execute SQL ───────────────────────────────────────────────────────────

  const handleExecute = useCallback(async () => {
    const sqlToRun = sql.trim();
    if (!sqlToRun || sqlToRun.startsWith('--')) {
      void message.warning('No valid SQL to execute. Generate SQL first.');
      return;
    }
    setExecuting(true);
    setResults(null);
    setResultError(undefined);
    try {
      const res = await executeSql({
        sql: sqlToRun,
        dialect,
        catalog: selectedCatalog,
        namespace: selectedNamespace,
      });
      setResults(res);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setResultError(msg);
    } finally {
      setExecuting(false);
    }
  }, [sql, dialect, selectedCatalog, selectedNamespace]);

  // ── Generate + Execute in one click ──────────────────────────────────────

  const handleGenerateAndRun = useCallback(async () => {
    if (!question.trim()) {
      void message.warning('Please enter a question first.');
      return;
    }
    setGenerating(true);
    setResults(null);
    setResultError(undefined);
    try {
      const res = await generateSql({
        question,
        dialect,
        catalog: selectedCatalog,
        namespace: selectedNamespace,
        execute: true,
      });
      setSql(res.sql);
      setGenerationTime(res.generation_time_ms);
      setResults(res);
      if (res.error) setResultError(res.error);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      void message.error(`Failed: ${msg}`);
    } finally {
      setGenerating(false);
    }
  }, [question, dialect, selectedCatalog, selectedNamespace]);

  // ── Keyboard shortcuts ────────────────────────────────────────────────────

  const handlePromptKeyDown = (e: React.KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      void handleGenerateAndRun();
    }
  };

  // ── Helpers ───────────────────────────────────────────────────────────────

  const handleCopySql = () => {
    void navigator.clipboard.writeText(sql);
    void message.success('SQL copied');
  };

  const handleTableSelect = (catalog: string, ns: string, _table: string) => {
    setSelectedCatalog(catalog);
    setSelectedNamespace(ns);
  };

  const resultColumns = results && 'columns' in results ? results.columns ?? [] : [];
  const resultRows = results && 'rows' in results ? results.rows ?? [] : [];
  const resultRowCount = results && 'row_count' in results ? results.row_count ?? undefined : undefined;
  const resultExecTime = results && 'execution_time_ms' in results ? results.execution_time_ms ?? undefined : undefined;
  const resultTruncated = results && 'truncated' in results ? results.truncated : false;
  const resultWarnings = results && 'warnings' in results ? results.warnings : [];

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* ── Schema sidebar ───────────────────────────────────────── */}
      <Splitter style={{ height: '100%' }}>
        <Splitter.Panel
          defaultSize="22%"
          min="180px"
          max="35%"
          style={{ borderRight: '1px solid #f0f0f0', overflow: 'hidden' }}
        >
          <SchemaBrowser
            onTableSelect={handleTableSelect}
            selectedCatalog={selectedCatalog}
            selectedNamespace={selectedNamespace}
          />
        </Splitter.Panel>

        {/* ── Main workbench ───────────────────────────────────────── */}
        <Splitter.Panel style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

          {/* ── Toolbar row ─────────────────────────────────── */}
          <div
            className="workbench-toolbar"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '8px 12px',
              borderBottom: '1px solid #f0f0f0',
              background: '#fafafa',
              flexShrink: 0,
              flexWrap: 'wrap',
            }}
          >
            <Text strong style={{ fontSize: 12 }}>Engine:</Text>
            <Select
              value={dialect}
              onChange={(v) => setDialect(v)}
              size="small"
              style={{ width: 90 }}
            >
              {DIALECT_OPTIONS.map((o) => (
                <Option key={o.value} value={o.value}>{o.label}</Option>
              ))}
            </Select>

            <Divider type="vertical" />
            <DatabaseOutlined style={{ color: '#8c8c8c' }} />
            <Select
              placeholder="Catalog"
              value={selectedCatalog}
              onChange={(v) => { setSelectedCatalog(v); setSelectedNamespace(undefined); }}
              size="small"
              style={{ width: 130 }}
              allowClear
              showSearch
            >
              {catalogs.map((c) => <Option key={c} value={c}>{c}</Option>)}
            </Select>
            <Select
              placeholder="Namespace"
              value={selectedNamespace}
              onChange={setSelectedNamespace}
              size="small"
              style={{ width: 140 }}
              allowClear
              showSearch
              disabled={!selectedCatalog}
            >
              {namespaces.map((n) => <Option key={n} value={n}>{n}</Option>)}
            </Select>
          </div>

          {/* ── Vertical splitter: prompt+editor | results ─── */}
          <Splitter layout="vertical" style={{ flex: 1, minHeight: 0 }}>

            {/* ── Top half: prompt + SQL editor ──────────── */}
            <Splitter.Panel defaultSize="55%" min="30%">
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  height: '100%',
                  overflow: 'hidden',
                }}
              >
                {/* Prompt input */}
                <div
                  style={{
                    padding: '10px 12px 8px',
                    borderBottom: '1px solid #f0f0f0',
                    flexShrink: 0,
                  }}
                >
                  <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                    <TextArea
                      ref={promptRef}
                      value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      onKeyDown={handlePromptKeyDown}
                      placeholder="Ask a question in natural language… (Ctrl+Enter to generate and run)"
                      autoSize={{ minRows: 2, maxRows: 5 }}
                      style={{ flex: 1, fontFamily: 'inherit', fontSize: 13, resize: 'none' }}
                    />
                    <div
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 4,
                        flexShrink: 0,
                      }}
                    >
                      <Tooltip title="Generate SQL (Ctrl+Enter)">
                        <Button
                          type="default"
                          icon={<BulbOutlined />}
                          onClick={() => { void handleGenerate(); }}
                          loading={generating && !executing}
                          size="small"
                          style={{ width: 120 }}
                        >
                          Generate SQL
                        </Button>
                      </Tooltip>
                      <Tooltip title="Generate and run (Ctrl+Enter)">
                        <Button
                          type="primary"
                          icon={<ThunderboltOutlined />}
                          onClick={() => { void handleGenerateAndRun(); }}
                          loading={generating}
                          size="small"
                          style={{ width: 120 }}
                        >
                          Generate &amp; Run
                        </Button>
                      </Tooltip>
                    </div>
                  </div>
                  {generationTime !== undefined && (
                    <Text type="secondary" style={{ fontSize: 11, marginTop: 4, display: 'block' }}>
                      Generated in {generationTime}ms
                    </Text>
                  )}
                </div>

                {/* SQL Editor toolbar */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                    padding: '5px 12px',
                    borderBottom: '1px solid #f0f0f0',
                    background: '#fafafa',
                    flexShrink: 0,
                  }}
                >
                  <Text strong style={{ fontSize: 11 }}>SQL Editor</Text>
                  <div style={{ flex: 1 }} />
                  <Space size={4}>
                    <Tooltip title="Run SQL (current editor contents)">
                      <Button
                        size="small"
                        type="primary"
                        ghost
                        icon={<PlayCircleOutlined />}
                        onClick={() => { void handleExecute(); }}
                        loading={executing}
                      >
                        Run
                      </Button>
                    </Tooltip>
                    <Tooltip title="Copy SQL">
                      <Button
                        size="small"
                        icon={<CopyOutlined />}
                        onClick={handleCopySql}
                      />
                    </Tooltip>
                    <Tooltip title="Clear editor">
                      <Button
                        size="small"
                        icon={<ClearOutlined />}
                        onClick={() => setSql('')}
                      />
                    </Tooltip>
                  </Space>
                </div>

                {/* Monaco editor */}
                <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
                  {generating && (
                    <div
                      style={{
                        position: 'absolute',
                        inset: 0,
                        background: 'rgba(255,255,255,0.7)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        zIndex: 10,
                      }}
                    >
                      <Spin tip="Generating SQL…" />
                    </div>
                  )}
                  <Editor
                    height="100%"
                    language="sql"
                    value={sql}
                    onChange={(v) => setSql(v ?? '')}
                    theme="vs"
                    options={{
                      fontSize: 13,
                      minimap: { enabled: false },
                      scrollBeyondLastLine: false,
                      wordWrap: 'on',
                      renderLineHighlight: 'line',
                      lineNumbers: 'on',
                      folding: false,
                      padding: { top: 8, bottom: 8 },
                      fontFamily: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
                    }}
                  />
                </div>
              </div>
            </Splitter.Panel>

            {/* ── Bottom half: results ─────────────────────── */}
            <Splitter.Panel min="20%" style={{ overflow: 'hidden' }}>
              <ResultsTable
                columns={resultColumns as string[]}
                rows={resultRows as unknown[][]}
                rowCount={resultRowCount}
                executionTimeMs={resultExecTime}
                generationTimeMs={generationTime}
                truncated={resultTruncated}
                warnings={resultWarnings}
                error={resultError}
                loading={executing}
              />
            </Splitter.Panel>

          </Splitter>
        </Splitter.Panel>
      </Splitter>
    </div>
  );
};

export default QueryPage;
