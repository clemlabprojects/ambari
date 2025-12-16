// ui/src/pages/NodesPage.tsx
import React from 'react';
import { Typography, Table, Progress, Tag, Spin, Result, message } from 'antd';
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
      return <div style={{ textAlign: 'center', padding: '50px' }}><Spin size="large" /></div>;
    }

    const columns = [
        { title: 'Name', dataIndex: 'name', key: 'name', sorter: (a: any, b: any) => a.name.localeCompare(b.name) },
        { title: 'Status', dataIndex: 'status', key: 'status', render: (status: any) => <StatusTag status={status} /> },
        { title: 'Roles', dataIndex: 'roles', key: 'roles', render: (roles: string[]) => roles.map(role => <Tag key={role}>{role}</Tag>) },
        { title: 'CPU', dataIndex: 'cpuUsage', key: 'cpuUsage', render: (usage: number) => <Progress percent={usage * 100} /> },
        { title: 'Memory', dataIndex: 'memoryUsage', key: 'memoryUsage', render: (usage: number) => <Progress percent={usage * 100} status="success" /> },
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
