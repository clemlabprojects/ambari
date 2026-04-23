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

// ui/src/pages/ConfigurationPage.tsx
import React from 'react';
import { Typography, Card, Upload, Button, Alert, Collapse, Space, Layout, message, Form, Input, InputNumber, Switch, Select, Tag, Modal, Row, Col, Steps } from 'antd';
import { UploadOutlined, ApiOutlined, ReloadOutlined, PlayCircleOutlined, PlusOutlined, CheckCircleTwoTone, DisconnectOutlined, CheckCircleOutlined, ClockCircleOutlined, SyncOutlined, WarningOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { usePermissions } from '../hooks/usePermissions';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { useNavigate } from 'react-router-dom';
import './Page.css';
import type { UploadRequestOption as RcCustomRequestOptions } from 'rc-upload/lib/interface';
import { installMonitoring, resetMonitoringCache, getHelmRepos, getViewSettings, saveViewSettings, saveHelmRepo, loginHelmRepo, API_BASE_URL } from '../api/client';
import { required, url, slug, trim } from "../utils/formRules";

const { Title, Paragraph, Text } = Typography;
const { Content } = Layout;

const kubeconfigExample = `
# Example file to be placed on the Ambari server, e.g., in /etc/ambari-views/k8s-view/kubeconfig.yaml
apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0t...
    server: https://your-k8s-api-server:6443
  name: my-cluster
# ... (rest of the file)
`;

const MONITORING_STATE_CONFIG: Record<string, { color: string; label: string; icon: React.ReactNode }> = {
    COMPLETED: { color: 'success',    label: 'Detected',    icon: <CheckCircleOutlined /> },
    RUNNING:   { color: 'processing', label: 'Installing…', icon: <SyncOutlined spin /> },
    FAILED:    { color: 'warning',    label: 'Not found',   icon: <WarningOutlined /> },
    SKIPPED:   { color: 'default',    label: 'Skipped',     icon: <MinusCircleOutlined /> },
    PENDING:   { color: 'default',    label: 'Pending',     icon: <ClockCircleOutlined /> },
};

const ConfigurationPage: React.FC = () => {
    const { permissions } = usePermissions();
    const { status, fetchData, setClusterStatus, monitoringState } = useClusterStatus();
    const [form] = Form.useForm();
    const [repoForm] = Form.useForm();
    const [helmRepositoryOptions, setHelmRepositoryOptions] = React.useState<{ value: string; label: string }[]>([]);
    const [loadingHelmRepositories, setLoadingHelmRepositories] = React.useState(false);
    const [monitoringBootstrapLoading, setMonitoringBootstrapLoading] = React.useState(false);
    const [repositoryModalOpen, setRepositoryModalOpen] = React.useState(false);
    const [repositorySaving, setRepositorySaving] = React.useState(false);
    const [repositoryTesting, setRepositoryTesting] = React.useState(false);
    const [currentStep, setCurrentStep] = React.useState<number>(0);
    const [clusterStepCompleted, setClusterStepCompleted] = React.useState(status !== 'unconfigured');
    const [authStepSaved, setAuthStepSaved] = React.useState(false);
    const [goToDashboardLoading, setGoToDashboardLoading] = React.useState(false);
    const navigate = useNavigate();

    const monitoringRepoSelectOptions = React.useMemo(() => {
        return [...helmRepositoryOptions, { value: '__add_repo__', label: '+ Add repository' }];
    }, [helmRepositoryOptions]);

    const handleMonitoringRepoChange = (selectedRepoId: string | undefined) => {
        if (selectedRepoId === '__add_repo__') {
            const current = form.getFieldValue('monitoring') || {};
            form.setFieldsValue({ monitoring: { ...current, repoId: undefined } });
            setRepositoryModalOpen(true);
        }
    };

    const buildFullPayload = (vals: any) => ({
        monitoring: {
            autoBootstrap:              vals.monitoring?.autoBootstrap,
            preferPrometheus:           vals.monitoring?.preferPrometheus,
            allowMetricsServerFallback: vals.monitoring?.allowMetricsServerFallback,
            skipOnOpenShift:            vals.monitoring?.skipOnOpenShift,
            preferOpenShiftMonitoring:  vals.monitoring?.preferOpenShiftMonitoring,
            repoId:                     vals.monitoring?.repoId,
            prometheusHost:             vals.monitoring?.prometheusHost,
            prometheusIngressClass:     vals.monitoring?.prometheusIngressClass,
            prometheusNodePort:         vals.monitoring?.prometheusNodePort,
            thanos: {
                enabled:   vals.monitoring?.thanosEnabled,
                bucket:    vals.monitoring?.thanosBucket,
                endpoint:  vals.monitoring?.thanosEndpoint,
                region:    vals.monitoring?.thanosRegion,
                accessKey: vals.monitoring?.thanosAccessKey,
                secretKey: vals.monitoring?.thanosSecretKey,
                insecure:  vals.monitoring?.thanosInsecure,
            },
            thanosHost:         vals.monitoring?.thanosHost,
            thanosIngressClass: vals.monitoring?.thanosIngressClass,
            thanosNodePort:     vals.monitoring?.thanosNodePort,
        },
        kerberos: { injectionMode: vals.kerberos?.injectionMode },
    });

    const navButtons = (
        <Space>
            {currentStep > 0 && <Button onClick={() => setCurrentStep(currentStep - 1)}>Back</Button>}
            {currentStep === 0 && (
                <Button type="primary" disabled={!clusterStepCompleted} onClick={() => setCurrentStep(1)}>
                    Next
                </Button>
            )}
            {currentStep === 1 && (
                <Button
                    type="primary"
                    onClick={async () => {
                        try {
                            await form.validateFields([['kerberos', 'injectionMode']]);
                            const vals = form.getFieldsValue(true);
                            await saveViewSettings(buildFullPayload(vals));
                            setAuthStepSaved(true);
                            message.success('Authentication settings saved');
                        } catch (e: any) {
                            if (e?.errorFields) return;
                            message.error(e?.message || 'Save failed');
                        }
                    }}
                >
                    Save settings
                </Button>
            )}
            {status === 'connected' && clusterStepCompleted && (
                <Button
                    type="primary"
                    loading={goToDashboardLoading}
                    style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
                    onClick={async () => {
                        setGoToDashboardLoading(true);
                        try { navigate('/'); } finally {
                            setTimeout(() => setGoToDashboardLoading(false), 100);
                        }
                    }}
                >
                    Go to Dashboard
                </Button>
            )}
        </Space>
    );

    const refreshHelmRepositories = React.useCallback(async () => {
        setLoadingHelmRepositories(true);
        try {
            const repositories = await getHelmRepos();
            setHelmRepositoryOptions(repositories.map((repo: any) => ({
                value: repo.id,
                label: `${repo.name || repo.id} (${repo.type})`,
            })));
        } catch (err) {
            console.error('Failed to load Helm repositories', err);
        } finally {
            setLoadingHelmRepositories(false);
        }
    }, []);

    const handleCustomRequest = (uploadOptions: RcCustomRequestOptions) => {
        const { onSuccess, onError, file, onProgress } = uploadOptions;
        const xhr = new XMLHttpRequest();
        xhr.upload.onprogress = e => {
            if (e.lengthComputable && onProgress) onProgress({ percent: Math.floor((e.loaded / e.total) * 100) });
        };
        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) { if (onSuccess) onSuccess(xhr.responseText, xhr); }
            else { if (onError) onError(new Error(`Error ${xhr.status}: ${xhr.statusText}`), xhr); }
        };
        xhr.onerror = () => { if (onError) onError(new Error('Failed to upload the kubeconfig yaml'), xhr); };
        xhr.open('POST', `${API_BASE_URL}/cluster/config`, true);
        xhr.setRequestHeader('X-Requested-By', 'ambari');
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.send(file as Blob);
    };

    React.useEffect(() => {
        void refreshHelmRepositories();
        const loadSettings = async () => {
            try {
                const settings = await getViewSettings();
                form.setFieldsValue({
                    monitoring: {
                        autoBootstrap:              settings?.monitoring?.autoBootstrap ?? true,
                        preferPrometheus:           settings?.monitoring?.preferPrometheus ?? true,
                        allowMetricsServerFallback: settings?.monitoring?.allowMetricsServerFallback ?? false,
                        skipOnOpenShift:            settings?.monitoring?.skipOnOpenShift ?? false,
                        preferOpenShiftMonitoring:  settings?.monitoring?.preferOpenShiftMonitoring ?? false,
                        repoId:                     settings?.monitoring?.repoId,
                        prometheusHost:             settings?.monitoring?.prometheusHost,
                        prometheusIngressClass:     settings?.monitoring?.prometheusIngressClass ?? 'nginx',
                        prometheusNodePort:         settings?.monitoring?.prometheusNodePort,
                        thanosEnabled:              settings?.monitoring?.thanos?.enabled ?? false,
                        thanosBucket:               settings?.monitoring?.thanos?.bucket,
                        thanosEndpoint:             settings?.monitoring?.thanos?.endpoint,
                        thanosRegion:               settings?.monitoring?.thanos?.region,
                        thanosAccessKey:            settings?.monitoring?.thanos?.accessKey,
                        thanosSecretKey:            settings?.monitoring?.thanos?.secretKey,
                        thanosInsecure:             settings?.monitoring?.thanos?.insecure ?? false,
                        thanosHost:                 settings?.monitoring?.thanosHost,
                        thanosIngressClass:         settings?.monitoring?.thanosIngressClass ?? 'nginx',
                        thanosNodePort:             settings?.monitoring?.thanosNodePort,
                    },
                    kerberos: { injectionMode: settings?.kerberos?.injectionMode ?? 'WEBHOOK' },
                });
                if (settings?.kerberos?.injectionMode) setAuthStepSaved(true);
            } catch { /* ignore */ }
        };
        loadSettings();
    }, [form, refreshHelmRepositories]);

    React.useEffect(() => {
        if (status !== 'unconfigured') setCurrentStep(1);
    }, [status]);

    const handleSaveRepo = React.useCallback(async () => {
        try {
            const vals = await repoForm.validateFields();
            const { secret, ...entity } = vals;
            setRepositorySaving(true);
            await saveHelmRepo(entity, secret || undefined);
            message.success('Repository saved');
            setRepositoryModalOpen(false);
            repoForm.resetFields();
            await refreshHelmRepositories();
        } catch (e: any) {
            if (e?.errorFields) return;
            message.error(e?.message || 'Save failed');
        } finally { setRepositorySaving(false); }
    }, [refreshHelmRepositories, repoForm]);

    const handleTestRepo = React.useCallback(async () => {
        try {
            const vals = await repoForm.validateFields();
            const { secret, ...entity } = vals;
            if (!entity.id) throw new Error('Repository ID is required to test connectivity.');
            setRepositoryTesting(true);
            await saveHelmRepo(entity, secret || undefined);
            await loginHelmRepo(entity.id);
            message.success('Repository saved and login verified');
            await refreshHelmRepositories();
        } catch (e: any) {
            if (e?.errorFields) return;
            message.error(e?.message || 'Test failed');
        } finally { setRepositoryTesting(false); }
    }, [refreshHelmRepositories, repoForm]);

    const uploadProps = {
        name: 'file',
        customRequest: handleCustomRequest,
        showUploadList: true,
        onChange(uploadInfo: any) {
            if (uploadInfo.file.status === 'done') {
                message.success(`${uploadInfo.file.name} uploaded. Proceed to authentication or go to the dashboard.`);
                setClusterStatus('connected');
                setCurrentStep(1);
                setClusterStepCompleted(true);
                void fetchData(true);
            } else if (uploadInfo.file.status === 'error') {
                message.error(`Failed to upload ${uploadInfo.file.name}.`);
            }
        },
    };

    if (!permissions?.canConfigure) {
        return <Alert message="Insufficient Permissions" description="You do not have the required permissions to configure this view." type="error" showIcon style={{ margin: 24 }} />;
    }

    const stateInfo = monitoringState?.state ? MONITORING_STATE_CONFIG[monitoringState.state] : null;

    const monitoringCollapseItems = [
        {
            key: 'auto-install',
            label: (
                <Space>
                    <PlayCircleOutlined />
                    <span>Auto-install Prometheus</span>
                    <Tag color="default">optional</Tag>
                </Space>
            ),
            children: (
                <Space direction="vertical" style={{ width: '100%' }} size={4}>
                    {helmRepositoryOptions.length === 0 && (
                        <Alert
                            type="warning" showIcon
                            message="No Helm repository configured"
                            description={
                                <Space direction="vertical">
                                    <span>Add a repository to bootstrap monitoring or install charts.</span>
                                    <Button icon={<PlusOutlined />} size="small" onClick={() => setRepositoryModalOpen(true)}>Add repository</Button>
                                </Space>
                            }
                            style={{ marginBottom: 8 }}
                        />
                    )}
                    <Form.Item name={['monitoring','autoBootstrap']} valuePropName="checked" label="Auto-bootstrap when Prometheus is not detected" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Form.Item name={['monitoring','preferPrometheus']} valuePropName="checked" label="Prefer Prometheus over metrics-server when available" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Form.Item name={['monitoring','allowMetricsServerFallback']} valuePropName="checked" label="Allow metrics-server fallback when Prometheus is unavailable" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Form.Item name={['monitoring','repoId']} label="Helm repository" className="form-compact-row">
                        <Select allowClear loading={loadingHelmRepositories} placeholder="Use configured/default repo" options={monitoringRepoSelectOptions} onChange={handleMonitoringRepoChange} />
                    </Form.Item>
                    <Row gutter={16}>
                        <Col xs={24} md={8}>
                            <Form.Item name={['monitoring','prometheusIngressClass']} label="Ingress class" className="form-compact-row">
                                <Input placeholder="nginx" />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name={['monitoring','prometheusHost']} label="Ingress host (optional)" className="form-compact-row">
                                <Input placeholder="prometheus.example.com" />
                            </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                            <Form.Item name={['monitoring','prometheusNodePort']} label="NodePort (optional)" className="form-compact-row">
                                <InputNumber min={30000} max={32767} style={{ width: '100%' }} placeholder="30090" />
                            </Form.Item>
                        </Col>
                    </Row>
                    <Space wrap style={{ marginTop: 4 }}>
                        <Button
                            type="primary"
                            onClick={async () => {
                                try {
                                    const vals = form.getFieldsValue(true);
                                    await saveViewSettings(buildFullPayload(vals));
                                    message.success('Monitoring settings saved');
                                } catch (e: any) { message.error(e?.message || 'Save failed'); }
                            }}
                        >Save</Button>
                        <Button
                            icon={<PlayCircleOutlined />}
                            loading={monitoringBootstrapLoading}
                            onClick={async () => {
                                setMonitoringBootstrapLoading(true);
                                try {
                                    const repoId = form.getFieldValue(['monitoring','repoId']);
                                    await installMonitoring(repoId);
                                    message.success('Monitoring bootstrap requested');
                                    await fetchData(true);
                                } catch (e: any) { message.error(e?.message || 'Bootstrap failed'); }
                                finally { setMonitoringBootstrapLoading(false); }
                            }}
                        >Install now</Button>
                        <Button
                            icon={<DisconnectOutlined />}
                            onClick={async () => {
                                try {
                                    await resetMonitoringCache();
                                    message.success('Monitoring cache cleared');
                                    await fetchData(true);
                                } catch (e: any) { message.error(e?.message || 'Reset failed'); }
                            }}
                        >Reset cache</Button>
                    </Space>
                </Space>
            ),
        },
        {
            key: 'openshift',
            label: (
                <Space>
                    <span>OpenShift integration</span>
                    <Tag color="default">optional</Tag>
                </Space>
            ),
            children: (
                <Space direction="vertical" style={{ width: '100%' }} size={4}>
                    <Form.Item name={['monitoring','skipOnOpenShift']} valuePropName="checked" label="Skip bootstrap on OpenShift (use built-in monitoring)" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Form.Item name={['monitoring','preferOpenShiftMonitoring']} valuePropName="checked" label="Use OpenShift monitoring when available" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Button
                        type="primary" size="small" style={{ marginTop: 4 }}
                        onClick={async () => {
                            try {
                                const vals = form.getFieldsValue(true);
                                await saveViewSettings(buildFullPayload(vals));
                                message.success('OpenShift settings saved');
                            } catch (e: any) { message.error(e?.message || 'Save failed'); }
                        }}
                    >Save</Button>
                </Space>
            ),
        },
        {
            key: 'thanos',
            label: (
                <Space>
                    <span>Long-term storage / Thanos</span>
                    <Tag color="default">optional</Tag>
                </Space>
            ),
            children: (
                <Space direction="vertical" style={{ width: '100%' }} size={4}>
                    <Form.Item name={['monitoring','thanosEnabled']} valuePropName="checked" label="Enable Thanos long-term storage" className="form-switch-row">
                        <Switch size="small" />
                    </Form.Item>
                    <Form.Item noStyle shouldUpdate>
                        {() => form.getFieldValue(['monitoring','thanosEnabled']) ? (
                            <Row gutter={16} style={{ marginTop: 4 }}>
                                <Col xs={24} md={12}>
                                    <Form.Item name={['monitoring','thanosBucket']} label="S3 Bucket" className="form-compact-row" rules={[({ getFieldValue }) => ({
                                        validator(_, val) {
                                            if (getFieldValue(['monitoring','thanosEnabled']) && !val) return Promise.reject('Bucket is required when Thanos is enabled');
                                            return Promise.resolve();
                                        },
                                    })]}>
                                        <Input placeholder="my-thanos-bucket" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosRegion']} label="Region (optional)" className="form-compact-row">
                                        <Input placeholder="us-east-1" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosInsecure']} valuePropName="checked" label="Use insecure (HTTP) endpoint" className="form-switch-row">
                                        <Switch size="small" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosIngressClass']} label="Thanos ingress class" className="form-compact-row">
                                        <Input placeholder="nginx" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosHost']} label="Thanos ingress host (optional)" className="form-compact-row">
                                        <Input placeholder="thanos.example.com" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosNodePort']} label="Thanos NodePort (optional)" className="form-compact-row">
                                        <InputNumber min={30000} max={32767} style={{ width: '100%' }} placeholder="30901" />
                                    </Form.Item>
                                </Col>
                                <Col xs={24} md={12}>
                                    <Form.Item name={['monitoring','thanosEndpoint']} label="S3 Endpoint" className="form-compact-row" rules={[({ getFieldValue }) => ({
                                        validator(_, val) {
                                            if (getFieldValue(['monitoring','thanosEnabled']) && !val) return Promise.reject('Endpoint is required when Thanos is enabled');
                                            return Promise.resolve();
                                        },
                                    })]}>
                                        <Input placeholder="s3.amazonaws.com" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosAccessKey']} label="Access key" className="form-compact-row">
                                        <Input placeholder="ACCESS_KEY" />
                                    </Form.Item>
                                    <Form.Item name={['monitoring','thanosSecretKey']} label="Secret key" className="form-compact-row">
                                        <Input.Password placeholder="SECRET_KEY" />
                                    </Form.Item>
                                </Col>
                            </Row>
                        ) : null}
                    </Form.Item>
                    <Button
                        type="primary" size="small" style={{ marginTop: 4 }}
                        onClick={async () => {
                            try {
                                const vals = await form.validateFields();
                                await saveViewSettings(buildFullPayload(vals));
                                message.success('Storage settings saved');
                            } catch (e: any) {
                                if (e?.errorFields) return;
                                message.error(e?.message || 'Save failed');
                            }
                        }}
                    >Save</Button>
                </Space>
            ),
        },
    ];

    return (
        <>
            <Layout style={{ background: '#f0f2f5' }}>
                <Content style={{ padding: '24px', maxWidth: '1280px', minWidth: '1200px', margin: '0 auto' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                        <Space>
                            <ApiOutlined style={{ color: '#1677ff', fontSize: '24px' }} />
                            <Title level={4} style={{ margin: 0 }}>Ambari K8S View Configuration</Title>
                            {status === 'connected'
                                ? <Tag color="green" icon={<CheckCircleTwoTone twoToneColor="#52c41a" twoToneColor2="#52c41a" />}>Connected</Tag>
                                : <Tag color="orange" icon={<DisconnectOutlined />}>Not connected</Tag>
                            }
                        </Space>
                    </div>

                    <Steps
                        style={{ marginBottom: 16 }}
                        current={currentStep}
                        items={[
                            { title: 'Cluster connection' },
                            { title: 'Authentication' },
                        ]}
                    />

                    {/* ── Step 0: Cluster connection ── */}
                    {currentStep === 0 && (
                        <Card title="Kubernetes Cluster Connection">
                            <Space direction="vertical" size="large" style={{ width: '100%' }}>
                                {status === 'unconfigured' && (
                                    <Alert message="Configuration required" description="Please upload a valid kubeconfig file to continue." type="info" showIcon />
                                )}
                                <Paragraph>
                                    Upload the <code>kubeconfig</code> file here to enable the view to connect to your Kubernetes or OpenShift cluster.
                                </Paragraph>
                                <Upload {...uploadProps}>
                                    <Button icon={<UploadOutlined />}>Select Kubeconfig File</Button>
                                </Upload>
                                <Collapse ghost items={[{
                                    key: '1',
                                    label: <Text strong>View example file</Text>,
                                    children: (
                                        <pre style={{ backgroundColor: '#2b2b2b', color: '#f8f8f2', padding: '16px', borderRadius: '8px' }}>
                                            <code>{kubeconfigExample.trim()}</code>
                                        </pre>
                                    ),
                                }]} />
                            </Space>
                        </Card>
                    )}

                    {/* ── Step 1: Authentication ── */}
                    {currentStep === 1 && (
                        <Card title="Authentication Configuration" extra={navButtons} style={{ marginTop: 0 }}>
                            <Form form={form} layout="vertical">
                                <Paragraph>
                                    Choose how Kerberos keytabs are delivered to pods. Webhook mode injects keytabs at
                                    admission time; pre-provisioned mode creates a Secret before Helm install.
                                </Paragraph>
                                <Form.Item label="Kerberos keytab injection mode" name={['kerberos','injectionMode']} className="form-compact-row" style={{ maxWidth: 360 }}>
                                    <Select options={[
                                        { value: 'WEBHOOK',         label: 'Webhook (mutating admission)' },
                                        { value: 'PRE_PROVISIONED', label: 'Pre-provisioned Secret (no webhook)' },
                                    ]} />
                                </Form.Item>
                            </Form>
                        </Card>
                    )}

                    {currentStep === 0 && (
                        <div style={{ marginTop: 16, textAlign: 'right' }}>{navButtons}</div>
                    )}

                    {/* ── Monitoring card — always visible when cluster is connected ── */}
                    {status !== 'unconfigured' && (
                        <Card
                            style={{ marginTop: 16 }}
                            title={
                                <Space>
                                    <span>Monitoring</span>
                                    {stateInfo
                                        ? <Tag color={stateInfo.color} icon={stateInfo.icon}>{stateInfo.label}</Tag>
                                        : <Tag color="default">Not checked</Tag>
                                    }
                                    {monitoringState?.state === 'COMPLETED' && monitoringState.message && (
                                        <Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                                            {monitoringState.message}
                                        </Text>
                                    )}
                                </Space>
                            }
                            extra={
                                <Button size="small" icon={<ReloadOutlined />} onClick={() => fetchData(true)}>
                                    Check status
                                </Button>
                            }
                        >
                            {monitoringState?.state === 'FAILED' && monitoringState.message && (
                                <Alert
                                    type="warning" showIcon
                                    message="Last bootstrap attempt did not find Prometheus"
                                    description={monitoringState.message}
                                    style={{ marginBottom: 12 }}
                                />
                            )}
                            <Form
                                form={form}
                                layout="vertical"
                                initialValues={{
                                    monitoring: {
                                        autoBootstrap: true, preferPrometheus: true,
                                        allowMetricsServerFallback: false, skipOnOpenShift: false,
                                        thanosEnabled: false, thanosInsecure: false,
                                    },
                                    kerberos: { injectionMode: 'WEBHOOK' },
                                }}
                            >
                                <Collapse ghost items={monitoringCollapseItems} />
                            </Form>
                        </Card>
                    )}
                </Content>
            </Layout>

            <Modal
                title="Add Helm Repository"
                open={repositoryModalOpen}
                onCancel={() => setRepositoryModalOpen(false)}
                footer={
                    <Space>
                        <Button onClick={() => setRepositoryModalOpen(false)}>Cancel</Button>
                        <Button loading={repositoryTesting} onClick={handleTestRepo}>Test & login</Button>
                        <Button type="primary" loading={repositorySaving} onClick={handleSaveRepo}>Save repository</Button>
                    </Space>
                }
            >
                <Form layout="vertical" form={repoForm} initialValues={{ type: 'OCI', authMode: 'anonymous' }}>
                    <Row gutter={[12, 0]}>
                        <Col span={12}>
                            <Form.Item name="id" label="ID" rules={[required('id is required'), slug]}>
                                <Input placeholder="repo-id" getValueFromEvent={trim} />
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="name" label="Name" rules={[required('name is required')]}>
                                <Input placeholder="Monitoring repository" />
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={[12, 0]}>
                        <Col span={12}>
                            <Form.Item name="type" label="Type" rules={[required('type is required')]}>
                                <Select options={[{ value: 'OCI', label: 'OCI' }, { value: 'HTTP', label: 'HTTP' }]} />
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="url" label="URL" rules={[
                                required('url is required'),
                                ({ getFieldValue }) => ({
                                    validator(_, value) {
                                        if (!value) return Promise.resolve();
                                        const type = getFieldValue('type');
                                        if (type === 'OCI') {
                                            return /^[a-zA-Z0-9.-]+(:\d+)?(\/[a-zA-Z0-9._\-\/]+)?$/.test(String(value).trim())
                                                ? Promise.resolve()
                                                : Promise.reject('Enter a valid OCI URL like registry.clemlab.com/clemlabprojects/charts');
                                        }
                                        return url('Enter a valid HTTP(S) repo URL').validator(_, value);
                                    },
                                }),
                            ]}>
                                <Input placeholder="registry.example.com/project/charts" />
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={[12, 0]}>
                        <Col span={12}>
                            <Form.Item name="imageProject" label="Image project">
                                <Input placeholder="image-project" />
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="imageRegistryHostOverride" label="Registry host override">
                                <Input placeholder="registry.override.com" />
                            </Form.Item>
                        </Col>
                    </Row>
                    <Row gutter={[12, 0]}>
                        <Col span={12}>
                            <Form.Item name="authMode" label="Auth mode" rules={[required('authMode is required')]}>
                                <Select options={[{ value: 'anonymous', label: 'Anonymous' }, { value: 'basic', label: 'Basic / Token' }]} />
                            </Form.Item>
                        </Col>
                        <Col span={12}>
                            <Form.Item name="username" label="Username / Token">
                                <Input placeholder="user or token" />
                            </Form.Item>
                        </Col>
                    </Row>
                    <Form.Item name="secret" label="Password / Token (stored encrypted)">
                        <Input.Password />
                    </Form.Item>
                </Form>
            </Modal>
        </>
    );
};

export default ConfigurationPage;
