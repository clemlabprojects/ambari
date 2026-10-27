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
import { Alert, Form, Input, Typography, Divider, Select, Radio, Card } from 'antd';
import DynamicFormField from '../ServiceInstallationModal/DynamicFormField';
import VolumeEditor from '../ServiceInstallationModal/VolumeEditor';
import { resolveAuthCascade, applyAuthCascadeToFields } from '../ServiceInstallationModal';
import { ExternalAuthTargetsContext, ContextLinkedFieldsContext, ResolvedContextValuesContext, type ResolvedContextInfo } from '../ServiceInstallationModal/ExternalAuthTargetsContext';
import { FieldSyncContext, UpgradeModeContext } from '../ServiceInstallationModal/fieldSync';
import { BASE_BRANCH_TOOLTIP, COMMIT_MODE_LABELS, DEFAULT_BASE_BRANCH, WORKING_BRANCH_TOOLTIP, opensPullRequest, workingBranchProblem } from '../../utils/gitOpsBranches';
import { namespaceRules, releaseNameRules } from '../../utils/nameRules';
import GitSshFields from '../common/GitSshFields';
import { savedRepoGitFields } from '../../utils/gitSsh';
import { listGitOpsProfiles, type GitOpsProfile, type KubernetesCluster } from '../../api/client';
import { useTargetCluster } from '../../context/TargetClusterContext';
import { DEPLOYMENT_MODE_LABELS, DIRECT_HELM, GITOPS_FLUX, GITOPS_LAYOUT, savedGitRepos, writesToGit } from '../../utils/deploymentModes';

interface InstallStepProps {
  definition: any;
  data: any;
  onChange: (updated: any) => void;
  mode: string;
  repos?: any[];
  securityProfiles?: Record<string, any>;
  resolvedContext?: ResolvedContextInfo | null;
  /** Upgrading a deployed release (discovery must not replace its values). */
  upgrade?: boolean;
  /** How the release being upgraded was deployed; a release deployed with Flux GitOps keeps its mode. */
  deployedMode?: string;
  /** Rendered inside another panel (the sizing dialog): the chart fields only, without the step's title. */
  embedded?: boolean;
  /** The Kubernetes clusters KDPS knows: with more than one, the general step asks which one to deploy to. */
  kubernetesClusters?: KubernetesCluster[];
}

const InstallStep: React.FC<InstallStepProps> = ({
  definition,
  data,
  onChange,
  mode,
  repos = [],
  securityProfiles = {},
  resolvedContext,
  upgrade = false,
  deployedMode,
  embedded = false,
  kubernetesClusters = [],
}) => {
  const [form] = Form.useForm();
  // The cluster chosen above: a Flux release left without a path prefix goes to its folder.
  const targetCluster = useTargetCluster();

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

  const heldInGit = upgrade && deployedMode === 'FLUX_GITOPS';

  // The GitOps profiles a deploy can write the release with (see the GitOps Profiles page).
  const [gitOpsProfiles, setGitOpsProfiles] = React.useState<GitOpsProfile[]>([]);
  React.useEffect(() => {
    if (mode !== 'general') return;
    listGitOpsProfiles().then(r => setGitOpsProfiles(r.items || [])).catch(() => setGitOpsProfiles([]));
  }, [mode]);
  const deploymentMode: string | undefined = form.getFieldValue('deploymentMode') ?? data?.deploymentMode;
  // The profiles that describe a repository layout; the built-in Flux one is the "GitOps with Flux" mode.
  const layoutProfiles = gitOpsProfiles.filter(p => p.kind === 'files');
  // A file profile decides how its deploys are delivered and where they are written.
  const filesProfile = layoutProfiles.find(p => p.name === form.getFieldValue(['git', 'profile']));
  // Read when the step is shown, so a repository saved since is listed.
  const savedRepos = savedGitRepos();

  if (mode === 'general') {
      return (
        <FieldSyncContext.Provider value={onValuesChange}>
        <Form form={form} size="large" layout="vertical" onValuesChange={onValuesChange} initialValues={data}>
            <Typography.Title level={4}>Deployment Basics</Typography.Title>
            {/* Asked only when KDPS knows several clusters; a release stays on the cluster it was deployed to. */}
            {!upgrade && kubernetesClusters.length > 1 && (
              <Form.Item name="kubernetesCluster" label="Kubernetes cluster" initialValue="default"
                tooltip="The cluster the service is deployed to. What the wizard finds (Secrets, services, operators) is read on it.">
                <Select options={kubernetesClusters.map(c => ({
                  value: c.id,
                  label: c.configured ? c.name : `${c.name} (no connection yet)`,
                  disabled: !c.configured,
                }))} />
              </Form.Item>
            )}
            {/* Helm and Kubernetes refuse other names; a GitOps deploy writes them into Git. Checked again by the
                server, these rules only warn as the name is typed. */}
            <Form.Item name="releaseName" label="Release Name" rules={releaseNameRules(definition)}>
                <Input />
            </Form.Item>
            <Form.Item name="namespace" label="Kubernetes Namespace" rules={namespaceRules()}>
                <Input />
            </Form.Item>
            <Form.Item name="chartOverride" label="Chart (override)" tooltip="Override the default chart reference; leave blank to use service.json chart">
                <Input placeholder={definition?.chart || 'repo/chart'} />
            </Form.Item>
            <Form.Item name="version" label="Version (override)" tooltip="Pin a specific chart version (e.g. 0.12.39) sent as helm --version. Leave blank for the catalog default / latest. For OCI charts do NOT put the version in the Chart field — use this.">
                <Input placeholder={definition?.version ? `default: ${definition.version}` : 'latest / catalog default'} />
            </Form.Item>
            <Form.Item name="securityProfile" label="Security Profile" tooltip="Pick the auth profile to apply (LDAP/AD/OIDC truststore wiring)">
                <Select allowClear placeholder="Default profile">
                  {Object.keys(securityProfiles).map((p: string) => (
                    <Select.Option key={p} value={p}>{securityProfiles[p]?.displayName || p}</Select.Option>
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
            {/* A release written to Git is deployed from Git: a direct Helm upgrade would be undone, and the server refuses
                it. Its mode is kept: written with a profile, it keeps that profile too. */}
            <Form.Item
              name="deploymentMode"
              label="Deployment mode"
              initialValue={DIRECT_HELM}
              tooltip={heldInGit
                ? 'This release is written to Git, and deployed from there. To deploy it another way, uninstall it first.'
                : 'Direct Helm installs the release now. GitOps with Flux writes a HelmRelease that Flux applies. GitOps with a repository layout writes the chart and the files of a GitOps profile, for your Argo CD or CI to deploy.'}
              rules={[{ required: true }]}
            >
              <Radio.Group disabled={heldInGit}>
                <Radio value={DIRECT_HELM}>{DEPLOYMENT_MODE_LABELS[DIRECT_HELM]}</Radio>
                <Radio value={GITOPS_FLUX}>{DEPLOYMENT_MODE_LABELS[GITOPS_FLUX]}</Radio>
                <Radio value={GITOPS_LAYOUT}>{DEPLOYMENT_MODE_LABELS[GITOPS_LAYOUT]}</Radio>
              </Radio.Group>
            </Form.Item>
            {deploymentMode === GITOPS_LAYOUT && (
              <Card size="small" title="Repository layout" style={{ marginBottom: 16 }}>
                <Form.Item name={['git','profile']} label="GitOps profile"
                  rules={[{ required: true, message: 'Choose the profile that describes the layout of the repository' }]}
                  tooltip="The files the deploy writes around the chart, made on the GitOps Profiles page.">
                  <Select placeholder="Choose a profile" disabled={heldInGit}
                    notFoundContent="No profile yet: create one on the GitOps Profiles page."
                    options={layoutProfiles.map(p => ({ value: p.name, label: p.description ? `${p.name} — ${p.description}` : p.name }))} />
                </Form.Item>
                {filesProfile && (
                  <Alert type="info" showIcon
                    message={`Written as the profile ${filesProfile.name} says`}
                    description={<>
                      {filesProfile.chartPath ? <>The chart is copied to <code>{filesProfile.chartPath}</code>, with the profile's files around it. </> : 'The profile\'s files are written. '}
                      {filesProfile.delivery?.mode === 'pullRequest'
                        ? `The change is proposed in a pull request from a branch starting with ${filesProfile.delivery.branchPrefix || 'kdps/'}.`
                        : 'The change is committed to the base branch.'}
                      {' '}Your Argo CD or CI then deploys it. Check the files with Preview Git repository before deploying.
                    </>} />
                )}
              </Card>
            )}
            {writesToGit(deploymentMode) && (
              <Card size="small" title={deploymentMode === GITOPS_FLUX ? 'Flux settings' : 'Git repository'}>
                <Form.Item name={['git','repoId']} label="Saved Git repository" tooltip="A repository saved on the Git Repositories page fills in its URL and access.">
                  <Select
                    placeholder={savedRepos.length ? 'Choose a saved repository, or enter its URL below' : 'No saved repository: enter its URL below'}
                    allowClear
                    showSearch
                    optionFilterProp="label"
                    onChange={(value) => {
                      const repo = savedRepos.find(r => r.id === value);
                      if (repo) {
                        form.setFieldsValue({ git: { ...form.getFieldValue('git'), ...savedRepoGitFields(repo) } });
                        onValuesChange();
                      }
                    }}
                    options={savedRepos.map(r => ({ value: r.id, label: `${r.name} (${r.url})` }))}
                  />
                </Form.Item>
                <Form.Item name={['git','repoUrl']} label="Git repository URL" rules={[{ required: true, message: 'A GitOps deploy needs its Git repository' }]}>
                  <Input placeholder="https://git.example.com/org/cluster-config.git" />
                </Form.Item>
                <Form.Item name={['git','baseBranch']} label="Base branch" tooltip={BASE_BRANCH_TOOLTIP}>
                  <Input placeholder={DEFAULT_BASE_BRANCH} />
                </Form.Item>
                {deploymentMode === GITOPS_FLUX && (<>
                  <Form.Item name={['git','pathPrefix']} label="Path prefix"
                    tooltip="Left blank: clusters/<cluster id>, one folder per Kubernetes cluster, as Flux bootstrap lays them out.">
                    <Input placeholder={`clusters/${targetCluster || 'default'}`} />
                  </Form.Item>
                  <Form.Item name={['git','commitMode']} label="Commit mode" initialValue="DIRECT_COMMIT">
                    <Select>
                      <Select.Option value="DIRECT_COMMIT">{COMMIT_MODE_LABELS.DIRECT_COMMIT}</Select.Option>
                      <Select.Option value="PR_MODE">{COMMIT_MODE_LABELS.PR_MODE}</Select.Option>
                    </Select>
                  </Form.Item>
                  {/* Only a pull request has a working branch: a direct commit goes to the base branch. */}
                  {opensPullRequest(form.getFieldValue('git')) && (
                    <Form.Item
                      name={['git','branch']}
                      label="Working branch"
                      tooltip={WORKING_BRANCH_TOOLTIP}
                      dependencies={[['git','baseBranch']]}
                      rules={[({ getFieldValue }) => ({
                        validator: (_, value) => {
                          const problem = workingBranchProblem({ ...(getFieldValue('git') || {}), branch: value });
                          return problem ? Promise.reject(new Error(problem)) : Promise.resolve();
                        },
                      })]}
                    >
                      <Input placeholder="Left empty: a new branch for each change" />
                    </Form.Item>
                  )}
                </>)}
                <Form.Item name={['git','authToken']} label="Auth token">
                  <Input.Password placeholder="Personal access token (optional if using SSH)" />
                </Form.Item>
                <GitSshFields />
              </Card>
            )}
        </Form>
        </FieldSyncContext.Provider>
      );
  }

  const chartFields = (definition?.form || []).filter((f: any) => !['releaseName', 'namespace'].includes(f.name));
  const mountSpecs = Array.isArray(definition?.mounts) ? definition.mounts : [];

  // Resolve the Step 1 → Step 3 auth cascade for this service. When active:
  //  - apply disabled state to the target field (via applyAuthCascadeToFields)
  //  - render a blue info banner so the operator knows the form value isn't
  //    free-text. Server-side enforcement happens regardless via
  //    applySecurityOverrides (--set wins over values.yaml).
  const profileName: string | undefined = data?.securityProfile;
  const chosenProfileMode: string | undefined = profileName ? securityProfiles?.[profileName]?.mode : undefined;
  const authCascade = resolveAuthCascade(definition, chosenProfileMode);
  const cascadedChartFields = applyAuthCascadeToFields(chartFields, authCascade);
  // Fields whose behaviour depends on the selected Platform Context — i.e. a field named
  // as the `when` trigger of a requiresContext rule (e.g. atlasFederation.enabled). We give
  // these a light accent + a cue so the operator sees the toggle⇄context dependency.
  const contextLinkedFields = new Set<string>(
    ((definition as any)?.requiresContext || [])
      .map((r: any) => r?.when)
      .filter((w: any): w is string => typeof w === 'string' && w.length > 0)
  );
  // Step-1 incompatibility: service has authModes declared but no mapping for the chosen mode.
  const authBlock = (definition as any)?.securityCoupling?.authModes;
  const cascadeMismatch = Boolean(
    chosenProfileMode && authBlock?.field && authBlock?.mappings && !authBlock.mappings[chosenProfileMode]
  );

  return (
    <ExternalAuthTargetsContext.Provider value={(definition as any)?.externalServiceTargets}>
    <ResolvedContextValuesContext.Provider value={resolvedContext || undefined}>
    <ContextLinkedFieldsContext.Provider value={contextLinkedFields}>
    <FieldSyncContext.Provider value={onValuesChange}>
    <UpgradeModeContext.Provider value={upgrade}>
        <Form form={form} size="large" layout="vertical" onValuesChange={onValuesChange} initialValues={data}>
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
            {!embedded && <Typography.Title level={4}>Chart Configuration</Typography.Title>}
            {/* Verbose TLS / auth-cascade explanatory alerts removed — the TLS mode + auth
                field are still pre-selected/locked server-side; the inline field help covers it
                without a wall of warning text at the top of every step-3 view. The actionable
                mismatch warning below is kept. */}
            {cascadeMismatch && !embedded && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message="No auth-mode mapping for the selected security profile"
                description={
                  <span>
                    Profile mode <strong>{chosenProfileMode}</strong> is not declared in this service's{' '}
                    <code>securityCoupling.authModes</code>. The auth field is left untouched — set it manually if needed.
                  </span>
                }
              />
            )}
            {cascadedChartFields.length === 0 ? (
              <Typography.Text type="secondary">No chart fields defined.</Typography.Text>
            ) : (
              // DynamicFormField applies the context-link accent itself (via
              // ContextLinkedFieldsContext) so it reaches fields nested inside groups too.
              cascadedChartFields.map((f: any) => (
                <DynamicFormField key={f.name} field={f} />
              ))
            )}
          </>
        )}
    </Form>
    </UpgradeModeContext.Provider>
        </FieldSyncContext.Provider>
    </ContextLinkedFieldsContext.Provider>
    </ResolvedContextValuesContext.Provider>
    </ExternalAuthTargetsContext.Provider>
  );
};
export default InstallStep;
