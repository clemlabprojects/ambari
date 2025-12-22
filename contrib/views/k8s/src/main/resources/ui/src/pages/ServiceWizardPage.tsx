import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Layout, Steps, Button, message, Spin, theme, Row, Col, Card, Segmented, Switch, Alert, Typography, Space, Progress, Modal } from 'antd';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import yaml from 'yaml';

import { getStackService, getStackConfigs, submitHelmDeploy, getHelmRepos, getSecurityConfig, type SecurityProfiles, getReleaseValues } from '../api/client';
import type { HelmRepo } from '../types';
import InstallStep from '../components/wizard/InstallStep';
import ConfigurationStep from '../components/wizard/ConfigurationStep';
import ReviewStep from '../components/wizard/ReviewStep';
import { applyBindingTargets, buildVarContext, deleteAtStr } from '../components/ServiceInstallationModal/bindings';
import BackgroundOperationsModal from '../components/common/BackgroundOperationsModal';
import { useClusterStatus } from '../context/ClusterStatusContext';

const { Text } = Typography;

const ServiceWizardPage: React.FC = () => {
  const { serviceName } = useParams<{ serviceName: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { token } = theme.useToken();
  const [current, setCurrent] = useState(0);
  
  // Data
  const [def, setDef] = useState<any>(null);
  const [configs, setConfigs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [securityProfiles, setSecurityProfiles] = useState<SecurityProfiles>({ defaultProfile: undefined, profiles: {} });

  // State
  const [installValues, setInstallValues] = useState<any>({}); // Step 1 & 2 data
  const [configOverrides, setConfigOverrides] = useState<Record<string, any>>({}); // Step 3 data

  // YAML preview / editor state (mirrors the modal behaviour)
  const [view, setView] = useState<'preview' | 'editor'>('preview');
  const [editorYaml, setEditorYaml] = useState('');
  const [editMode, setEditMode] = useState(false);
  const [parseError, setParseError] = useState<string | null>(null);
  const isDirtyRef = useRef(false);
  const parsedRef = useRef<Record<string, any> | null>(null);
  const [previewModalOpen, setPreviewModalOpen] = useState(false);

  // Command status drawer
  const [showOpsModal, setShowOpsModal] = useState(false);
  const [lastCommandId, setLastCommandId] = useState<string | undefined>(undefined);
  const { refresh: refreshCluster } = useClusterStatus();

  const upgradeState = (location.state as any)?.mode === 'upgrade' ? (location.state as any) : undefined;
  const isUpgrade = !!upgradeState;

  useEffect(() => {
    if (!serviceName) return;
    const load = async () => {
        try {
            const svcDef = await getStackService(serviceName);
            const svcCfgs = await getStackConfigs(serviceName);
            setDef(svcDef);
            setConfigs(svcCfgs);
            // Repos are best-effort; don't fail the page if repo API errors
            try {
              const repoList = await getHelmRepos();
              const sorted = (repoList || []).slice().sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
              setRepos(sorted);
              if (sorted.length > 0) {
                setInstallValues((prev: any) => ({ ...prev, repoId: prev.repoId || sorted[0].id }));
              }
            } catch (repoErr: any) {
              console.warn('Repo load failed', repoErr);
            }
            // Load security profiles
            try {
              const sec = await getSecurityConfig();
            setSecurityProfiles(sec);
            } catch (e: any) {
              console.warn('Security profiles load failed', e);
            }

            // Initialize defaults
            const initial: any = { releaseName: serviceName.toLowerCase(), namespace: 'dashboarding', deploymentMode: 'DIRECT_HELM' };
            if (upgradeState) {
              initial.releaseName = upgradeState.releaseName || initial.releaseName;
              initial.namespace = upgradeState.namespace || initial.namespace;
              initial.deploymentMode = upgradeState.deploymentMode || 'DIRECT_HELM';
              if (upgradeState.repoId) {
                initial.repoId = upgradeState.repoId;
              }
              if (upgradeState.git) {
                initial.git = upgradeState.git;
              }
              if (upgradeState.securityProfile) {
                initial.securityProfile = upgradeState.securityProfile;
              }
            }
            const applyDefaults = (fields: any[], target: any) => {
              fields.forEach(f => {
                if (f.type === 'group' && Array.isArray((f as any).fields)) {
                  applyDefaults((f as any).fields, target);
                } else if ((f as any).defaultValue !== undefined) {
                  const parts = f.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
                  let cur = target;
                  for (let i = 0; i < parts.length - 1; i++) {
                    cur[parts[i]] = cur[parts[i]] || {};
                    cur = cur[parts[i]];
                  }
                  cur[parts[parts.length - 1]] = (f as any).defaultValue;
                }
              });
            };
            if (Array.isArray(svcDef?.form)) applyDefaults(svcDef.form, initial);
            // Seed mount defaults from service definition (if any).
            // These defaults mirror the mounts section in service.json so the user sees a prefilled volume editor.
            if (Array.isArray((svcDef as any)?.mounts) && (svcDef as any).mounts.length > 0) {
              const mountObj: any = {};
              (svcDef as any).mounts.forEach((m: any) => {
                if (!m?.key) return;
                mountObj[m.key] = {
                  ...(m.defaults || {}),
                  mountPath: m.defaultMountPath || '/data'
                };
              });
              initial.mounts = mountObj;
            }
            // Seed TLS defaults from service definition (if any).
            // This keeps TLS entries visible in the form and makes bindings able to pick up default values,
            // including resolved secret names so the user sees sensible defaults.
            const resolveTpl = (tpl: string | undefined, ctx: any) =>
              (tpl || '').replace(/{{\s*([^}]+)\s*}}/g, (_m, raw) => {
                const path = String(raw || '').trim().split('.').filter(Boolean);
                let cur: any = ctx;
                for (const p of path) {
                  if (cur == null) return '';
                  cur = cur[p];
                }
                return cur == null ? '' : String(cur);
              });
            if (Array.isArray((svcDef as any)?.tls) && (svcDef as any).tls.length > 0) {
              const tlsObj: any = {};
              (svcDef as any).tls.forEach((t: any) => {
                if (!t?.key) return;
                const secretName = t.secretNameTemplate
                  ? resolveTpl(t.secretNameTemplate, initial)
                  : `${initial.releaseName}-${t.key}-tls`;
                const pwdSecret = (t.defaults && t.defaults.passwordSecretName)
                  ? t.defaults.passwordSecretName
                  : `${secretName}-pass`;
                tlsObj[t.key] = {
                  ...(t.defaults || {}),
                  secretName,
                  passwordSecretName: pwdSecret,
                  keystoreKey: (t.defaults && t.defaults.keystoreKey) || t.keystoreKey || 'keystore.p12',
                  passwordKey: (t.defaults && t.defaults.passwordKey) || t.passwordKey || 'truststore.password',
                  keystorePath: (t.defaults && t.defaults.keystorePath) || t.keystorePath || '/etc/security/tls/https-keystore.p12',
                  mountPath: (t.defaults && t.defaults.mountPath) || t.mountPath || '/etc/security/tls/https-keystore.p12'
                };
              });
              initial.tls = tlsObj;
            }
            setInstallValues(initial);
        } catch(e) { message.error("Failed to load definition"); }
        finally { setLoading(false); }
    };
    load();
  }, [serviceName]);

  // When launched in upgrade mode, pre-load current values from the existing release
  useEffect(() => {
    if (!isUpgrade || !upgradeState || !upgradeState.releaseName || !upgradeState.namespace) return;
    void (async () => {
      try {
        const currentValues = await getReleaseValues(upgradeState.namespace, upgradeState.releaseName);
        const rawYaml = yaml.stringify(currentValues || {});
        parsedRef.current = currentValues || {};
        setEditorYaml(rawYaml);
        setView('editor');
        setEditMode(true);
        setParseError(null);
      } catch (e: any) {
        message.error(e?.message || 'Failed to load current values for upgrade');
      }
    })();
  }, [isUpgrade, upgradeState]);

  // --- helpers ---
  const getExcludedPaths = (fields: any[]): string[] => {
    let paths: string[] = [];
    fields.forEach(f => {
      if ((f as any).excludeFromValues) paths.push(f.name);
      if (f.type === 'group' && (f as any).fields) {
        paths = [...paths, ...getExcludedPaths((f as any).fields)];
      }
    });
    return paths;
  };

  const previewYaml = useMemo(() => {
    if (!def) return '';
    try {
      const merged = JSON.parse(JSON.stringify(installValues || {}));

      // Apply bindings same way as the modal
      const varCtx = buildVarContext((def as any)?.variables, installValues, installValues?.mounts || {});
      applyBindingTargets(merged, (def as any)?.bindings || [], installValues?.mounts || {}, installValues, installValues?.releaseName || '', varCtx);

      // Drop fields that should not end up in values.yaml
      if (Array.isArray(def.form)) {
        getExcludedPaths(def.form).forEach(p => deleteAtStr(merged, p));
      }

      return yaml.stringify(merged, { aliasDuplicateObjects: false });
    } catch (e) {
      return '# (preview unavailable)';
    }
  }, [installValues, def]);

  useEffect(() => {
    if (!editMode && !isDirtyRef.current) {
      setEditorYaml(previewYaml);
      setParseError(null);
    }
  }, [previewYaml, editMode]);

  const onEditorChange = (value?: string) => {
    isDirtyRef.current = true;
    const text = value ?? '';
    setEditorYaml(text);
    try {
      const parsed = yaml.parse(text || '') || {};
      parsedRef.current = parsed;
      setParseError(null);
    } catch (e: any) {
      parsedRef.current = null;
      setParseError(e?.message || 'Invalid YAML');
    }
  };

  const buildFinalValues = () => {
    // Start from form values
    const base = JSON.parse(JSON.stringify(installValues || {}));

    // Apply bindings same way as preview
    const varCtx = buildVarContext((def as any)?.variables, installValues, installValues?.mounts || {});
    applyBindingTargets(base, (def as any)?.bindings || [], installValues?.mounts || {}, installValues, installValues?.releaseName || '', varCtx);

    // Drop fields that should not end up in values.yaml
    if (Array.isArray(def?.form)) {
      getExcludedPaths(def.form).forEach(p => deleteAtStr(base, p));
    }

    // If YAML editor overrides are present and valid, merge them in
    if (editMode && parsedRef.current && !parseError) {
      return { ...base, ...parsedRef.current };
    }
    return base;
  };

  const handleDeploy = async () => {
      setSubmitting(true);
      try {
          const finalValues = buildFinalValues();
          const chartRef = (installValues.chartOverride && String(installValues.chartOverride).trim()) || def.chart;
          const params = new URLSearchParams();
          if (installValues.repoId) params.set('repoId', installValues.repoId as string);
          if ((def as any)?.version) params.set('version', String((def as any).version));

          const { id } = await submitHelmDeploy({
              chart: chartRef,
              releaseName: installValues.releaseName,
              namespace: installValues.namespace,
              values: finalValues, 
              // Send Step 3 data to backend
              stackConfigOverrides: configOverrides,
              // Required to trigger the Config Materialize step
              serviceKey: serviceName,
              // Pass image pull secret if defined by the service definition or form
              secretName: (def as any)?.secretName || (installValues as any)?.secretName || undefined,
              endpoints: (def as any)?.endpoints || undefined,
              mounts: isUpgrade ? null : ((installValues as any)?.mounts || (def as any)?.mounts || null),
              dependencies: isUpgrade ? null : ((def as any)?.dependencies || null),
              ranger: isUpgrade ? null : ((def as any)?.ranger || null),
              requiredConfigMaps: isUpgrade ? null : ((def as any)?.requiredConfigMaps || null),
              dynamicValues: isUpgrade ? null : ((def as any)?.dynamicValues || null),
              tls: (installValues as any)?.tls || undefined,
              // Build optional ingress TLS upload payload:
              // - Require both cert and key.
              // - Secret name priority: user-provided -> ingress.tlsSecret -> <release>-ingress-tls.
              ingressTlsUpload: (() => {
                const tlsUpload = (installValues as any)?.ingress?.tlsUpload || {};
                const certPem = tlsUpload.cert || '';
                const keyPem = tlsUpload.key || '';
                if (!certPem || !keyPem) return undefined;
                const chosenSecret =
                  tlsUpload.secretName ||
                  (installValues as any)?.ingress?.tlsSecret ||
                  `${installValues.releaseName}-ingress-tls`;
                return { certPem, keyPem, secretName: chosenSecret };
              })(),
              // Only send securityProfile if user explicitly picked one.
              securityProfile: (installValues as any)?.securityProfile || undefined,
              deploymentMode: (installValues as any)?.deploymentMode || 'DIRECT_HELM',
              git: (installValues as any)?.git || undefined,
          }, params);
      message.success('Deployment request submitted');
      setLastCommandId(id);
      setShowOpsModal(true);
  } catch(e: any) {
      message.error("Deploy failed: " + e.message);
      } finally {
          setSubmitting(false);
      }
  };

  const steps = React.useMemo(() => {
    if (!def) return [];
    return [
      { title: isUpgrade ? 'Upgrade – General' : 'General Info', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="general" repos={repos} securityProfiles={securityProfiles.profiles} /> },
      { title: 'Storage', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="storage" repos={repos} /> },
      { title: 'Chart Settings', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="chart" repos={repos} /> },
      { title: 'Configuration', content: <ConfigurationStep configs={configs} overrides={configOverrides} onChange={setConfigOverrides} /> },
      { title: 'Review', content: <ReviewStep def={def} install={installValues} repoLabel={repos.find(r => r.id === installValues.repoId)?.name || installValues.repoId} /> },
    ];
  }, [def, installValues, repos, configs, configOverrides]);

  // Disable Next if any required password in overrides is empty or mismatched
  const hasInvalidPasswords = React.useMemo(() => {
    let invalid = false;
    configs.forEach(cfg => {
      (cfg.properties || []).forEach((prop: any) => {
        if (prop.type === 'password' && prop.required) {
          const key = `${cfg.name}/${prop.name}`;
          const val = configOverrides[key];
          // In PropertyRenderer we enforce confirm match; here just ensure present
          if (!val || val === '') {
            invalid = true;
          }
        }
      });
    });
    return invalid;
  }, [configs, configOverrides]);

  return (
    <>
    <Layout style={{ padding: '24px', background: '#fff', maxHeight: 'calc(100vh - 120px)', overflowY: 'auto', position: 'relative' }}>
      {(loading || !def) && (
        <>
          <div style={{ position: 'absolute', top: 0, left: 0, right: 0, zIndex: 10 }}>
            <Progress percent={60} status="active" showInfo={false} strokeColor="#1677ff" />
          </div>
          <div style={{ position: 'absolute', inset: 0, background: 'rgba(255,255,255,0.65)', zIndex: 9, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Spin size="large" />
          </div>
        </>
      )}
      <Row gutter={16}>
        <Col span={16}>
          {(loading || !def) ? (
            <div style={{textAlign:'center', marginTop: 100}}><Spin size="large"/></div>
          ) : (
          <>
          <Card bordered style={{ marginBottom: 12, padding: '8px 12px' }} bodyStyle={{ padding: 0 }}>
            <Steps
              size="small"
              current={current}
              onChange={(v) => setCurrent(v)}
              items={steps.map(s => ({ title: s.title }))}
            />
          </Card>
          <div style={{ minHeight: 440, border: `1px solid ${token.colorBorderSecondary}`, padding: 16, borderRadius: 8 }}>
            {steps[current].content}
          </div>

          <div style={{ marginTop: 16, display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            {current > 0 && <Button onClick={() => setCurrent(current - 1)} disabled={submitting}>Previous</Button>}
      {current < steps.length - 1 && 
       <Button type="primary" onClick={() => setCurrent(current + 1)} disabled={current === 3 && hasInvalidPasswords}>Next</Button>}
            {current === steps.length - 1 && 
             <Button
               type="primary"
               style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
               onClick={handleDeploy}
               loading={submitting}
             >
               Deploy
             </Button>}
          </div>
          </>
          )}
        </Col>

        <Col span={8}>
          <Card
            title={<span>values.yaml preview</span>}
            extra={<Button size="small" onClick={() => setPreviewModalOpen(true)}>Open large view</Button>}
          >
            <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <Segmented
                value={view}
                onChange={(v) => setView(v as 'preview' | 'editor')}
                options={[{ label: 'Preview', value: 'preview' }, { label: 'Editor', value: 'editor' }]}
              />
              <Space>
                <Text type="secondary">Editable</Text>
                <Switch checked={editMode} onChange={(v) => { setEditMode(v); if (!v) { setParseError(null); isDirtyRef.current = false; } }} />
              </Space>
            </div>
            {view === 'preview' && (
              <Editor
                height="420px"
                language="yaml"
                value={previewYaml}
                options={{ readOnly: true, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false }}
              />
            )}
            {view === 'editor' && (
              <>
                <Editor
                  height="420px"
                  language="yaml"
                  value={editorYaml || previewYaml}
                  onChange={onEditorChange}
                  options={{ readOnly: !editMode, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false, wordWrap: 'on' }}
                />
                {parseError && (
                  <Alert style={{ marginTop: 8 }} type="error" showIcon message="YAML error" description={<pre style={{ margin: 0 }}>{parseError}</pre>} />
                )}
              </>
            )}
          </Card>
        </Col>
      </Row>
    </Layout>
    <div style={{ position: 'fixed', right: 24, bottom: 24, zIndex: 1000, display: 'flex', gap: 8 }}>
      {current > 0 && <Button onClick={() => setCurrent(current - 1)} disabled={submitting}>Previous</Button>}
      {current < steps.length - 1 && 
       <Button type="primary" onClick={() => setCurrent(current + 1)} disabled={current === 3 && hasInvalidPasswords}>Next</Button>}
      {current === steps.length - 1 && 
       <Button
         type="primary"
         style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
         onClick={handleDeploy}
         loading={submitting}
       >
         Deploy
       </Button>}
    </div>
    <Modal
      open={previewModalOpen}
      onCancel={() => setPreviewModalOpen(false)}
      footer={null}
      width="90%"
      bodyStyle={{ height: '80vh' }}
      title="values.yaml preview (full view)"
      destroyOnClose={false}
    >
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Segmented
          value={view}
          onChange={(v) => setView(v as 'preview' | 'editor')}
          options={[{ label: 'Preview', value: 'preview' }, { label: 'Editor', value: 'editor' }]}
        />
        <Space>
          <Text type="secondary">Editable</Text>
          <Switch checked={editMode} onChange={(v) => { setEditMode(v); if (!v) { setParseError(null); isDirtyRef.current = false; } }} />
        </Space>
      </div>
      {view === 'preview' && (
        <Editor
          height="68vh"
          language="yaml"
          value={previewYaml}
          options={{ readOnly: true, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false }}
        />
      )}
      {view === 'editor' && (
        <>
          <Editor
            height="68vh"
            language="yaml"
            value={editorYaml || previewYaml}
            onChange={onEditorChange}
            options={{ readOnly: !editMode, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false, wordWrap: 'on' }}
          />
          {parseError && (
            <Alert style={{ marginTop: 8 }} type="error" showIcon message="YAML error" description={<pre style={{ margin: 0 }}>{parseError}</pre>} />
          )}
        </>
      )}
    </Modal>
    <BackgroundOperationsModal
      open={showOpsModal}
      onClose={() => setShowOpsModal(false)}
      watchCommandId={lastCommandId}
      onAutoClose={() => {
        setShowOpsModal(false);
        refreshCluster();
        navigate('/helm');
      }}
    />
    </>
  );
};
export default ServiceWizardPage;
