// ui/src/pages/ConfigurationPage.tsx
import React from 'react';
import { Typography, Card, Upload, Button, Alert, Collapse, Space, Layout, message, Form, Input, InputNumber, Switch, Select, Tag, Modal, Row, Col, Steps } from 'antd';
import { UploadOutlined, FileTextOutlined, ApiOutlined, ReloadOutlined, PlayCircleOutlined, PlusOutlined, CheckCircleTwoTone, DisconnectOutlined } from '@ant-design/icons';
import { usePermissions } from '../hooks/usePermissions';
import { useClusterStatus } from '../context/ClusterStatusContext';
import { useNavigate } from 'react-router-dom';
import './Page.css';
import type { UploadRequestOption as RcCustomRequestOptions } from 'rc-upload/lib/interface';
import { installMonitoring, getMonitoringDiscovery, getHelmRepos, getViewSettings, saveViewSettings, saveHelmRepo, loginHelmRepo, API_BASE_URL } from '../api/client';
import { required, url, slug, trim } from "../utils/formRules";

const { Title, Paragraph, Text } = Typography;
const { Header, Content } = Layout;
const { Panel } = Collapse;

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

const ConfigurationPage: React.FC = () => {
    const { permissions } = usePermissions();
    const { status, fetchData, setClusterStatus, monitoringState } = useClusterStatus();
    const [form] = Form.useForm();
    const [repoForm] = Form.useForm();
    const [repos, setRepos] = React.useState<{ value: string; label: string }[]>([]);
    const [loadingRepos, setLoadingRepos] = React.useState(false);
    const [monitoringLoading, setMonitoringLoading] = React.useState(false);
    const [repoModalOpen, setRepoModalOpen] = React.useState(false);
    const [repoSaving, setRepoSaving] = React.useState(false);
    const [repoTesting, setRepoTesting] = React.useState(false);
    const [currentStep, setCurrentStep] = React.useState<number>(0);
    const [step1Done, setStep1Done] = React.useState(status !== 'unconfigured');
    const [step2Saved, setStep2Saved] = React.useState(false); // bootstrap
    const [step3Saved, setStep3Saved] = React.useState(false); // storage
    const [goDashLoading, setGoDashLoading] = React.useState(false);

    const repoSelectOptions = React.useMemo(() => {
        const options = [...repos];
        options.push({
            value: '__add_repo__',
            label: '+ Add repository',
        });
        return options;
    }, [repos]);

    const handleMonitoringRepoChange = (value: string | undefined) => {
        if (value === '__add_repo__') {
            const current = form.getFieldValue('monitoring') || {};
            form.setFieldsValue({ monitoring: { ...current, repoId: undefined } });
            setRepoModalOpen(true);
        }
    };

    const navButtons = (
    <Space>
      {currentStep > 0 && <Button onClick={() => setCurrentStep(currentStep - 1)}>Back</Button>}
      {currentStep < 2 && (
        <Button
          type="primary"
          onClick={async () => {
            if (currentStep === 1) {
              try {
                const vals = await form.validateFields();
                await saveViewSettings({
                  monitoring: {
                    autoBootstrap: vals.monitoring?.autoBootstrap,
                    preferPrometheus: vals.monitoring?.preferPrometheus,
                    skipOnOpenShift: vals.monitoring?.skipOnOpenShift,
                    preferOpenShiftMonitoring: vals.monitoring?.preferOpenShiftMonitoring,
                    repoId: vals.monitoring?.repoId,
                    prometheusHost: vals.monitoring?.prometheusHost,
                    prometheusIngressClass: vals.monitoring?.prometheusIngressClass,
                    prometheusNodePort: vals.monitoring?.prometheusNodePort,
                    thanos: {
                      enabled: vals.monitoring?.thanosEnabled,
                      bucket: vals.monitoring?.thanosBucket,
                      endpoint: vals.monitoring?.thanosEndpoint,
                      region: vals.monitoring?.thanosRegion,
                      accessKey: vals.monitoring?.thanosAccessKey,
                      secretKey: vals.monitoring?.thanosSecretKey,
                      insecure: vals.monitoring?.thanosInsecure
                    },
                    thanosHost: vals.monitoring?.thanosHost,
                    thanosIngressClass: vals.monitoring?.thanosIngressClass,
                    thanosNodePort: vals.monitoring?.thanosNodePort
                  }
                });
                setStep2Saved(true);
                setStep3Saved(!vals.monitoring?.thanosEnabled ? true : step3Saved);
                setCurrentStep(currentStep + 1);
              } catch (e:any) {
                Modal.confirm({
                  title: 'Save failed',
                  content: (
                    <Space direction="vertical">
                      <span>{e?.message || 'Failed to save monitoring settings.'}</span>
                      <span>You can continue to the next step, but monitoring overrides won’t be saved until this is fixed.</span>
                    </Space>
                  ),
                  okText: 'Continue anyway',
                  cancelText: 'Stay',
                  onOk: () => setCurrentStep(currentStep + 1)
                });
              }
              return;
            }
            setCurrentStep(currentStep + 1);
          }}
        >
          Next
        </Button>
      )}
        {status === 'connected' && step1Done && step2Saved && step3Saved && (
          <Button
            type="primary"
            loading={goDashLoading}
            onClick={async () => {
              setGoDashLoading(true);
              try {
                await fetchData(true);
                navigate('/');
              } finally {
                setGoDashLoading(false);
              }
            }}
            style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
          >
            Go to Dashboard
          </Button>
        )}
      </Space>
    );

    const refreshRepos = React.useCallback(async () => {
        setLoadingRepos(true);
        try {
            const r = await getHelmRepos();
            setRepos(r.map((x:any) => ({ value: x.id, label: `${x.name || x.id} (${x.type})` })));
        } catch (err) {
            console.error('Failed to load Helm repositories', err);
        } finally {
            setLoadingRepos(false);
        }
    }, []);

    const handleCustomRequest = (options: RcCustomRequestOptions) => {
        const { onSuccess, onError, file, onProgress } = options;

        const xhr = new XMLHttpRequest();

        xhr.upload.onprogress = event => {
            if (event.lengthComputable && onProgress) {
                const percent = Math.floor((event.loaded / event.total) * 100);
                onProgress({ percent });
            }
        };

        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                if (onSuccess) onSuccess(xhr.responseText, xhr);
            } else {
                if (onError) onError(new Error(`Error ${xhr.status}: ${xhr.statusText}`), xhr);
            }
        };
        
        xhr.onerror = () => {
            if (onError) onError(new Error('Failed to upload the kubeconfig yaml'), xhr);
        };

        xhr.open('POST', `${API_BASE_URL}/cluster/config`, true);
        xhr.setRequestHeader('X-Requested-By', 'ambari');
        xhr.setRequestHeader('Content-Type', 'application/octet-stream'); // Send the file as a binary stream
        xhr.send(file as Blob); // The file is directly the request body
    };

    React.useEffect(() => {
        void refreshRepos();
        const loadSettings = async () => {
            try {
                const s = await getViewSettings();
                form.setFieldsValue({
                    monitoring: {
                        autoBootstrap: s?.monitoring?.autoBootstrap ?? true,
                        preferPrometheus: s?.monitoring?.preferPrometheus ?? true,
                        skipOnOpenShift: s?.monitoring?.skipOnOpenShift ?? false,
                        repoId: s?.monitoring?.repoId,
                        prometheusHost: s?.monitoring?.prometheusHost,
                        prometheusIngressClass: s?.monitoring?.prometheusIngressClass ?? 'nginx',
                        prometheusNodePort: s?.monitoring?.prometheusNodePort,
                        thanosEnabled: s?.monitoring?.thanos?.enabled ?? false,
                        thanosBucket: s?.monitoring?.thanos?.bucket,
                        thanosEndpoint: s?.monitoring?.thanos?.endpoint,
                        thanosRegion: s?.monitoring?.thanos?.region,
                        thanosAccessKey: s?.monitoring?.thanos?.accessKey,
                        thanosSecretKey: s?.monitoring?.thanos?.secretKey,
                        thanosInsecure: s?.monitoring?.thanos?.insecure ?? false,
                        thanosHost: s?.monitoring?.thanosHost,
                        thanosIngressClass: s?.monitoring?.thanosIngressClass ?? 'nginx',
                        thanosNodePort: s?.monitoring?.thanosNodePort
                    }
                });
                if (s?.monitoring) {
                    setStep2Saved(true);
                    const thanosEnabled = s?.monitoring?.thanos?.enabled;
                    const hasThanosConfig = !!(s?.monitoring?.thanos?.bucket && s?.monitoring?.thanos?.endpoint);
                    setStep3Saved(!thanosEnabled || hasThanosConfig);
                }
            } catch {
                // ignore
            }
        };
        loadSettings();
    }, [form, refreshRepos]);

    const handleSaveRepo = React.useCallback(async () => {
        try {
            const vals = await repoForm.validateFields();
            const { secret, ...entity } = vals;
            setRepoSaving(true);
            await saveHelmRepo(entity, secret || undefined);
            message.success('Repository saved');
            setRepoModalOpen(false);
            repoForm.resetFields();
            await refreshRepos();
        } catch (e:any) {
            if (e?.errorFields) return;
            message.error(e?.message || 'Save failed');
        } finally {
            setRepoSaving(false);
        }
    }, [refreshRepos, repoForm]);

    const handleTestRepo = React.useCallback(async () => {
        try {
            const vals = await repoForm.validateFields();
            const { secret, ...entity } = vals;
            if (!entity.id) {
                throw new Error('Repository ID is required to test connectivity.');
            }
            setRepoTesting(true);
            await saveHelmRepo(entity, secret || undefined);
            await loginHelmRepo(entity.id);
            message.success('Repository saved and login verified');
            await refreshRepos();
        } catch (e:any) {
            if (e?.errorFields) return;
            message.error(e?.message || 'Test failed');
        } finally {
            setRepoTesting(false);
        }
    }, [refreshRepos, repoForm]);

    let navigate = useNavigate();
    const uploadProps = {
        name: 'file',
        customRequest: handleCustomRequest,
        showUploadList: true,
        onChange(info: any) {
            if (info.file.status === 'done') {
                message.success(`${info.file.name} successfully uploaded. Proceed to monitoring step or dashboard when ready.`);
                setClusterStatus('connected');
                // Move to step 2 so the user can configure monitoring right after upload.
                setCurrentStep(1);
                setStep1Done(true);
                // Kick off initial fetch so the dashboard has data on first visit
                void fetchData(true);
            } else if (info.file.status === 'error') {
                message.error(`Failed to upload ${info.file.name}.`);
            }
        },
    };

    if (!permissions?.canConfigure) {
        return <Alert message="Insufficient Permissions" description="You do not have the required permissions to configure this view." type="error" showIcon style={{ margin: 24 }} />;
    }

    // If already configured, default to step 2 on load
    React.useEffect(() => {
        if (status !== 'unconfigured') {
            setCurrentStep(1);
        }
    }, [status]);

    // A self-contained layout for the configuration page
    return (
      <>
        <Layout style={{ minHeight: '100vh', background: '#f0f2f5' }}>
            <Content style={{ padding: '24px', maxWidth: '1280px', minWidth: '1200px', margin: '0 auto' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                  <Space>
                    <ApiOutlined style={{color: '#1677ff', fontSize: '24px'}}/>
                    <Title level={4} style={{ margin: 0 }}>Ambari K8S View Configuration</Title>
                    {status === 'connected' ? (
                      <Tag color="green" icon={<CheckCircleTwoTone twoToneColor="#52c41a" twoToneColor2="#52c41a" />}>Connected</Tag>
                    ) : (
                      <Tag color="orange" icon={<DisconnectOutlined />}>Not connected</Tag>
                    )}
                  </Space>
                </div>
                <Steps
                  style={{ marginBottom: 16 }}
                  current={currentStep}
                  items={[
                    { title: 'Cluster configuration' },
                    { title: 'Monitoring bootstrap' },
                    { title: 'Monitoring storage' }
                  ]}
                />
                {currentStep === 0 && (
                  <Card title="Kubernetes Cluster Connection">
                    <Space direction="vertical" size="large" style={{ width: '100%' }}>
                        {status === 'unconfigured' && (
                            <Alert
                                message="Configuration required"
                                description="Please upload a valid kubeconfig file to continue."
                                type="info"
                                showIcon
                            />
                        )}
                        <Paragraph>
                            Upload the <code>kubeconfig</code> file here to enable the view to connect
                            to your Kubernetes or OpenShift cluster.
                        </Paragraph>
                        
                        <Upload {...uploadProps}>
                            <Button icon={<UploadOutlined />}>Select Kubeconfig File</Button>
                        </Upload>

                        <Collapse ghost>
                            <Panel 
                                header={<Text strong>View example file</Text>} 
                                key="1"
                            >
                                <pre style={{ backgroundColor: '#2b2b2b', color: '#f8f8f2', padding: '16px', borderRadius: '8px' }}>
                                    <code>{kubeconfigExample.trim()}</code>
                                </pre>
                            </Panel>
                        </Collapse>
                    </Space>
                  </Card>
                )}

                {currentStep === 1 && (
                  <Card
                    title="Monitoring bootstrap"
                    extra={navButtons}
                    style={{ marginTop: 16 }}
                    bodyStyle={{ maxHeight: '100vh', overflowY: 'auto' }}
                  >
                    <Space direction="vertical" size="large" style={{ width: '100%' }}>
                        {monitoringState?.state && (
                            <Alert
                                banner
                                message={
                                    <Space>
                                        <span>Monitoring bootstrap</span>
                                        <Tag color={monitoringState.state === 'COMPLETED' ? 'green' : monitoringState.state === 'FAILED' ? 'red' : 'blue'}>
                                            {monitoringState.state}
                                        </Tag>
                                    </Space>
                                }
                                description={monitoringState.message}
                                type={monitoringState.state === 'FAILED' ? 'error' : 'info'}
                                showIcon
                            />
                        )}
                        {repos.length === 0 && (
                            <Alert
                                type="warning"
                                showIcon
                                message="No Helm repository configured"
                                description={
                                    <Space direction="vertical">
                                        <span>Add a repository to bootstrap monitoring or install charts.</span>
                                        <Button
                                            icon={<PlusOutlined />}
                                            onClick={() => setRepoModalOpen(true)}
                                            size="small"
                                        >
                                            Add repository
                                        </Button>
                                    </Space>
                                }
                            />
                        )}
                        <Form
                          form={form}
                          layout="vertical"
                          initialValues={{
                            monitoring: {
                              autoBootstrap: true,
                              preferPrometheus: true,
                              skipOnOpenShift: false,
                              thanosEnabled: false,
                              thanosInsecure: false
                            }
                          }}
                        >
                          <Card size="small" bordered={false}>
                            <Paragraph>
                              Control how the view installs and uses monitoring. On OpenShift, built-in Thanos/Prometheus will be preferred when available.
                            </Paragraph>
                            <Row gutter={16}>
                              <Col xs={24} md={12}>
                                <Form.Item label="Auto-bootstrap monitoring stack" name={['monitoring','autoBootstrap']} valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                                <Form.Item label="Force Prometheus over metrics-server when available" name={['monitoring','preferPrometheus']} valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                                <Form.Item label="Skip bootstrap on OpenShift (use built-in monitoring)" name={['monitoring','skipOnOpenShift']} valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                                <Form.Item label="Use OpenShift monitoring when available" name={['monitoring','preferOpenShiftMonitoring']} valuePropName="checked">
                                    <Switch />
                                </Form.Item>
                                <Form.Item label="Helm repository for monitoring (optional override)" name={['monitoring','repoId']}>
                                    <Select
                                      allowClear
                                      loading={loadingRepos}
                                      placeholder="Use configured/default repo"
                                      options={repoSelectOptions}
                                      onChange={handleMonitoringRepoChange}
                                    />
                                </Form.Item>
                              </Col>
                              <Col xs={24} md={12}>
                                <Form.Item label="Prometheus ingress class (default: nginx)" name={['monitoring','prometheusIngressClass']}>
                                    <Input placeholder="nginx" />
                                </Form.Item>
                                <Form.Item label="Prometheus ingress host (optional)" name={['monitoring','prometheusHost']}>
                                    <Input placeholder="prometheus.example.com" />
                                </Form.Item>
                                <Form.Item label="Prometheus NodePort (optional fallback)" name={['monitoring','prometheusNodePort']}>
                                    <InputNumber min={30000} max={32767} style={{ width: '100%' }} placeholder="e.g. 30090" />
                                </Form.Item>
                              </Col>
                            </Row>
                            <Space wrap>
                                <Button
                                  type="primary"
                                  onClick={async () => {
                                    try {
                                        const vals = await form.validateFields();
                                        await saveViewSettings({
                                          monitoring: {
                                            autoBootstrap: vals.monitoring?.autoBootstrap,
                                            preferPrometheus: vals.monitoring?.preferPrometheus,
                                            skipOnOpenShift: vals.monitoring?.skipOnOpenShift,
                                            preferOpenShiftMonitoring: vals.monitoring?.preferOpenShiftMonitoring,
                                            repoId: vals.monitoring?.repoId,
                                            prometheusHost: vals.monitoring?.prometheusHost,
                                            prometheusIngressClass: vals.monitoring?.prometheusIngressClass,
                                            prometheusNodePort: vals.monitoring?.prometheusNodePort,
                                            thanos: {
                                              enabled: vals.monitoring?.thanosEnabled,
                                              bucket: vals.monitoring?.thanosBucket,
                                              endpoint: vals.monitoring?.thanosEndpoint,
                                              region: vals.monitoring?.thanosRegion,
                                              accessKey: vals.monitoring?.thanosAccessKey,
                                              secretKey: vals.monitoring?.thanosSecretKey,
                                              insecure: vals.monitoring?.thanosInsecure
                                            },
                                            thanosHost: vals.monitoring?.thanosHost,
                                            thanosIngressClass: vals.monitoring?.thanosIngressClass,
                                            thanosNodePort: vals.monitoring?.thanosNodePort
                                          }
                                        });
                                        message.success('Monitoring settings saved');
                                        setStep2Saved(true);
                                        setStep3Saved(!vals.monitoring?.thanosEnabled ? true : step3Saved);
                                      } catch (e:any) {
                                        message.error(e?.message || 'Save failed');
                                      }
                                  }}
                                >
                                  Save settings
                                </Button>
                                <Button
                                  icon={<PlayCircleOutlined />}
                                  loading={monitoringLoading}
                                  onClick={async () => {
                                    setMonitoringLoading(true);
                                    try {
                                        const repoId = form.getFieldValue(['monitoring','repoId']);
                                        await installMonitoring(repoId);
                                        message.success('Monitoring bootstrap requested');
                                        await fetchData(true);
                                      } catch (e:any) {
                                        message.error(e?.message || 'Bootstrap failed');
                                      } finally {
                                        setMonitoringLoading(false);
                                      }
                                  }}
                                >
                                  Run bootstrap now
                                </Button>
                                <Button icon={<ReloadOutlined />} onClick={() => fetchData(true)}>Refresh status</Button>
                            </Space>
                          </Card>
                        </Form>
                    </Space>
                  </Card>
                )}

                {currentStep === 2 && (
                  <Card
                    title="Monitoring storage (Thanos optional)"
                    extra={navButtons}
                    style={{ marginTop: 16 }}
                    bodyStyle={{ maxHeight: '100vh', overflowY: 'auto' }}
                  >
                    <Form form={form} layout="vertical">
                      <Alert
                        type="info"
                        showIcon
                        message="Long-term storage (optional)"
                        description="Configure S3-compatible storage for Prometheus blocks if you need long retention. Not required for KEDA autoscaling."
                        style={{ marginBottom: 12 }}
                      />
                      <Form.Item label="Enable Thanos long-term storage" name={['monitoring','thanosEnabled']} valuePropName="checked">
                          <Switch />
                      </Form.Item>
                      <Row gutter={16}>
                        <Col xs={24} md={12}>
                          <Form.Item label="S3 Bucket" name={['monitoring','thanosBucket']} rules={[({ getFieldValue }) => ({
                            validator(_, val) {
                              if (getFieldValue(['monitoring','thanosEnabled']) && !val) {
                                return Promise.reject("Bucket is required when Thanos is enabled");
                              }
                              return Promise.resolve();
                            }
                          })]}>
                              <Input placeholder="my-thanos-bucket" />
                          </Form.Item>
                          <Form.Item label="Region (optional)" name={['monitoring','thanosRegion']}>
                              <Input placeholder="us-east-1" />
                          </Form.Item>
                          <Form.Item label="Use insecure (HTTP) endpoint" name={['monitoring','thanosInsecure']} valuePropName="checked">
                              <Switch />
                          </Form.Item>
                          <Form.Item label="Thanos ingress class (default: nginx)" name={['monitoring','thanosIngressClass']}>
                              <Input placeholder="nginx" />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={12}>
                          <Form.Item label="S3 Endpoint" name={['monitoring','thanosEndpoint']} rules={[({ getFieldValue }) => ({
                            validator(_, val) {
                              if (getFieldValue(['monitoring','thanosEnabled']) && !val) {
                                return Promise.reject("Endpoint is required when Thanos is enabled");
                              }
                              return Promise.resolve();
                            }
                          })]}>
                              <Input placeholder="s3.amazonaws.com" />
                          </Form.Item>
                          <Form.Item label="Access key" name={['monitoring','thanosAccessKey']}>
                              <Input placeholder="ACCESS_KEY" />
                          </Form.Item>
                          <Form.Item label="Secret key" name={['monitoring','thanosSecretKey']}>
                              <Input.Password placeholder="SECRET_KEY" />
                          </Form.Item>
                          <Form.Item label="Thanos ingress host (optional)" name={['monitoring','thanosHost']}>
                              <Input placeholder="thanos.example.com" />
                          </Form.Item>
                          <Form.Item label="Thanos NodePort (optional fallback)" name={['monitoring','thanosNodePort']}>
                              <InputNumber min={30000} max={32767} style={{ width: '100%' }} placeholder="e.g. 30901" />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Space>
                        <Button
                          type="primary"
                          onClick={async () => {
                            try {
                              const vals = await form.validateFields();
                              await saveViewSettings({
                                monitoring: {
                                  autoBootstrap: vals.monitoring?.autoBootstrap,
                                  preferPrometheus: vals.monitoring?.preferPrometheus,
                                  skipOnOpenShift: vals.monitoring?.skipOnOpenShift,
                                  preferOpenShiftMonitoring: vals.monitoring?.preferOpenShiftMonitoring,
                                  repoId: vals.monitoring?.repoId,
                                  prometheusHost: vals.monitoring?.prometheusHost,
                                  prometheusIngressClass: vals.monitoring?.prometheusIngressClass,
                                  prometheusNodePort: vals.monitoring?.prometheusNodePort,
                                  thanos: {
                                    enabled: vals.monitoring?.thanosEnabled,
                                    bucket: vals.monitoring?.thanosBucket,
                                    endpoint: vals.monitoring?.thanosEndpoint,
                                    region: vals.monitoring?.thanosRegion,
                                    accessKey: vals.monitoring?.thanosAccessKey,
                                    secretKey: vals.monitoring?.thanosSecretKey,
                                    insecure: vals.monitoring?.thanosInsecure
                                  },
                                  thanosHost: vals.monitoring?.thanosHost,
                                  thanosIngressClass: vals.monitoring?.thanosIngressClass,
                                  thanosNodePort: vals.monitoring?.thanosNodePort
                                }
                              });
                              message.success('Storage settings saved');
                              setStep2Saved(true);
                              setStep3Saved(!vals.monitoring?.thanosEnabled || !!(vals.monitoring?.thanosBucket && vals.monitoring?.thanosEndpoint));
                            } catch (e:any) {
                              message.error(e?.message || 'Save failed');
                            }
                          }}
                        >
                          Save storage settings
                        </Button>
                      </Space>
                    </Form>
                  </Card>
                )}

                {currentStep === 0 && (
                  <div style={{ marginTop: 16, textAlign: 'right' }}>
                    {navButtons}
                  </div>
                )}
            </Content>
        </Layout>

        <Modal
          title="Add Helm Repository"
          open={repoModalOpen}
          onCancel={() => setRepoModalOpen(false)}
          footer={
            <Space>
              <Button onClick={() => setRepoModalOpen(false)}>Cancel</Button>
              <Button loading={repoTesting} onClick={handleTestRepo}>Test & login</Button>
              <Button type="primary" loading={repoSaving} onClick={handleSaveRepo}>Save repository</Button>
            </Space>
          }
        >
          <Form
            layout="vertical"
            form={repoForm}
            initialValues={{ type: 'OCI', authMode: 'anonymous' }}
          >
            <Row gutter={[12, 0]}>
              <Col span={12}>
                <Form.Item name="id" label="ID" rules={[required("id is required"), slug]}>
                  <Input placeholder="repo-id" getValueFromEvent={trim} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="name" label="Name" rules={[required("name is required")]}>
                  <Input placeholder="Monitoring repository" />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={[12, 0]}>
              <Col span={12}>
                <Form.Item name="type" label="Type" rules={[required("type is required")]}>
                  <Select
                    options={[
                      { value: 'OCI', label: 'OCI' },
                      { value: 'HTTP', label: 'HTTP' },
                    ]}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="url"
                  label="URL"
                  rules={[
                    required("url is required"),
                    ({ getFieldValue }) => ({
                      validator(_, value) {
                        if (!value) return Promise.resolve();
                        const type = getFieldValue("type");
                        if (type === "OCI") {
                          const v = String(value).trim();
                          const regex = /^[a-zA-Z0-9.-]+(:\d+)?(\/[a-zA-Z0-9._\-\/]+)?$/;
                          return regex.test(v)
                            ? Promise.resolve()
                            : Promise.reject("Enter a valid OCI URL like registry.clemlab.com/clemlabprojects/charts");
                        }
                        return url("Enter a valid HTTP(S) repo URL").validator(_, value);
                      },
                    }),
                  ]}
                >
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
                <Form.Item name="authMode" label="Auth mode" rules={[required("authMode is required")]}>
                  <Select
                    options={[
                      { value: "anonymous", label: "Anonymous" },
                      { value: "basic", label: "Basic / Token" }
                    ]}
                  />
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
