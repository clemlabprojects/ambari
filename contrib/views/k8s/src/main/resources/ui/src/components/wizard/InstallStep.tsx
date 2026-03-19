import React from 'react';
import { Form, Input, Typography, Divider, Select, Radio, Card } from 'antd';
import DynamicFormField from '../ServiceInstallationModal/DynamicFormField';
import VolumeEditor from '../ServiceInstallationModal/VolumeEditor';

interface InstallStepProps {
  definition: any;
  data: any;
  onChange: (updated: any) => void;
  mode: string;
  repos?: any[];
  securityProfiles?: Record<string, any>;
  vaultProfiles?: Record<string, any>;
}

const InstallStep: React.FC<InstallStepProps> = ({
  definition,
  data,
  onChange,
  mode,
  repos = [],
  securityProfiles = {},
  vaultProfiles = {},
}) => {
  const [form] = Form.useForm();

  /**
   * Merge source object into destination deeply, preserving nested objects and
   * overriding primitive leaves. This keeps previously entered fields from
   * other wizard steps while updating only the subset modified in the current
   * view. We avoid mutating caller objects by cloning before recursion.
   * The function intentionally treats arrays as leaf values to prevent
   * unexpected structural merges on ordered collections.
   * Returns the combined object so callers can chain or assign directly.
   * This helper is shared by the wizard to keep form state cohesive across
   * steps without losing the values already set by the user.
   * It deliberately remains tolerant of undefined/null inputs so calling code
   * can be concise when wiring Ant Design change handlers.
   * Think of this as a safe "update in place" that still respects immutability
   * for the props passed into the wizard.
   */
  const deepMerge = (destination: any, source: any) => {
    Object.keys(source || {}).forEach((key) => {
      const sourceValue = source[key];
      const isObject = sourceValue && typeof sourceValue === 'object' && !Array.isArray(sourceValue);
      if (isObject) {
        destination[key] = deepMerge({ ...(destination[key] || {}) }, sourceValue);
      } else {
        destination[key] = sourceValue;
      }
    });
    return destination;
  };

  /**
   * Keep the form in sync with external data changes (e.g., navigating back
   * or loading defaults). We set all field values rather than mutating
   * individual items to ensure Ant Design validation state stays consistent.
   */
  React.useEffect(() => {
    form.setFieldsValue(data);
  }, [data, form]);

  /**
   * Capture live form edits, merge them with existing wizard data from
   * previous steps, and notify the parent component. This prevents overwriting
   * release/namespace selections made earlier while still reflecting edits
   * in the current tab. A deep clone is used to avoid mutating props and to
   * keep Ant Design form state decoupled from parent state.
   * The handler is intentionally synchronous to provide immediate feedback
   * to downstream consumers (e.g., YAML preview or validation).
   * If a field is removed in the form definition, the merge logic will
   * preserve any existing value unless explicitly cleared by the user.
   * This mirrors the multi-step wizard expectation where each step is additive
   * and can be revisited without losing edits.
   */
  const onValuesChange = () => {
    const currentFormValues = form.getFieldsValue(true) || {};
    const mergedValues = deepMerge(JSON.parse(JSON.stringify(data || {})), currentFormValues);
    onChange(mergedValues);
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
            <Form.Item name="vaultProfile" label="Vault Profile" tooltip="Pick the Vault profile to apply (CSI secret injection)">
                <Select allowClear placeholder="No Vault profile">
                  {Object.keys(vaultProfiles).map((p: string) => (
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
                <Form.Item name={['git','repoId']} label="Git repository" tooltip="Select a saved Git repository">
                  <Select
                    placeholder="Select Git repository or enter URL manually"
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    onChange={(value) => {
                      if (value && typeof value === 'string' && value !== '__manual__') {
                        try {
                          const gitRepos = (window as any).__gitRepos || [];
                          const repo = gitRepos.find((r: any) => r.id === value);
                          if (repo) {
                            form.setFieldsValue({
                              git: {
                                ...form.getFieldValue('git'),
                                repoUrl: repo.url,
                                credentialAlias: repo.credentialAlias,
                              }
                            });
                          }
                        } catch (e) {
                          console.error('Failed to load Git repo', e);
                        }
                      }
                    }}
                  >
                    <Select.Option value="__manual__">Enter URL manually</Select.Option>
                    {(() => {
                      try {
                        const gitRepos = (window as any).__gitRepos || [];
                        return gitRepos.map((repo: any) => (
                          <Select.Option key={repo.id} value={repo.id} label={`${repo.name} (${repo.url})`}>
                            {repo.name} - {repo.url}
                          </Select.Option>
                        ));
                      } catch {
                        return null;
                      }
                    })()}
                  </Select>
                </Form.Item>
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
