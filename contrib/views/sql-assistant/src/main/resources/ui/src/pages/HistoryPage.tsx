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

// ui/src/pages/HistoryPage.tsx

import React, { useCallback, useEffect, useState } from 'react';
import {
  Table,
  Input,
  Select,
  Button,
  Tag,
  Space,
  Typography,
  Popconfirm,
  Tooltip,
  Drawer,
  message,
} from 'antd';
import {
  SearchOutlined,
  DeleteOutlined,
  ReloadOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { listHistory, deleteHistoryEntry, clearHistory } from '../api/client';
import type { HistoryEntry, SqlDialect } from '../types';
import './Page.css';

dayjs.extend(relativeTime);

const { Search } = Input;
const { Option } = Select;

const PAGE_SIZE = 50;

const HistoryPage: React.FC = () => {
  const [items, setItems] = useState<HistoryEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [dialectFilter, setDialectFilter] = useState<SqlDialect | undefined>();
  const [drawerEntry, setDrawerEntry] = useState<HistoryEntry | null>(null);

  const load = useCallback(
    async (p: number, s: string, d: SqlDialect | undefined) => {
      setLoading(true);
      try {
        const res = await listHistory({
          limit: PAGE_SIZE,
          offset: (p - 1) * PAGE_SIZE,
          search: s || undefined,
          dialect: d,
        });
        setItems(res.items);
        setTotal(res.total);
      } catch {
        void message.error('Failed to load history');
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    void load(page, search, dialectFilter);
  }, [load, page, search, dialectFilter]);

  const handleDelete = async (id: string) => {
    try {
      await deleteHistoryEntry(id);
      void message.success('Entry deleted');
      void load(page, search, dialectFilter);
    } catch {
      void message.error('Failed to delete entry');
    }
  };

  const handleClearAll = async () => {
    try {
      await clearHistory();
      void message.success('History cleared');
      setPage(1);
      void load(1, '', undefined);
    } catch {
      void message.error('Failed to clear history');
    }
  };

  const columns: ColumnsType<HistoryEntry> = [
    {
      title: 'Time',
      dataIndex: 'created_at',
      key: 'created_at',
      width: 120,
      render: (v: string) => (
        <Tooltip title={dayjs(v).format('YYYY-MM-DD HH:mm:ss')}>
          <Typography.Text style={{ fontSize: 12 }}>{dayjs(v).fromNow()}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: 'Question',
      dataIndex: 'question',
      key: 'question',
      ellipsis: true,
      render: (v: string) => (
        <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>
      ),
    },
    {
      title: 'Dialect',
      dataIndex: 'dialect',
      key: 'dialect',
      width: 70,
      render: (v: string) => (
        <Tag color={v === 'trino' ? 'blue' : 'orange'} style={{ fontSize: 11 }}>{v}</Tag>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (v: string) => (
        <Tag color={v === 'success' ? 'green' : 'red'} style={{ fontSize: 11 }}>{v}</Tag>
      ),
    },
    {
      title: 'Rows',
      dataIndex: 'row_count',
      key: 'row_count',
      width: 70,
      render: (v: number) => (
        <Typography.Text style={{ fontSize: 12 }}>{v?.toLocaleString() ?? '—'}</Typography.Text>
      ),
    },
    {
      title: 'Gen (ms)',
      dataIndex: 'generation_time_ms',
      key: 'generation_time_ms',
      width: 80,
      render: (v: number) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>,
    },
    {
      title: 'Exec (ms)',
      dataIndex: 'execution_time_ms',
      key: 'execution_time_ms',
      width: 80,
      render: (v: number) => <Typography.Text style={{ fontSize: 12 }}>{v || '—'}</Typography.Text>,
    },
    {
      title: '',
      key: 'actions',
      width: 70,
      render: (_: unknown, record: HistoryEntry) => (
        <Space size={4}>
          <Tooltip title="View details">
            <Button
              size="small"
              type="text"
              icon={<EyeOutlined />}
              onClick={() => setDrawerEntry(record)}
            />
          </Tooltip>
          <Popconfirm
            title="Delete this entry?"
            onConfirm={() => { void handleDelete(record.id); }}
            okText="Delete"
            okType="danger"
          >
            <Button size="small" type="text" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 16, height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Toolbar */}
      <div
        style={{
          display: 'flex',
          gap: 8,
          marginBottom: 12,
          alignItems: 'center',
          flexWrap: 'wrap',
          flexShrink: 0,
        }}
      >
        <Typography.Title level={5} style={{ margin: 0 }}>
          Query History
        </Typography.Title>
        <div style={{ flex: 1 }} />
        <Search
          placeholder="Search questions / SQL…"
          allowClear
          size="small"
          style={{ width: 260 }}
          onSearch={(v) => { setSearch(v); setPage(1); }}
          prefix={<SearchOutlined />}
        />
        <Select
          placeholder="Dialect"
          allowClear
          size="small"
          style={{ width: 100 }}
          onChange={(v) => { setDialectFilter(v as SqlDialect | undefined); setPage(1); }}
        >
          <Option value="trino">Trino</Option>
          <Option value="hive">Hive</Option>
        </Select>
        <Button
          size="small"
          icon={<ReloadOutlined />}
          onClick={() => { void load(page, search, dialectFilter); }}
        >
          Refresh
        </Button>
        <Popconfirm
          title="Clear all history?"
          description="This action cannot be undone."
          onConfirm={() => { void handleClearAll(); }}
          okText="Clear All"
          okType="danger"
        >
          <Button size="small" danger icon={<DeleteOutlined />}>
            Clear All
          </Button>
        </Popconfirm>
      </div>

      {/* Table */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <Table
          columns={columns}
          dataSource={items}
          rowKey="id"
          loading={loading}
          size="small"
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            onChange: (p) => setPage(p),
            size: 'small',
            showTotal: (t) => `${t} entries`,
          }}
          style={{ fontSize: 12 }}
        />
      </div>

      {/* Detail drawer */}
      <Drawer
        title="Query Detail"
        open={drawerEntry !== null}
        onClose={() => setDrawerEntry(null)}
        width={600}
      >
        {drawerEntry && (
          <div>
            <Typography.Paragraph>
              <Typography.Text strong>Question</Typography.Text>
              <br />
              <Typography.Text>{drawerEntry.question}</Typography.Text>
            </Typography.Paragraph>
            <Typography.Paragraph>
              <Typography.Text strong>SQL</Typography.Text>
              <br />
              <pre
                style={{
                  background: '#f6f8fa',
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 12,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {drawerEntry.sql}
              </pre>
            </Typography.Paragraph>
            {drawerEntry.error && (
              <Typography.Paragraph>
                <Typography.Text strong type="danger">Error</Typography.Text>
                <br />
                <Typography.Text type="danger">{drawerEntry.error}</Typography.Text>
              </Typography.Paragraph>
            )}
            <Space wrap>
              <Tag color={drawerEntry.dialect === 'trino' ? 'blue' : 'orange'}>
                {drawerEntry.dialect}
              </Tag>
              {drawerEntry.catalog && <Tag>{drawerEntry.catalog}</Tag>}
              {drawerEntry.namespace && <Tag>{drawerEntry.namespace}</Tag>}
              {drawerEntry.model && <Tag color="purple">{drawerEntry.model}</Tag>}
              <Tag color={drawerEntry.status === 'success' ? 'green' : 'red'}>
                {drawerEntry.status}
              </Tag>
            </Space>
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default HistoryPage;
