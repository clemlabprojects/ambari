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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ui/src/pages/NodesPage.tsx
import React from 'react';
import { Typography, Table, Progress, Tag, Skeleton, Result, message } from 'antd';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { getNodes } from '../api/client';
import StatusTag from '../components/common/StatusTag';
import './Page.css';

const { Title } = Typography;

const NodesPage: React.FC = () => {
    const { status } = useClusterStatus();
    const [nodes, setNodes] = React.useState<any[]>([]);
    const [total, setTotal] = React.useState(0);
    const [page, setPage] = React.useState(1);
    const [pageSize, setPageSize] = React.useState(50);
    const [loading, setLoading] = React.useState(false);

    const fetchPage = React.useCallback(async (p = page, ps = pageSize) => {
        setLoading(true);
        try {
            const offset = (p - 1) * ps;
            const res = await getNodes(ps, offset);
            const items = (res as any)?.items || res;
            setNodes(items || []);
            setTotal((res as any)?.total || items?.length || 0);
            setPage(p);
            setPageSize(ps);
        } catch (e: any) {
            message.error(e?.message || 'Failed to load nodes');
        } finally {
            setLoading(false);
        }
    }, [page, pageSize]);

    React.useEffect(() => {
        void fetchPage(1, pageSize);
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    if (status === 'error') {
        return <Result status="warning" title="Node data not available." subTitle="Unable to retrieve cluster information." />;
    }

    if (loading && nodes.length === 0) {
        return <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 16 }} />;
    }

    /**
     * Convert the raw usage fraction into a single-decimal percentage for display.
     */
    const clampPercent = (usage?: number) => {
        if (typeof usage !== 'number' || Number.isNaN(usage)) {
            return 0;
        }
        const pct = Math.min(100, Math.max(0, usage * 100));
        return Number(pct.toFixed(1));
    };

    const getRoleColor = (role: string) => {
        if (role === 'control-plane' || role === 'master') return 'blue';
        if (role === 'worker') return 'cyan';
        return 'default';
    };

    const columns = [
        { title: 'Name', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'Status', dataIndex: 'status', key: 'status', render: (s: any) => <StatusTag status={s} /> },
        { title: 'Roles', dataIndex: 'roles', key: 'roles', render: (roles: string[]) => roles?.length ? roles.map(role => <Tag key={role} color={getRoleColor(role)}>{role}</Tag>) : '—' },
        { title: 'CPU', dataIndex: 'cpuUsage', key: 'cpuUsage', render: (usage: number) => <Progress percent={clampPercent(usage)} /> },
        { title: 'Memory', dataIndex: 'memoryUsage', key: 'memoryUsage', render: (usage: number) => <Progress percent={clampPercent(usage)} /> },
    ];
    return (
        <div>
            <div className="page-header">
                <Title level={2}>Cluster Nodes</Title>
            </div>
            <Table
              columns={columns}
              dataSource={nodes}
              rowKey="id"
              loading={loading || status === 'loading'}
              pagination={{
                current: page,
                pageSize,
                total,
                showSizeChanger: true,
                onChange: (p, ps) => fetchPage(p, ps),
                showTotal: (t, range) => `${range[0]}-${range[1]} of ${t}`
              }}
            />
        </div>
    );
};

export default NodesPage;
