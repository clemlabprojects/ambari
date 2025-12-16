import React from 'react';
import { Form, Input, Typography, Divider, Select, Radio, Card } from 'antd';
import DynamicFormField from '../ServiceInstallationModal/DynamicFormField';
import VolumeEditor from '../ServiceInstallationModal/VolumeEditor';

const InstallStep: React.FC<any> = ({ definition, data, onChange, mode, repos = [], securityProfiles = {} }) => {
  const [form] = Form.useForm();

  const deepMerge = (dst: any, src: any) => {
    Object.keys(src || {}).forEach(k => {
      const sv = src[k];
      if (sv && typeof sv === 'object' && !Array.isArray(sv)) {
        dst[k] = deepMerge({ ...(dst[k] || {}) }, sv);
      } else {
        dst[k] = sv;
      }
    });
    return dst;
  };

  // Sync data
  React.useEffect(() => { form.setFieldsValue(data); }, [data]);

  const onValuesChange = () => {
    // Take a fresh snapshot of all current form values (chart step fields only)
    const current = form.getFieldsValue(true) || {};
    // Merge into existing data to retain releaseName/namespace set in step 1
    const merged = deepMerge(JSON.parse(JSON.stringify(data || {})), current);
    onChange(merged);
  };

  if (mode === 'general') {
      return (
        <Form form={form} layout="vertical" onValuesChange={onValuesChange} initialValues={data}>
            <Typography.Title level={4}>Deployment Basics</Typography.Title>
            <Form.Item name="releaseName" label="Release Name" rules={[{ required: true }]}>
                <Input />
            </Form.Item>
            <Form.Item name="namespace" label="Kubernetes Namespace" rules={[{ required: true }]}>
                <Input />
            </Form.Item>
            <Form.Item name="chartOverride" label="Chart (override)" tooltip="Override the default chart reference; leave blank to use service.json chart">
                <Input placeholder={definition?.chart || 'repo/chart'} />
            </Form.Item>
            <Form.Item name="securityProfile" label="Security Profile" tooltip="Pick the auth profile to apply (LDAP/AD/OIDC truststore wiring)">
                <Select allowClear placeholder="Default profile">
                  {Object.keys(securityProfiles).map((p: string) => (
                    <Select.Option key={p} value={p}>{p}</Select.Option>
                  ))}
                </Select>
            </Form.Item>
            <Divider />
            <Form.Item name="repoId" label="Repository" rules={[{ required: true }]}> 
                <Select placeholder="Select repository">
                  {repos.map((r: any) => (
                    <Select.Option key={r.id} value={r.id}>{r.name || r.id}</Select.Option>
                  ))}
                </Select>
            </Form.Item>
            <Form.Item
              name="deploymentMode"
              label="Deployment mode"
              initialValue="DIRECT_HELM"
              tooltip="Direct Helm installs releases immediately; Flux GitOps commits manifests to Git."
              rules={[{ required: true }]}
            >
              <Radio.Group>
                <Radio value="DIRECT_HELM">Direct Helm</Radio>
                <Radio value="FLUX_GITOPS">Flux GitOps</Radio>
              </Radio.Group>
            </Form.Item>
            {form.getFieldValue('deploymentMode') === 'FLUX_GITOPS' && (
              <Card size="small" title="Flux / GitOps settings">
                <Form.Item name={['git','repoUrl']} label="Git repository URL" rules={[{ required: true, message: 'Repository is required for GitOps' }]}>
                  <Input placeholder="https://git.example.com/org/cluster-config.git" />
                </Form.Item>
                <Form.Item name={['git','baseBranch']} label="Base branch">
                  <Input placeholder="main" />
                </Form.Item>
                <Form.Item name={['git','pathPrefix']} label="Path prefix">
                  <Input placeholder="clusters/default" />
                </Form.Item>
                <Form.Item name={['git','branch']} label="Working branch">
                  <Input placeholder="feature/ambari-flux" />
                </Form.Item>
                <Form.Item name={['git','commitMode']} label="Commit mode" initialValue="DIRECT_COMMIT">
                  <Select>
                    <Select.Option value="DIRECT_COMMIT">Direct commit</Select.Option>
                    <Select.Option value="PR_MODE">Open PR (stub)</Select.Option>
                  </Select>
                </Form.Item>
                <Form.Item name={['git','authToken']} label="Auth token">
                  <Input.Password placeholder="Personal access token (optional if using SSH)" />
                </Form.Item>
                <Form.Item name={['git','sshKey']} label="SSH private key">
                  <Input.TextArea rows={3} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" />
                </Form.Item>
              </Card>
            )}
        </Form>
      );
  }

  const chartFields = (definition?.form || []).filter((f: any) => !['releaseName', 'namespace'].includes(f.name));
  const mountSpecs = Array.isArray(definition?.mounts) ? definition.mounts : [];

  return (
    <Form form={form} layout="vertical" onValuesChange={onValuesChange} initialValues={data}>
        {mode === 'storage' ? (
          <>
            <Typography.Title level={4}>Storage / Mounts</Typography.Title>
            {mountSpecs.length > 0 ? (
              <VolumeEditor specs={mountSpecs} />
            ) : (
              <Typography.Text type="secondary">No mount specifications defined.</Typography.Text>
            )}
          </>
        ) : (
          <>
            <Typography.Title level={4}>Chart Configuration</Typography.Title>
            {chartFields.length === 0 ? (
              <Typography.Text type="secondary">No chart fields defined.</Typography.Text>
            ) : (
              chartFields.map((f: any) => (
                <DynamicFormField key={f.name} field={f} />
              ))
            )}
          </>
        )}
    </Form>
  );
};
export default InstallStep;
