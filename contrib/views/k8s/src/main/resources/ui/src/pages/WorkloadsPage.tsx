import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Select, Space, Table, Tag, Typography, Button, Modal, Spin, Input, Dropdown, message, Tabs, Tooltip } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import type { MenuProps } from 'antd';
import {
  getNamespaces,
  getPods,
  getServices,
  getPodLogs,
  getNamespaceEvents,
  getPodEvents,
  describePod,
  describeService,
  deletePod,
  restartPod
} from '../api/client';
import type { KubeNamespace, KubePod, KubeService, KubePodContainerStatus, KubeEvent } from '../types/KubeTypes';
import { DownloadOutlined } from '@ant-design/icons';
import { ReloadOutlined } from '@ant-design/icons';

const { Option } = Select;

/**
 * Workloads explorer: namespaces, pods, services, events.
 * Mirrors a lightweight subset of the OpenShift console.
 */
const WorkloadsPage: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const [namespaces, setNamespaces] = useState<KubeNamespace[]>([]);
  const [namespace, setNamespace] = useState<string>();
  const [pods, setPods] = useState<KubePod[]>([]);
  const [services, setServices] = useState<KubeService[]>([]);
  const [loadingPods, setLoadingPods] = useState(false);
  const [loadingSvc, setLoadingSvc] = useState(false);
  const [labelSelector, setLabelSelector] = useState<string>('');
  const [logsVisible, setLogsVisible] = useState(false);
  const [logContent, setLogContent] = useState<string>('');
  const [logTitle, setLogTitle] = useState<string>('');
  const [logPod, setLogPod] = useState<KubePod | undefined>(undefined);
  const [logLoading, setLogLoading] = useState(false);
  const [logContainer, setLogContainer] = useState<string | undefined>(undefined);
  const [namespaceEvents, setNamespaceEvents] = useState<KubeEvent[]>([]);
  const [namespaceEventsLoading, setNamespaceEventsLoading] = useState(false);
  const [podEvents, setPodEvents] = useState<KubeEvent[]>([]);
  const [podEventsLoading, setPodEventsLoading] = useState(false);
  const [describeVisible, setDescribeVisible] = useState(false);
  const [describeTitle, setDescribeTitle] = useState('');
  const [describeContent, setDescribeContent] = useState('');
  const [describeLoading, setDescribeLoading] = useState(false);
  const [podEventsVisible, setPodEventsVisible] = useState(false);
  const [podEventsFor, setPodEventsFor] = useState<string | undefined>(undefined);
  const [activeTab, setActiveTab] = useState<string>('pods');

  useEffect(() => {
    // Initial namespace fetch and default selection (use nav state/query if present)
    const stateNs = (location.state as any)?.namespace as string | undefined;
    const stateSelector = (location.state as any)?.labelSelector as string | undefined;
    const query = new URLSearchParams(location.search);
    const qsNs = query.get('namespace') || undefined;
    const qsSelector = query.get('labelSelector') || undefined;
    const initialNs = stateNs || qsNs;
    const initialSelector = stateSelector || qsSelector;
    if (initialSelector) setLabelSelector(initialSelector);
    void (async () => {
      try {
        const ns = await getNamespaces();
        setNamespaces(ns);
        const first = initialNs || ns[0]?.name;
        if (first) setNamespace(first);
      } catch (err) {
        console.error(err);
      }
    })();
  }, [location]);

  const refreshAll = useCallback(() => {
    if (!namespace) return;
    setLoadingPods(true);
    void getPods(namespace, labelSelector || undefined)
      .then(setPods)
      .finally(() => setLoadingPods(false));

    setLoadingSvc(true);
    void getServices(namespace, labelSelector || undefined)
      .then(setServices)
      .finally(() => setLoadingSvc(false));
    setNamespaceEventsLoading(true);
    void getNamespaceEvents(namespace)
      .then(setNamespaceEvents)
      .finally(() => setNamespaceEventsLoading(false));
  }, [namespace, labelSelector]);

  useEffect(() => {
    refreshAll();
  }, [refreshAll]);

  const showDescribe = async (kind: 'pod' | 'service', name: string) => {
    if (!namespace) return;
    setDescribeVisible(true);
    setDescribeLoading(true);
    setDescribeTitle(`${kind === 'pod' ? 'Pod' : 'Service'}: ${name}`);
    try {
      const content = kind === 'pod' ? await describePod(namespace, name) : await describeService(namespace, name);
      setDescribeContent(content);
    } catch (err: any) {
      setDescribeContent(err?.message || 'Failed to load describe');
    } finally {
      setDescribeLoading(false);
    }
  };

  const showPodEvents = async (podName: string) => {
    if (!namespace) return;
    setPodEventsLoading(true);
    setPodEventsFor(podName);
    setPodEventsVisible(true);
    try {
      const ev = await getPodEvents(namespace, podName);
      setPodEvents(ev);
    } catch (err) {
      message.error('Failed to load pod events');
    } finally {
      setPodEventsLoading(false);
    }
  };

  const onDeletePod = async (podName: string) => {
    if (!namespace) return;
    try {
      await deletePod(namespace, podName, 0);
      message.success('Pod delete requested');
      void getPods(namespace, labelSelector || undefined).then(setPods);
    } catch (err: any) {
      message.error(err?.message || 'Delete failed');
    }
  };

  const onRestartPod = async (podName: string) => {
    if (!namespace) return;
    try {
      await restartPod(namespace, podName);
      message.success('Restart requested');
      // Refresh immediately to pick up new pod names after restart
      void getPods(namespace, labelSelector || undefined).then(setPods);
      setNamespaceEventsLoading(true);
      void getNamespaceEvents(namespace).then(setNamespaceEvents).finally(() => setNamespaceEventsLoading(false));
    } catch (err: any) {
      message.error(err?.message || 'Restart failed');
    }
  };

  const readyRatio = (p: KubePod): string => {
    const total = p.containers?.length || 0;
    const ready = p.containers?.filter(c => c.ready).length || 0;
    return `${ready}/${total}`;
  };

  const fetchLogs = async (p: KubePod, container?: string) => {
    setLogLoading(true);
    try {
      let logs = await getPodLogs(p.namespace, p.name, container, 500);
      if (!logs) {
        logs = await getPodLogs(p.namespace, p.name, container);
      }
      setLogContent(logs);
    } catch (err: any) {
      setLogContent(err?.message || 'Failed to load logs');
    } finally {
      setLogLoading(false);
    }
  };

  const openLogs = async (p: KubePod, container?: string) => {
    const defaultContainer = container || p.containers?.[0]?.name;
    setLogTitle(`${p.name} (${p.namespace})`);
    setLogContainer(defaultContainer);
    setLogPod(p);
    setLogsVisible(true);
    void fetchLogs(p, defaultContainer);
  };

  const downloadLogs = () => {
    const blob = new Blob([logContent || ''], { type: 'text/plain;charset=utf-8' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${logTitle || 'pod-logs'}.log`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
  };

  // Keep URL in sync for bookmarking/link sharing
  useEffect(() => {
    const params = new URLSearchParams();
    if (namespace) params.set('namespace', namespace);
    if (labelSelector) params.set('labelSelector', labelSelector);
    navigate({ pathname: location.pathname, search: params.toString() }, { replace: true });
  }, [namespace, labelSelector, navigate, location.pathname]);

  // If pods changed and the currently viewed pod disappeared (after restart/delete), close the log modal
  useEffect(() => {
    if (logPod && !pods.find(p => p.name === logPod.name && p.namespace === logPod.namespace)) {
      setLogsVisible(false);
      setLogPod(undefined);
      setLogContent('');
    }
  }, [pods, logPod]);

  // Reusable pod action menu to shrink column width
  const podActionItems = useMemo(
    () => (row: KubePod): MenuProps['items'] => [
      { key: 'logs', label: 'Logs', onClick: () => openLogs(row, row.containers?.[0]?.name) },
      { key: 'describe', label: 'Describe', onClick: () => showDescribe('pod', row.name) },
      { key: 'events', label: 'Events', onClick: () => showPodEvents(row.name) },
      { type: 'divider' as const },
      { key: 'restart', label: <span style={{ color: '#d46b08' }}>Restart</span>, onClick: () => onRestartPod(row.name) },
      { key: 'delete', label: <span style={{ color: '#a8071a' }}>Delete</span>, onClick: () => onDeletePod(row.name) }
    ],
    [labelSelector, namespace]
  );

  // Columns for the pods table (lightweight status similar to OpenShift pod list)
  const podColumns = [
    { title: 'Name', dataIndex: 'name', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: 'Status', dataIndex: 'phase', render: (v: string) => <Tag color={v === 'Running' ? 'green' : v === 'Pending' ? 'blue' : 'red'}>{v || 'Unknown'}</Tag> },
    { title: 'Ready', dataIndex: 'containers', render: (_: any, row: KubePod) => readyRatio(row) },
    { title: 'Restarts', dataIndex: 'containers', render: (_: any, row: KubePod) => row.containers?.reduce((sum, c) => sum + (c.restartCount || 0), 0) ?? 0 },
    { title: 'Node', dataIndex: 'nodeName' },
    { title: 'IP', dataIndex: 'podIP' },
    { title: 'Start', dataIndex: 'startTime' },
    { title: 'Actions', render: (_: any, row: KubePod) => (
      <Dropdown menu={{ items: podActionItems(row) }} placement="bottomLeft" trigger={['click']}>
        <Button size="small" className="action-button" style={{ background: '#fff' }}>Actions</Button>
      </Dropdown>
    )}
  ];

  // Columns for the services table
  const serviceColumns = [
    { title: 'Name', dataIndex: 'name', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: 'Type', dataIndex: 'type' },
    { title: 'Cluster IP', dataIndex: 'clusterIP' },
    { title: 'Ports', dataIndex: 'ports', render: (p: Record<string, number>) => p ? Object.entries(p).map(([n, val]) => <Tag key={n}>{n}:{val}</Tag>) : '—' },
    { title: 'Labels', dataIndex: 'labels', render: (lbls: Record<string,string>) => lbls ? Object.entries(lbls).map(([k,v]) => <Tag key={k}>{k}={v}</Tag>) : '—' },
    {
      title: 'Actions',
      render: (_: any, row: KubeService) => (
        <Space size="small">
          <Button size="small" onClick={() => showDescribe('service', row.name)}>Describe</Button>
        </Space>
      )
    }
  ];

  const namespaceColumns = [
    { title: 'Name', dataIndex: 'name', render: (v: string) => <Typography.Text code>{v}</Typography.Text> },
    { title: 'Status', dataIndex: 'phase' },
  ];

  const tabItems = [
    {
      key: 'pods',
      label: <Space size={4}>Pods <Tag color="geekblue">{pods.length}</Tag></Space>,
      children: (
        <Table<KubePod>
          rowKey="name"
          size="small"
          loading={loadingPods}
          dataSource={pods}
          columns={podColumns as any}
          pagination={false}
          bordered={false}
        />
      )
    },
    {
      key: 'services',
      label: <Space size={4}>Services <Tag color="geekblue">{services.length}</Tag></Space>,
      children: (
        <Table<KubeService>
          rowKey="name"
          size="small"
          loading={loadingSvc}
          dataSource={services}
          columns={serviceColumns as any}
          pagination={false}
          bordered={false}
        />
      )
    },
    {
      key: 'events',
      label: <Space size={4}>Namespace Events <Tag color="geekblue">{namespaceEvents.length}</Tag></Space>,
      children: (
        <Table<KubeEvent>
          rowKey={(row) => `${row.involvedName}-${row.lastTimestamp}-${row.reason}`}
          size="small"
          loading={namespaceEventsLoading}
          dataSource={namespaceEvents}
          pagination={false}
          bordered={false}
          columns={[
            { title: 'Reason', dataIndex: 'reason' },
            { title: 'Type', dataIndex: 'type' },
            { title: 'Last', dataIndex: 'lastTimestamp' },
            { title: 'Object', render: (_: any, row) => `${row.involvedKind || ''} ${row.involvedName || ''}` },
            { title: 'Message', dataIndex: 'message' },
          ]}
        />
      )
    },
    {
      key: 'namespaces',
      label: 'Namespaces',
      children: (
        <Table<KubeNamespace>
          rowKey="name"
          size="small"
          dataSource={namespaces}
          columns={namespaceColumns as any}
          pagination={false}
          bordered={false}
        />
      )
    }
  ];

  return (
    <div className="page-shell">
      <Space style={{ marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>Workloads</Typography.Title>
        <Select
          placeholder="Namespace"
          style={{ minWidth: 200 }}
          value={namespace}
          onChange={setNamespace}
          showSearch
          optionFilterProp="children"
        >
          {namespaces.map(ns => (
            <Option value={ns.name} key={ns.name}>{ns.name}</Option>
          ))}
        </Select>
      </Space>

      <Space style={{ marginBottom: 12 }} wrap>
        <Input
          style={{ minWidth: 260 }}
          placeholder="Label selector (e.g. app=trino,release=superset)"
          value={labelSelector}
          onChange={e => setLabelSelector(e.target.value)}
        />
        <Button onClick={() => setLabelSelector(labelSelector.trim())}>Apply filter</Button>
        <Button onClick={() => setLabelSelector('')}>Clear filter</Button>
        <Button onClick={refreshAll} icon={<ReloadOutlined />}>Refresh</Button>
      </Space>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
        type="card"
        tabBarExtraContent={<Button onClick={refreshAll} size="small" icon={<ReloadOutlined />}>Refresh</Button>}
      />

      <Modal
        open={logsVisible}
        onCancel={() => setLogsVisible(false)}
        footer={null}
        width="80vw"
        title={(
          <Space>
            <span>{`Logs – ${logTitle}${logContainer ? ` [${logContainer}]` : ''}`}</span>
            {namespace && logPod && (
              <Select
                size="small"
                style={{ minWidth: 140 }}
                value={logContainer}
                onChange={(val) => {
                  setLogContainer(val);
                  void fetchLogs(logPod, val);
                }}
                placeholder="Container"
              >
                {logPod.containers?.map(c => (
                  <Select.Option key={c.name} value={c.name}>{c.name}</Select.Option>
                ))}
              </Select>
            )}
            <Tooltip title="Refresh logs">
              <Button
                size="small"
                icon={<ReloadOutlined />}
                onClick={() => logPod && fetchLogs(logPod, logContainer)}
                disabled={!logPod || logLoading}
              />
            </Tooltip>
            <Tooltip title="Download logs">
              <Button size="small" icon={<DownloadOutlined />} onClick={downloadLogs} disabled={!logContent || logLoading} />
            </Tooltip>
          </Space>
        )}
      >
        {logLoading ? <Spin /> : (
          <pre style={{ maxHeight: '70vh', overflow: 'auto', background: '#0d1117', color: '#c9d1d9', padding: 12, borderRadius: 6 }}>
            {logContent || 'No logs returned. The pod may be pending or restarting. Use Refresh to retry.'}
          </pre>
        )}
      </Modal>

      <Modal
        open={describeVisible}
        onCancel={() => setDescribeVisible(false)}
        footer={null}
        width="70vw"
        title={describeTitle}
      >
        {describeLoading ? <Spin /> : (
          <pre style={{ maxHeight: '70vh', overflow: 'auto', background: '#0d1117', color: '#c9d1d9', padding: 12, borderRadius: 6 }}>
            {describeContent || 'No data'}
          </pre>
        )}
      </Modal>

      <Modal
        open={podEventsVisible}
        onCancel={() => setPodEventsVisible(false)}
        footer={null}
        width="70vw"
        title={podEventsFor ? `Events – ${podEventsFor}` : 'Pod events'}
      >
        <Table<KubeEvent>
          rowKey={(row) => `${row.involvedName}-${row.lastTimestamp}-${row.reason}`}
          size="small"
          loading={podEventsLoading}
          dataSource={podEvents}
          pagination={false}
          columns={[
            { title: 'Reason', dataIndex: 'reason' },
            { title: 'Type', dataIndex: 'type' },
            { title: 'Last', dataIndex: 'lastTimestamp' },
            { title: 'Object', render: (_: any, row) => `${row.involvedKind || ''} ${row.involvedName || ''}` },
            { title: 'Message', dataIndex: 'message' },
          ]}
        />
      </Modal>
    </div>
  );
};

export default WorkloadsPage;
