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

// ui/src/components/query/ResultsTable.tsx

import React, { useMemo } from 'react';
import {
  Table,
  Tag,
  Space,
  Button,
  Typography,
  Alert,
  Tooltip,
  Empty,
} from 'antd';
import {
  DownloadOutlined,
  CopyOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';

interface ResultsTableProps {
  columns?: string[];
  rows?: unknown[][];
  rowCount?: number;
  executionTimeMs?: number;
  generationTimeMs?: number;
  truncated?: boolean;
  warnings?: string[];
  error?: string;
  loading?: boolean;
}

const ResultsTable: React.FC<ResultsTableProps> = ({
  columns = [],
  rows = [],
  rowCount,
  executionTimeMs,
  generationTimeMs,
  truncated = false,
  warnings = [],
  error,
  loading = false,
}) => {
  const tableColumns: ColumnsType<Record<string, unknown>> = useMemo(
    () =>
      columns.map((col) => ({
        title: col,
        dataIndex: col,
        key: col,
        ellipsis: true,
        width: 160,
        render: (val: unknown) => {
          if (val === null || val === undefined) {
            return <Typography.Text type="secondary" italic>NULL</Typography.Text>;
          }
          const s = String(val);
          if (s.length > 120) {
            return (
              <Tooltip title={s}>
                <span style={{ fontFamily: 'monospace', fontSize: 11 }}>
                  {s.slice(0, 120)}…
                </span>
              </Tooltip>
            );
          }
          return <span style={{ fontFamily: 'monospace', fontSize: 11 }}>{s}</span>;
        },
      })),
    [columns],
  );

  const dataSource = useMemo(
    () =>
      rows.map((row, idx) => {
        const obj: Record<string, unknown> = { _key: idx };
        columns.forEach((col, i) => {
          obj[col] = (row as unknown[])[i];
        });
        return obj;
      }),
    [rows, columns],
  );

  const handleCopy = () => {
    const header = columns.join('\t');
    const body = rows.map((r) => (r as unknown[]).join('\t')).join('\n');
    void navigator.clipboard.writeText(`${header}\n${body}`);
  };

  const handleDownloadCsv = () => {
    const escape = (v: unknown) => {
      const s = v === null || v === undefined ? '' : String(v);
      return s.includes(',') || s.includes('"') || s.includes('\n')
        ? `"${s.replace(/"/g, '""')}"`
        : s;
    };
    const lines = [
      columns.map(escape).join(','),
      ...rows.map((r) => (r as unknown[]).map(escape).join(',')),
    ];
    const blob = new Blob([lines.join('\n')], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'query_results.csv';
    a.click();
    URL.revokeObjectURL(url);
  };

  const statsBar = (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '6px 12px',
        borderBottom: '1px solid #f0f0f0',
        background: '#fafafa',
        flexShrink: 0,
        fontSize: 12,
        gap: 8,
      }}
    >
      <Space size={6} wrap>
        <Typography.Text strong style={{ fontSize: 12 }}>Results</Typography.Text>
        {rowCount !== undefined && (
          <Tag color="blue">{rowCount.toLocaleString()} rows</Tag>
        )}
        {truncated && (
          <Tag color="orange" icon={<WarningOutlined />}>
            Truncated
          </Tag>
        )}
        {executionTimeMs !== undefined && (
          <Tag color="default">exec: {executionTimeMs}ms</Tag>
        )}
        {generationTimeMs !== undefined && (
          <Tag color="default">gen: {generationTimeMs}ms</Tag>
        )}
      </Space>
      {columns.length > 0 && (
        <Space size={4}>
          <Tooltip title="Copy as TSV">
            <Button size="small" icon={<CopyOutlined />} onClick={handleCopy} />
          </Tooltip>
          <Tooltip title="Download CSV">
            <Button
              size="small"
              icon={<DownloadOutlined />}
              onClick={handleDownloadCsv}
            />
          </Tooltip>
        </Space>
      )}
    </div>
  );

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'hidden',
      }}
    >
      {statsBar}

      {warnings.map((w, i) => (
        <Alert
          key={i}
          message={w}
          type="warning"
          showIcon
          banner
          style={{ flexShrink: 0 }}
        />
      ))}

      {error && (
        <Alert
          message="Execution error"
          description={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>{error}</pre>}
          type="error"
          showIcon
          style={{ margin: '8px 12px', flexShrink: 0 }}
        />
      )}

      <div style={{ flex: 1, overflow: 'auto' }}>
        {!error && columns.length === 0 && !loading ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="Run a query to see results"
            style={{ marginTop: 40 }}
          />
        ) : (
          <Table
            columns={tableColumns}
            dataSource={dataSource}
            rowKey="_key"
            size="small"
            loading={loading}
            pagination={
              dataSource.length > 100
                ? { pageSize: 100, showSizeChanger: false, size: 'small' }
                : false
            }
            scroll={{ x: 'max-content' }}
            style={{ fontSize: 12 }}
            tableLayout="fixed"
          />
        )}
      </div>
    </div>
  );
};

export default ResultsTable;
