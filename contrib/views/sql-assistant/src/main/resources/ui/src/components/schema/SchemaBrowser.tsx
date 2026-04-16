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

// ui/src/components/schema/SchemaBrowser.tsx

import React, { useCallback, useEffect, useState } from 'react';
import { Tree, Spin, Button, Tooltip, Tag, Typography, Empty, message } from 'antd';
import {
  DatabaseOutlined,
  TableOutlined,
  ReloadOutlined,
  FolderOutlined,
  KeyOutlined,
  PartitionOutlined,
} from '@ant-design/icons';
import type { TreeDataNode } from 'antd';
import {
  listCatalogs,
  listNamespaces,
  listTables,
  getTableSchema,
  refreshSchema,
} from '../../api/client';
import type { TableSchema } from '../../types';

interface SchemaBrowserProps {
  onTableSelect?: (catalog: string, namespace: string, table: string) => void;
  selectedCatalog?: string;
  selectedNamespace?: string;
}

type NodeKey = string;

interface NodeMeta {
  type: 'catalog' | 'namespace' | 'table' | 'column';
  catalog?: string;
  namespace?: string;
  table?: string;
}

const nodeMeta = new Map<NodeKey, NodeMeta>();

const SchemaBrowser: React.FC<SchemaBrowserProps> = ({
  onTableSelect,
  selectedCatalog,
  selectedNamespace,
}) => {
  const [treeData, setTreeData] = useState<TreeDataNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [loadingKeys, setLoadingKeys] = useState<Set<string>>(new Set());

  const loadCatalogs = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listCatalogs();
      const nodes: TreeDataNode[] = data.catalogs.map((c) => {
        const key = `cat:${c.name}`;
        nodeMeta.set(key, { type: 'catalog', catalog: c.name });
        return {
          key,
          title: (
            <span>
              <DatabaseOutlined style={{ marginRight: 6, color: '#1677ff' }} />
              <strong>{c.name}</strong>
              {c.description && (
                <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>
                  {c.description}
                </Typography.Text>
              )}
            </span>
          ),
          isLeaf: false,
          children: undefined,
        };
      });
      setTreeData(nodes);
    } catch (err) {
      void message.error('Failed to load catalogs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCatalogs();
  }, [loadCatalogs]);

  const onLoadData = async (node: TreeDataNode): Promise<void> => {
    const key = String(node.key);
    const meta = nodeMeta.get(key);
    if (!meta) return;

    setLoadingKeys((prev) => new Set(prev).add(key));
    try {
      if (meta.type === 'catalog' && meta.catalog) {
        const data = await listNamespaces(meta.catalog);
        const children: TreeDataNode[] = data.namespaces.map((ns) => {
          const nsKey = `ns:${meta.catalog}:${ns.name}`;
          nodeMeta.set(nsKey, {
            type: 'namespace',
            catalog: meta.catalog,
            namespace: ns.name,
          });
          return {
            key: nsKey,
            title: (
              <span>
                <FolderOutlined style={{ marginRight: 6, color: '#fa8c16' }} />
                {ns.name}
              </span>
            ),
            isLeaf: false,
            children: undefined,
          };
        });
        updateNodeChildren(key, children);
      } else if (meta.type === 'namespace' && meta.catalog && meta.namespace) {
        const data = await listTables(meta.catalog, meta.namespace);
        const children: TreeDataNode[] = data.tables.map((t) => {
          const tKey = `tbl:${meta.catalog}:${meta.namespace}:${t.name}`;
          nodeMeta.set(tKey, {
            type: 'table',
            catalog: meta.catalog,
            namespace: meta.namespace,
            table: t.name,
          });
          return {
            key: tKey,
            title: (
              <Tooltip title={t.description || ''} placement="right">
                <span>
                  <TableOutlined style={{ marginRight: 6, color: '#52c41a' }} />
                  {t.name}
                  {t.type && t.type !== 'TABLE' && (
                    <Tag style={{ marginLeft: 6, fontSize: 10 }} color="purple">
                      {t.type}
                    </Tag>
                  )}
                </span>
              </Tooltip>
            ),
            isLeaf: false,
            children: undefined,
          };
        });
        updateNodeChildren(key, children);
      } else if (meta.type === 'table' && meta.catalog && meta.namespace && meta.table) {
        const schema = await getTableSchema(meta.catalog, meta.namespace, meta.table);
        const children = renderColumnNodes(schema);
        updateNodeChildren(key, children);
        if (onTableSelect) {
          onTableSelect(meta.catalog, meta.namespace, meta.table);
        }
      }
    } catch (err) {
      void message.error(`Failed to load: ${String(err)}`);
    } finally {
      setLoadingKeys((prev) => {
        const s = new Set(prev);
        s.delete(key);
        return s;
      });
    }
  };

  const renderColumnNodes = (schema: TableSchema): TreeDataNode[] =>
    schema.columns.map((col) => ({
      key: `col:${schema.catalog}:${schema.namespace}:${schema.name}:${col.name}`,
      title: (
        <span style={{ fontSize: 12 }}>
          {col.partition_key ? (
            <PartitionOutlined style={{ marginRight: 4, color: '#eb2f96', fontSize: 11 }} />
          ) : (
            <span style={{ marginRight: 4, color: '#8c8c8c', fontSize: 10 }}>▸</span>
          )}
          <span style={{ fontFamily: 'monospace' }}>{col.name}</span>
          <Tag style={{ marginLeft: 6, fontSize: 10 }} color="default">{col.data_type}</Tag>
          {!col.nullable && <Tag style={{ fontSize: 10 }} color="red">NOT NULL</Tag>}
          {col.partition_key && <Tag style={{ fontSize: 10 }} color="magenta">PARTITION</Tag>}
        </span>
      ),
      isLeaf: true,
    }));

  const updateNodeChildren = (key: string, children: TreeDataNode[]) => {
    setTreeData((prev) => updateTree(prev, key, children));
  };

  const handleRefresh = async () => {
    try {
      await refreshSchema();
      nodeMeta.clear();
      setTreeData([]);
      setExpandedKeys([]);
      void loadCatalogs();
      void message.success('Schema cache refreshed');
    } catch {
      void message.error('Failed to refresh schema');
    }
  };

  if (loading) {
    return (
      <div style={{ padding: '24px', textAlign: 'center' }}>
        <Spin tip="Loading schema…" />
      </div>
    );
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div
        style={{
          padding: '8px 12px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexShrink: 0,
        }}
      >
        <Typography.Text strong style={{ fontSize: 12 }}>
          <DatabaseOutlined style={{ marginRight: 6 }} />
          Schema
        </Typography.Text>
        <Tooltip title="Refresh schema cache">
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => { void handleRefresh(); }}
          />
        </Tooltip>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
        {treeData.length === 0 ? (
          <Empty
            description="No catalogs found"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            style={{ marginTop: 40 }}
          />
        ) : (
          <Tree
            treeData={treeData}
            loadData={onLoadData}
            expandedKeys={expandedKeys}
            onExpand={(keys) => setExpandedKeys(keys)}
            blockNode
            style={{ fontSize: 12 }}
          />
        )}
      </div>
    </div>
  );
};

// ── tree helpers ─────────────────────────────────────────────────────────────

function updateTree(
  nodes: TreeDataNode[],
  targetKey: string,
  children: TreeDataNode[],
): TreeDataNode[] {
  return nodes.map((node) => {
    if (node.key === targetKey) {
      return { ...node, children };
    }
    if (node.children) {
      return { ...node, children: updateTree(node.children, targetKey, children) };
    }
    return node;
  });
}

export default SchemaBrowser;
