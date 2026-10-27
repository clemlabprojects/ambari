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

import React from 'react';
import { NavLink } from 'react-router-dom';
import { Alert, Button, Card, Checkbox, Form, Input, Modal, Popconfirm, Radio, Select, Space, Spin, Switch, Table, Tag, Tooltip, Typography, Upload, message } from 'antd';
import { ClusterOutlined, DeleteOutlined, EditOutlined, LinkOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons';
import {
  connectClusterWithInClusterServiceAccount, getClusterKubeconfigContexts, getNamedClusterLiveness, listKubernetesClusters,
  removeKubernetesCluster, saveClusterOpenShiftLogin, saveKubernetesCluster, selectClusterKubeconfigContext,
  uploadClusterKubeconfig, type ClusterLiveness, type KubeconfigContext, type KubernetesCluster,
} from '../api/client';
import { usePermissions } from '../hooks/usePermissions';
import { serverMessage } from '../utils/serverMessage';

const { Title, Paragraph, Text } = Typography;

/** A cluster id: a lowercase DNS label of at most 32 characters; "default" is the cluster KDPS was set up with. */
export const CLUSTER_ID = /^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$/;

/** How KDPS connects to a cluster, as the operator reads it. */
export const connectionLabel = (c: Pick<KubernetesCluster, 'configured' | 'authMode'>): string => {
  if (!c.configured) return 'No connection yet';
  switch (c.authMode) {
    case 'openshift-login': return 'OpenShift login';
    case 'in-cluster': return "Ambari pod's ServiceAccount";
    default: return 'Kubeconfig';
  }
};

/** The connection state of a cluster, as a tag: what to look at first. */
export const LivenessTag: React.FC<{ liveness?: ClusterLiveness | 'checking' }> = ({ liveness }) => {
  if (!liveness) return <Text type="secondary">—</Text>;
  if (liveness === 'checking') return <Spin size="small" aria-label="checking the connection" />;
  const [color, label] = {
    CONNECTED: ['green', 'Connected'],
    UNAUTHENTICATED: ['red', 'Credentials refused'],
    UNREACHABLE: ['orange', 'Unreachable'],
    UNCONFIGURED: ['default', 'No connection'],
  }[liveness.state] || ['default', liveness.state];
  return <Tooltip title={liveness.message}><Tag color={color} style={{ margin: 0 }}>{label}</Tag></Tooltip>;
};

type ConnectionMode = 'kubeconfig' | 'openshift-login' | 'in-cluster';

/** Saves the connection of a cluster other than the default one: a kubeconfig, an OpenShift login or the pod's account. */
const ConnectionModal: React.FC<{ cluster: KubernetesCluster | null; onClose: (saved: boolean) => void }> = ({ cluster, onClose }) => {
  const [mode, setMode] = React.useState<ConnectionMode>('kubeconfig');
  const [busy, setBusy] = React.useState(false);
  const [contexts, setContexts] = React.useState<KubeconfigContext[]>([]);
  const [form] = Form.useForm();

  React.useEffect(() => {
    if (!cluster) return;
    setMode((cluster.authMode as ConnectionMode) || 'kubeconfig');
    setContexts([]);
    form.resetFields();
  }, [cluster, form]);

  if (!cluster) return null;

  const loadContexts = async () => {
    try { setContexts(await getClusterKubeconfigContexts(cluster.id)); } catch { setContexts([]); }
  };

  const upload = async (file: File) => {
    setBusy(true);
    try {
      message.success(await uploadClusterKubeconfig(cluster.id, file));
      await loadContexts();
    } catch (e) {
      message.error(serverMessage(e));
    } finally {
      setBusy(false);
    }
    return false;
  };

  const run = async (action: () => Promise<{ message: string }>) => {
    setBusy(true);
    try {
      message.success((await action()).message || 'Connection saved');
      onClose(true);
    } catch (e) {
      message.error(serverMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const saveOpenShift = async () => {
    const v = await form.validateFields();
    await run(() => saveClusterOpenShiftLogin(cluster.id, {
      apiUrl: v.apiUrl, username: v.username, password: v.password, caData: v.caData || undefined, insecure: !!v.insecure,
    }));
  };

  return (
    <Modal open title={`Connection of ${cluster.name}`} width={640} onCancel={() => onClose(false)} footer={null} destroyOnHidden>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)} optionType="button">
          <Radio value="kubeconfig">Kubeconfig</Radio>
          <Radio value="openshift-login">OpenShift login</Radio>
          <Radio value="in-cluster">Ambari pod's ServiceAccount</Radio>
        </Radio.Group>

        {mode === 'kubeconfig' && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Text type="secondary">
              The kubeconfig is kept encrypted by KDPS. A context that runs a command to get its credentials is refused.
            </Text>
            <Upload accept=".yaml,.yml,.conf,.config,*" showUploadList={false} beforeUpload={upload}>
              <Button icon={<UploadOutlined />} loading={busy}>Upload a kubeconfig</Button>
            </Upload>
            {contexts.length > 1 && (
              <Select
                style={{ width: '100%' }}
                aria-label="kubeconfig context"
                placeholder="The context KDPS connects with"
                defaultValue={contexts.find(c => c.selected)?.name || contexts.find(c => c.current)?.name}
                options={contexts.map(c => ({ value: c.name, label: `${c.name}${c.server ? ` — ${c.server}` : ''}` }))}
                onChange={(context: string) => run(() => selectClusterKubeconfigContext(cluster.id, context))}
              />
            )}
            {contexts.length > 0 && <Button type="primary" onClick={() => onClose(true)}>Done</Button>}
          </Space>
        )}

        {mode === 'openshift-login' && (
          <Form form={form} layout="vertical" requiredMark={false}>
            <Form.Item name="apiUrl" label="API server" rules={[{ required: true, message: 'The API server URL is required' }]}>
              <Input placeholder="https://api.cluster.example.com:6443" />
            </Form.Item>
            <Form.Item name="username" label="User" rules={[{ required: true, message: 'The user is required' }]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item name="password" label="Password" rules={[{ required: true, message: 'The password is required' }]}>
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Form.Item name="caData" label="CA of the API server (PEM)"
              tooltip="KDPS verifies the API server's certificate with it. Required unless you skip the verification.">
              <Input.TextArea rows={4} placeholder="-----BEGIN CERTIFICATE-----" style={{ fontFamily: 'monospace', fontSize: 12 }} />
            </Form.Item>
            <Form.Item name="insecure" valuePropName="checked"
              rules={[({ getFieldValue }) => ({
                validator: (_, insecure) => (insecure || getFieldValue('caData')
                  ? Promise.resolve()
                  : Promise.reject(new Error("Paste the API server's CA, or skip the verification"))),
              })]}>
              <Checkbox>Do not verify the API server's certificate (shown on the cluster)</Checkbox>
            </Form.Item>
            <Button type="primary" loading={busy} onClick={saveOpenShift}>Save the login</Button>
          </Form>
        )}

        {mode === 'in-cluster' && (
          <Space direction="vertical">
            <Text type="secondary">
              Only for the cluster the Ambari pod runs in, and for one cluster at most: KDPS then acts with the pod's
              ServiceAccount and the permissions given to it.
            </Text>
            <Button type="primary" loading={busy} onClick={() => run(() => connectClusterWithInClusterServiceAccount(cluster.id))}>
              Connect with the pod's ServiceAccount
            </Button>
          </Space>
        )}
      </Space>
    </Modal>
  );
};

/** Adds a cluster, or changes its name, description and protection. */
const ClusterModal: React.FC<{ cluster: KubernetesCluster | 'new' | null; onClose: (saved?: KubernetesCluster) => void }> = ({ cluster, onClose }) => {
  const [form] = Form.useForm();
  const [busy, setBusy] = React.useState(false);
  React.useEffect(() => {
    if (!cluster) return;
    form.setFieldsValue(cluster === 'new'
      ? { id: '', name: '', description: '', protected: false }
      : { id: cluster.id, name: cluster.name, description: cluster.description || '', protected: cluster.protected });
  }, [cluster, form]);
  if (!cluster) return null;
  const adding = cluster === 'new';

  const save = async () => {
    const v = await form.validateFields();
    setBusy(true);
    try {
      const saved = await saveKubernetesCluster(v.id, { name: v.name, description: v.description, protected: !!v.protected });
      message.success(adding ? `Cluster ${saved.name} added` : `Cluster ${saved.name} saved`);
      onClose(saved);
    } catch (e) {
      message.error(serverMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open title={adding ? 'Add a Kubernetes cluster' : `Edit ${cluster.name}`} onCancel={() => onClose()}
      onOk={save} okText={adding ? 'Add' : 'Save'} confirmLoading={busy} destroyOnHidden>
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item name="id" label="Identifier"
          tooltip="Lowercase letters, digits and dashes; it names the cluster in releases and operations and cannot change."
          rules={[{ required: true, message: 'An identifier is required' },
            { pattern: CLUSTER_ID, message: 'Lowercase letters, digits and dashes, at most 32 characters' }]}>
          <Input disabled={!adding} placeholder="prod" />
        </Form.Item>
        <Form.Item name="name" label="Name" rules={[{ required: true, message: 'A name is required' }, { max: 64 }]}>
          <Input placeholder="Production" />
        </Form.Item>
        <Form.Item name="description" label="Description" rules={[{ max: 512 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <Form.Item name="protected" label="Protected" valuePropName="checked"
          tooltip="An uninstall, rollback or upgrade of a release on this cluster asks the operator to type its name.">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
};

/**
 * The Kubernetes clusters KDPS deploys to. Everyone reads them, with each one's connection state; administrators add
 * them, save their connection, protect them and remove them. Each cluster is checked on its own: one that cannot be
 * reached does not hold back the others.
 */
const KubernetesClustersPage: React.FC = () => {
  const { permissions } = usePermissions();
  const canConfigure = !!permissions?.canConfigure;
  const [clusters, setClusters] = React.useState<KubernetesCluster[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [liveness, setLiveness] = React.useState<Record<string, ClusterLiveness | 'checking'>>({});
  const [editing, setEditing] = React.useState<KubernetesCluster | 'new' | null>(null);
  const [connecting, setConnecting] = React.useState<KubernetesCluster | null>(null);

  const load = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await listKubernetesClusters();
      setClusters(list);
      setLiveness(Object.fromEntries(list.filter(c => c.configured).map(c => [c.id, 'checking' as const])));
      // One probe per cluster, each answering on its own.
      list.filter(c => c.configured).forEach(c => {
        getNamedClusterLiveness(c.id)
          .then(l => setLiveness(prev => ({ ...prev, [c.id]: l })))
          .catch(e => setLiveness(prev => ({ ...prev, [c.id]: { state: 'UNREACHABLE', message: serverMessage(e) } })));
      });
    } catch (e) {
      setError(serverMessage(e, 'The clusters could not be read.'));
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => { void load(); }, [load]);

  const remove = async (c: KubernetesCluster) => {
    try {
      message.success((await removeKubernetesCluster(c.id)).message);
      await load();
    } catch (e) {
      Modal.error({ title: `${c.name} was not removed`, content: serverMessage(e) });
    }
  };

  const columns = [
    {
      title: 'Cluster',
      key: 'cluster',
      render: (_: unknown, c: KubernetesCluster) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <Text strong>{c.name}</Text>
            <Text code style={{ fontSize: 12 }}>{c.id}</Text>
            {c.default && <Tag style={{ margin: 0 }}>default</Tag>}
            {c.protected && (
              <Tooltip title="An uninstall, rollback or upgrade here asks for the cluster's name">
                <Tag color="volcano" style={{ margin: 0 }}>protected</Tag>
              </Tooltip>
            )}
          </Space>
          {c.description && <Text type="secondary" style={{ fontSize: 12 }}>{c.description}</Text>}
        </Space>
      ),
    },
    {
      title: 'Connection',
      key: 'connection',
      render: (_: unknown, c: KubernetesCluster) => (
        <Space size={6}>
          <Text type={c.configured ? undefined : 'secondary'}>{connectionLabel(c)}</Text>
          {c.insecure && (
            <Tooltip title="KDPS does not verify this cluster's API server certificate: anyone on the path could impersonate it.">
              <Tag color="red" style={{ margin: 0 }}>TLS not verified</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: 'State',
      key: 'state',
      width: 170,
      render: (_: unknown, c: KubernetesCluster) => <LivenessTag liveness={c.configured ? liveness[c.id] : undefined} />,
    },
    ...(canConfigure ? [{
      title: '',
      key: 'actions',
      width: 260,
      render: (_: unknown, c: KubernetesCluster) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(c)}>Edit</Button>
          {c.default
            ? <NavLink to="/configuration"><Button size="small" icon={<LinkOutlined />}>Cluster Settings</Button></NavLink>
            : <Button size="small" onClick={() => setConnecting(c)}>Connection</Button>}
          {!c.default && (
            <Popconfirm title={`Remove ${c.name}?`} description="Its connection is forgotten. Refused while KDPS has releases on it."
              okText="Remove" okButtonProps={{ danger: true }} onConfirm={() => remove(c)}>
              <Button size="small" danger icon={<DeleteOutlined />} aria-label={`remove ${c.id}`} />
            </Popconfirm>
          )}
        </Space>
      ),
    }] : []),
  ];

  return (
    <>
      <Title level={3} style={{ marginTop: 0 }}>
        <ClusterOutlined style={{ marginRight: 8 }} />Kubernetes Clusters
      </Title>
      <Paragraph type="secondary" style={{ maxWidth: 820 }}>
        The clusters KDPS deploys to. The same configuration (repositories, security profiles, platform contexts,
        truststores, Company CAs) applies to every one; the wizard asks which cluster a release goes to once there is
        more than one. The default cluster is the one set up in Cluster Settings.
      </Paragraph>
      <Card
        size="small"
        title={`${clusters.length} cluster${clusters.length === 1 ? '' : 's'}`}
        extra={
          <Space>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>Refresh</Button>
            {canConfigure && <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>Add a cluster</Button>}
          </Space>
        }
        styles={{ body: { padding: 0 } }}
      >
        {error && <Alert type="error" showIcon message={error} style={{ margin: 12 }} />}
        <Table size="small" rowKey="id" loading={loading} dataSource={clusters} columns={columns as any} pagination={false} />
      </Card>

      <ClusterModal cluster={editing} onClose={(saved) => {
        const added = editing === 'new' && saved;
        setEditing(null);
        void load();
        // A new cluster is connected next.
        if (added) setConnecting(saved);
      }} />
      <ConnectionModal cluster={connecting} onClose={(saved) => { setConnecting(null); if (saved) void load(); }} />
    </>
  );
};

export default KubernetesClustersPage;
