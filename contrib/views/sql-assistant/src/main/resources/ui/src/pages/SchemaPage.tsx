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

// ui/src/pages/SchemaPage.tsx

import React, { useState } from 'react';
import { Splitter, Typography, Tag, Table, Empty, Descriptions } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import SchemaBrowser from '../components/schema/SchemaBrowser';
import { getTableSchema } from '../api/client';
import type { TableSchema, ColumnMeta } from '../types';
import './Page.css';

const SchemaPage: React.FC = () => {
  const [selectedSchema, setSelectedSchema] = useState<TableSchema | null>(null);
  const [loadingSchema, setLoadingSchema] = useState(false);

  const handleTableSelect = async (
    catalog: string,
    namespace: string,
    table: string,
  ) => {
    setLoadingSchema(true);
    try {
      const schema = await getTableSchema(catalog, namespace, table);
      setSelectedSchema(schema);
    } finally {
      setLoadingSchema(false);
    }
  };

  const columns: ColumnsType<ColumnMeta> = [
    {
      title: 'Column',
      dataIndex: 'name',
      key: 'name',
      render: (v: string) => (
        <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {v}
        </Typography.Text>
      ),
    },
    {
      title: 'Type',
      dataIndex: 'data_type',
      key: 'data_type',
      render: (v: string) => (
        <Tag color="default" style={{ fontFamily: 'monospace', fontSize: 11 }}>{v}</Tag>
      ),
    },
    {
      title: 'Nullable',
      dataIndex: 'nullable',
      key: 'nullable',
      width: 90,
      render: (v: boolean) =>
        v ? null : <Tag color="red" style={{ fontSize: 11 }}>NOT NULL</Tag>,
    },
    {
      title: 'Partition',
      dataIndex: 'partition_key',
      key: 'partition_key',
      width: 90,
      render: (v: boolean) =>
        v ? <Tag color="magenta" style={{ fontSize: 11 }}>PARTITION</Tag> : null,
    },
    {
      title: 'Description',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{v}</Typography.Text>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      <Splitter style={{ height: '100%' }}>
        <Splitter.Panel
          defaultSize="28%"
          min="200px"
          max="40%"
          style={{ borderRight: '1px solid #f0f0f0', overflow: 'hidden' }}
        >
          <SchemaBrowser
            onTableSelect={(cat, ns, tbl) => { void handleTableSelect(cat, ns, tbl); }}
          />
        </Splitter.Panel>

        <Splitter.Panel style={{ padding: 16, overflow: 'auto' }}>
          {!selectedSchema && !loadingSchema ? (
            <Empty
              description="Select a table in the schema browser to inspect its columns"
              style={{ marginTop: 60 }}
            />
          ) : (
            <>
              {selectedSchema && (
                <>
                  <Typography.Title level={5} style={{ marginTop: 0 }}>
                    {selectedSchema.catalog}.{selectedSchema.namespace}.{selectedSchema.name}
                  </Typography.Title>
                  <Descriptions size="small" column={3} style={{ marginBottom: 16 }}>
                    {selectedSchema.format && (
                      <Descriptions.Item label="Format">
                        <Tag color="blue">{selectedSchema.format}</Tag>
                      </Descriptions.Item>
                    )}
                    <Descriptions.Item label="Columns">
                      {selectedSchema.columns.length}
                    </Descriptions.Item>
                    {selectedSchema.location && (
                      <Descriptions.Item label="Location">
                        <Typography.Text
                          copyable
                          style={{ fontSize: 11, fontFamily: 'monospace' }}
                        >
                          {selectedSchema.location}
                        </Typography.Text>
                      </Descriptions.Item>
                    )}
                  </Descriptions>
                  <Table
                    columns={columns}
                    dataSource={selectedSchema.columns}
                    rowKey="name"
                    size="small"
                    pagination={false}
                    loading={loadingSchema}
                    style={{ fontSize: 12 }}
                  />
                </>
              )}
            </>
          )}
        </Splitter.Panel>
      </Splitter>
    </div>
  );
};

export default SchemaPage;
