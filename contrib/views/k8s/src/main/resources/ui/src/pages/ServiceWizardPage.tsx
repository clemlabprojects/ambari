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

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Layout, Steps, Button, message, notification, Spin, theme, Row, Col, Card, Segmented, Switch, Alert, Typography, Space, Progress, Modal, Select, Descriptions, Tag, Divider } from 'antd';
import { ApiOutlined, PlusOutlined, WarningOutlined } from '@ant-design/icons';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import Editor from '@monaco-editor/react';
import yaml from 'yaml';

import { getStackService, getStackConfigs, submitHelmDeploy, getHelmRepos, getSecurityConfig, type SecurityProfiles, getReleaseValues, getContexts, getResolvedContext, getContextAdvice, type PlatformContext, type ResolvedContext } from '../api/client';
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
  // Platform contexts (Atlas/Ranger integration target). Surfaced as a persistent
  // selector beside the YAML preview so the operator always sees/controls which ODP
  // platform a context-aware service (e.g. Atlas federation) integrates with.
  const [contexts, setContexts] = useState<PlatformContext[]>([]);
  const [resolvedSel, setResolvedSel] = useState<ResolvedContext | null>(null);
  const [ctxDetailsOpen, setCtxDetailsOpen] = useState(false);

  // State
  const [installValues, setInstallValues] = useState<any>({}); // Step 1 & 2 data
  const [configOverrides, setConfigOverrides] = useState<Record<string, any>>({}); // Step 3 data
  // Validity flag for each password-typed property, keyed by `${cfg}/${prop}`.
  // PropertyRenderer reports the boolean here whenever its local confirm state's
  // validity flips. The Next-button gate reads from this map to refuse advancing
  // when any password is empty, unconfirmed, or mismatched.
  //
  // We deliberately keep only the *boolean* in the wizard, not the confirm
  // string itself — lifting the string causes a re-render cascade through
  // Steps/Tabs on every keystroke and steals focus from the confirm input.
  const [passwordValidity, setPasswordValidity] = useState<Record<string, boolean>>({});

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

            // Load platform contexts (best-effort) for the persistent context selector.
            try {
              setContexts(await getContexts());
            } catch (e: any) {
              console.warn('Platform contexts load failed', e);
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
            // Seed Kerberos defaults (if any) so pre-provisioned mode can use them.
            if (Array.isArray((svcDef as any)?.kerberos) && (svcDef as any).kerberos.length > 0) {
              const kerberosObj: any = {};
              (svcDef as any).kerberos.forEach((k: any) => {
                if (!k?.key) return;
                const secretName = k.secretNameTemplate
                  ? resolveTpl(k.secretNameTemplate, initial)
                  : `${initial.releaseName}-keytab`;
                kerberosObj[k.key] = {
                  ...(k.defaults || {}),
                  enabled: k.enabled !== false,
                  principalTemplate: k.principalTemplate || '',
                  serviceName: k.serviceName || '',
                  secretName,
                  keyNameInSecret: k.keyNameInSecret || 'service.keytab',
                  mountPath: k.mountPath || '/etc/security/keytabs'
                };
              });
              initial.kerberos = kerberosObj;
            }
            setInstallValues(initial);
        } catch(e) { message.error("Failed to load definition"); }
        finally { setLoading(false); }
    };
    load();
  }, [serviceName]);

  // Pre-fill form fields that declare "defaultFromProfile" using the selected Security Profile.
  // Walks the form schema looking for fields with a `defaultFromProfile` path (dotted, looked up
  // inside the selected profile's SecurityConfigDTO, e.g. "oidc.principalDomain"). The value is
  // applied only when the form field is currently empty/undefined, so explicit user input is
  // preserved and switching profiles is non-destructive.
  useEffect(() => {
    if (!def || !installValues) return;
    const profileName = (installValues as any)?.securityProfile;
    if (!profileName) return;
    const profile = (securityProfiles?.profiles as any)?.[profileName];
    if (!profile) return;

    const getByPath = (obj: any, path: string): any => {
      if (!obj || !path) return undefined;
      return path.split('.').reduce<any>((acc, p) => (acc == null ? undefined : acc[p]), obj);
    };

    const collectProfileBackfills = (fields: any[], out: Array<[string[], any]>) => {
      fields.forEach(f => {
        if (f?.type === 'group' && Array.isArray(f.fields)) {
          collectProfileBackfills(f.fields, out);
          return;
        }
        const pPath = (f as any)?.defaultFromProfile;
        if (!pPath || typeof pPath !== 'string') return;
        const v = getByPath(profile, pPath);
        if (v === undefined || v === null || v === '') return;
        const parts = String(f.name).replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
        out.push([parts, v]);
      });
    };
    const backfills: Array<[string[], any]> = [];
    if (Array.isArray((def as any)?.form)) collectProfileBackfills((def as any).form, backfills);
    if (backfills.length === 0) return;

    setInstallValues((prev: any) => {
      const next = JSON.parse(JSON.stringify(prev || {}));
      let mutated = false;
      backfills.forEach(([parts, value]) => {
        let cur = next;
        for (let i = 0; i < parts.length - 1; i++) {
          if (cur[parts[i]] == null || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
          cur = cur[parts[i]];
        }
        const leaf = parts[parts.length - 1];
        const existing = cur[leaf];
        if (existing === undefined || existing === null || existing === '') {
          cur[leaf] = value;
          mutated = true;
        }
      });
      return mutated ? next : prev;
    });
  }, [def, securityProfiles, (installValues as any)?.securityProfile]);

  // Step 1 → Step 3 auth cascade. When a service.json declares
  // `securityCoupling.authModes` and the selected profile's mode is a key in
  // its mappings, push the cascaded value into installValues. Locked entries
  // ALWAYS overwrite (mirrors what `applySecurityOverrides` does server-side
  // via helm --set); unlocked entries only backfill empty values so manual
  // edits survive. No matching mode key → leave the field untouched (Step-1
  // incompatibility banner is rendered by InstallStep).
  useEffect(() => {
    if (!def || !installValues) return;
    const profileName = (installValues as any)?.securityProfile;
    if (!profileName) return;
    const profile = (securityProfiles?.profiles as any)?.[profileName];
    const mode = profile?.mode;
    if (!mode) return;
    const block = (def as any)?.securityCoupling?.authModes;
    if (!block?.field || !block?.mappings) return;
    const mapping = block.mappings[mode];
    if (!mapping || mapping.value === undefined) return;
    const parts = String(block.field)
      .replace(/\\\./g, '__DOT__')
      .split('.')
      .map((p: string) => p.replace(/__DOT__/g, '.'));
    setInstallValues((prev: any) => {
      const next = JSON.parse(JSON.stringify(prev || {}));
      let cur = next;
      for (let i = 0; i < parts.length - 1; i++) {
        if (cur[parts[i]] == null || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
        cur = cur[parts[i]];
      }
      const leaf = parts[parts.length - 1];
      const existing = cur[leaf];
      const shouldWrite = mapping.locked
        ? existing !== mapping.value
        : (existing === undefined || existing === null || existing === '');
      if (!shouldWrite) return prev;
      cur[leaf] = mapping.value;
      return next;
    });
  }, [def, securityProfiles, (installValues as any)?.securityProfile]);

  // Auth profiles imply TLS: OAuth callbacks reject http:// and session cookies
  // need the Secure flag. The rule lives in service.json under `securityCoupling`
  // so individual services can opt out or change the default minimum TLS mode
  // without editing wizard code.
  //
  // tlsProvisioning values:
  //   "k8s-view"      (default) — view participates in the unified TLS dropdown;
  //                                we auto-flip ingress.enabled=true and pick a
  //                                sane tlsMode when an auth profile is selected.
  //   "chart-managed" — chart owns TLS (e.g. GitLab via gitlab.global.hosts.https
  //                                + cert-manager); the wizard does not touch
  //                                ingress.* values at all.
  //   "external"      — operator wires TLS entirely outside; same as above.
  //
  // requireIngress is independent: it controls only the ingress.enabled auto-flip.
  // Backward compat: legacy `requireTls: false` is still honoured for one cycle.
  useEffect(() => {
    if (!installValues) return;
    const profileName = (installValues as any)?.securityProfile;
    if (!profileName) return;
    const coupling = (def as any)?.securityCoupling || {};
    const requireIngress = coupling.requireIngress !== false;
    const tlsProvisioning = String(coupling.tlsProvisioning
      ?? (coupling.requireTls === false ? 'chart-managed' : 'k8s-view'));
    const viewOwnsTls = tlsProvisioning === 'k8s-view';
    const minTlsMode = coupling.minTlsMode || 'signedByAmbariCA';
    const ingress = (installValues as any)?.ingress || {};
    const needsEnable = requireIngress && ingress.enabled !== true;
    const needsTls = viewOwnsTls && (!ingress.tlsMode || ingress.tlsMode === 'none');
    if (!needsEnable && !needsTls) return;
    setInstallValues((prev: any) => {
      const next = { ...(prev || {}) };
      next.ingress = { ...(next.ingress || {}) };
      if (needsEnable) next.ingress.enabled = true;
      if (needsTls) next.ingress.tlsMode = minTlsMode;
      return next;
    });
  }, [def, (installValues as any)?.securityProfile, (installValues as any)?.ingress?.enabled, (installValues as any)?.ingress?.tlsMode]);

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

      // Apply bindings same way as the modal. resolvedSel.resolvedFields feeds `context` variables.
      const varCtx = buildVarContext((def as any)?.variables, installValues, installValues?.mounts || {}, resolvedSel?.resolvedFields || {});
      applyBindingTargets(merged, (def as any)?.bindings || [], installValues?.mounts || {}, installValues, installValues?.releaseName || '', varCtx);

      // Drop fields that should not end up in values.yaml
      if (Array.isArray(def.form)) {
        getExcludedPaths(def.form).forEach(p => deleteAtStr(merged, p));
      }
      // Mirror buildFinalValues' envelope-key strip so the preview shows what
      // the chart will actually receive (otherwise users see top-level
      // releaseName/namespace/etc that wouldn't actually be sent).
      ['installMode','chartDirect','chartOverride','repoId','version','svcKey',
       'releaseName','namespace','securityProfile','deploymentMode','git',
       'kerberos','tls','oidc'].forEach(k => {
         delete (merged as any)[k];
       });

      return yaml.stringify(merged, { aliasDuplicateObjects: false });
    } catch (e) {
      return '# (preview unavailable)';
    }
  }, [installValues, def, resolvedSel]);

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

    // Apply bindings same way as preview. resolvedSel.resolvedFields feeds `context` variables.
    const varCtx = buildVarContext((def as any)?.variables, installValues, installValues?.mounts || {}, resolvedSel?.resolvedFields || {});
    applyBindingTargets(base, (def as any)?.bindings || [], installValues?.mounts || {}, installValues, installValues?.releaseName || '', varCtx);

    // Drop fields that should not end up in values.yaml
    if (Array.isArray(def?.form)) {
      getExcludedPaths(def.form).forEach(p => deleteAtStr(base, p));
    }

    // Drop wizard envelope keys — these are wizard state / request meta and
    // get sent as top-level fields on the submitHelmDeploy payload. Strict
    // chart schemas (Z2JH/JupyterHub, future) reject any additionalProperties
    // at the root of values.yaml, so anything left here would fail the
    // helm dry-run with errors like "Additional property releaseName is not allowed".
    // kerberos/tls/oidc are seeded into installValues by the def-load effect
    // (see line ~189) so they can be passed back as their own top-level request
    // fields (request.kerberos / request.tls / request.oidc). They are NOT chart
    // values — Z2JH/JupyterHub's strict schema rejects them at the root with
    // "Additional property kerberos is not allowed". Strip them along with the
    // other wizard envelope keys.
    ['installMode','chartDirect','chartOverride','repoId','version','svcKey',
     'releaseName','namespace','securityProfile','deploymentMode','git',
     'kerberos','tls','oidc'].forEach(k => {
       delete (base as any)[k];
     });

    // If YAML editor overrides are present and valid, merge them in
    if (editMode && parsedRef.current && !parseError) {
      return { ...base, ...parsedRef.current };
    }
    return base;
  };

  /**
   * Pre-submit validation: catch mode-specific missing fields BEFORE the deploy fires.
   * Without this, the backend silently planned no TLS step (the value was undefined,
   * code took the "skipping" branch with a WARN log) and OIDC failed downstream with
   * an unhelpful preflight error. Better to refuse here with a clear message.
   *
   * Returns null if the values are coherent, or an error string if not.
   */
  const validateTlsPayload = (): string | null => {
    const tlsMode: string | undefined = (installValues as any)?.ingress?.tlsMode;
    const ingressEnabled = (installValues as any)?.ingress?.enabled;
    if (!ingressEnabled) return null;
    if (tlsMode === 'signedByClusterIssuer') {
      const issuerName = (installValues as any)?.ingress?.certManager?.issuerName;
      if (!issuerName) {
        return 'Pick a ClusterIssuer from the dropdown — TLS mode "Sign via cert-manager" needs one to issue the leaf.';
      }
    }
    if (tlsMode === 'sourcedFromExternalSecret') {
      const es = (installValues as any)?.ingress?.externalSecret || {};
      if (!es.secretStoreName || !es.remoteKey) {
        return 'TLS mode "Source from external Secret store" needs a SecretStore + a remote key (e.g. Vault PKI path).';
      }
    }
    if (tlsMode === 'signedByCompanyCA') {
      const caName = (installValues as any)?.ingress?.tlsSelfSign?.caName;
      if (!caName) {
        return 'TLS mode "Sign with Company CA" needs a CA picked from the PKI registry. Upload one via Configuration → Certificate Authorities first.';
      }
    }
    if (tlsMode === 'uploadLeaf') {
      const up = (installValues as any)?.ingress?.tlsUpload || {};
      if (!up.cert || !up.key) {
        return 'TLS mode "Upload cert + key" needs both PEM cert and PEM key.';
      }
    }
    if (tlsMode === 'bringYourOwn') {
      const secretName = (installValues as any)?.ingress?.tlsSecret;
      if (!secretName) {
        return 'TLS mode "Bring your own Secret" needs the existing Secret name.';
      }
    }
    return null;
  };

  // Resolve the selected platform context (live for managed) so the wizard can show the
  // target + flag unmet requiresContext fields on an external context.
  const selectedCtxId = (installValues as any)?.platformContextId || 'default';
  useEffect(() => {
    let cancelled = false;
    getResolvedContext(selectedCtxId).then((r) => { if (!cancelled) setResolvedSel(r); })
      .catch(() => { if (!cancelled) setResolvedSel(null); });
    return () => { cancelled = true; };
  }, [selectedCtxId]);

  // Dotted-path get on the wizard form state (for evaluating requiresContext.when).
  const dotGet = (obj: any, path: string) =>
    path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);

  // Set a dotted-path value on a (cloned) object, creating intermediate objects.
  const dotSet = (obj: any, path: string, value: any) => {
    const parts = path.split('.');
    let o = obj;
    for (let i = 0; i < parts.length - 1; i++) {
      if (o[parts[i]] == null || typeof o[parts[i]] !== 'object') o[parts[i]] = {};
      o = o[parts[i]];
    }
    o[parts[parts.length - 1]] = value;
  };

  // Service-advisor: the form fields (incl. those nested in groups) that declare an `advisor`
  // key — these are the enable/disable toggles the advisor can recommend on/off.
  const advisorFields = useMemo(() => {
    const out: { name: string; advisor: string }[] = [];
    const walk = (fields: any[]) => (fields || []).forEach((f: any) => {
      if (f?.advisor && f?.name) out.push({ name: f.name, advisor: f.advisor });
      if (f?.type === 'group' && Array.isArray(f.fields)) walk(f.fields);
    });
    walk((def as any)?.form || []);
    return out;
  }, [def]);

  // When the operator selects a platform context, ask the (operator-editable) service advisor
  // which toggles to recommend on/off for what that context provides, apply them as defaults,
  // and surface the reasons. Advisory only + once per context selection; the operator can still
  // override any toggle afterwards.
  const advisedCtxRef = useRef<string | null>(null);
  useEffect(() => {
    if (!def || advisorFields.length === 0) return;
    if (advisedCtxRef.current === selectedCtxId) return;
    let cancelled = false;
    getContextAdvice(selectedCtxId, (def as any)?.name || '', advisorFields)
      .then((res) => {
        if (cancelled) return;
        advisedCtxRef.current = selectedCtxId;
        const recs = res?.recommendations || [];
        if (!recs.length) return;
        setInstallValues((prev: any) => {
          const next = JSON.parse(JSON.stringify(prev || {}));
          recs.forEach((r) => dotSet(next, r.field, r.recommend));
          return next;
        });
        notification.info({
          message: 'Service advisor recommendations',
          description: (
            <div>
              {recs.map((r, i) => (
                <div key={i}>
                  <Tag color={r.recommend ? 'green' : 'default'}>{r.recommend ? 'ON' : 'OFF'}</Tag>
                  {r.reason}
                </div>
              ))}
            </div>
          ),
          duration: 8,
        });
      })
      .catch(() => { /* advisory only — never block the wizard */ });
    return () => { cancelled = true; };
  }, [def, selectedCtxId, advisorFields]);

  // Required context fields not satisfied by the selected (external) context.
  const unmetContextReqs: string[] = React.useMemo(() => {
    const reqs = (def as any)?.requiresContext as any[] | undefined;
    if (!reqs || !resolvedSel || resolvedSel.kind === 'MANAGED') return [];
    const fields = resolvedSel.resolvedFields || {};
    const secretsSet = resolvedSel.secretFieldsSet || [];
    const missing: string[] = [];
    for (const r of reqs) {
      if (r.appliesTo && r.appliesTo !== resolvedSel.kind) continue;
      if (r.when && !dotGet(installValues, r.when)) continue;
      for (const fn of (r.fields || [])) {
        const key = `${r.capability}.${fn}`;
        if (!(key in fields) && !secretsSet.includes(key)) missing.push(key);
      }
    }
    return Array.from(new Set(missing));
  }, [def, resolvedSel, installValues]);

  // Actual deploy submit. `atlasRestartAuthorized` carries the operator's choice from the
  // pre-deploy restart-confirmation modal (true = let KDPS restart Atlas to activate the OM
  // federation user; false = skip the restart, operator restarts manually). It is threaded to the
  // backend via formValues._atlasRestartAuthorized and only affects the Atlas-federation path.
  const doDeploy = async (atlasRestartAuthorized: boolean = true) => {
      const validationError = validateTlsPayload();
      if (validationError) {
        message.error(validationError);
        return;
      }
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
              kerberos: (installValues as any)?.kerberos || undefined,
              // Translate ingress.tlsMode (form select) into the wire payload the backend
              // expects. Mirrors ServiceInstallationModal/index.tsx so the wizard and the
              // modal share one truth:
              //   uploadLeaf                  → ingressTlsUpload   { secretName, certPem, keyPem }
              //   signedByAmbariCA            → ingressTlsSelfSign  { mode: ambariInternal, ... }
              //   signedByCompanyCA           → ingressTlsSelfSign  { mode: companyUploaded, caName, ... }
              //   signedByClusterIssuer       → ingressTlsCertManager { issuerName, durationHours, ... }
              //   sourcedFromExternalSecret   → ingressTlsExternalSecret { secretStoreName, remoteKey, ... }
              //   bringYourOwn                → nothing (values already carry ingress.tlsSecret)
              ...((): { ingressTlsUpload?: any; ingressTlsSelfSign?: any; ingressTlsCertManager?: any; ingressTlsExternalSecret?: any } => {
                const tlsMode: string | undefined = (installValues as any)?.ingress?.tlsMode;
                if (tlsMode === 'uploadLeaf') {
                  const up = (installValues as any)?.ingress?.tlsUpload || {};
                  if (!up.cert || !up.key) return {};
                  return {
                    ingressTlsUpload: {
                      secretName: up.secretName || `${installValues.releaseName}-ingress-tls`,
                      certPem: up.cert,
                      keyPem: up.key,
                    },
                  };
                }
                if (tlsMode === 'signedByAmbariCA' || tlsMode === 'signedByCompanyCA') {
                  const selfSign = (installValues as any)?.ingress?.tlsSelfSign || {};
                  const validityDays = parseInt(selfSign.validityDays, 10);
                  return {
                    ingressTlsSelfSign: {
                      mode: tlsMode === 'signedByAmbariCA' ? 'ambariInternal' : 'companyUploaded',
                      secretName: `${installValues.releaseName}-ingress-tls`,
                      ingressHost: (installValues as any)?.ingress?.host,
                      validityDays: Number.isFinite(validityDays) ? validityDays : 90,
                      ...(tlsMode === 'signedByCompanyCA' ? { caName: selfSign.caName } : {}),
                    },
                  };
                }
                if (tlsMode === 'signedByClusterIssuer') {
                  const cm = (installValues as any)?.ingress?.certManager || {};
                  const validityDays = parseInt(cm.validityDays, 10);
                  return {
                    ingressTlsCertManager: {
                      issuerName: cm.issuerName,
                      issuerKind: cm.issuerKind || 'ClusterIssuer',
                      secretName: `${installValues.releaseName}-ingress-tls`,
                      ingressHost: (installValues as any)?.ingress?.host,
                      durationHours: (Number.isFinite(validityDays) ? validityDays : 90) * 24,
                    },
                  };
                }
                if (tlsMode === 'sourcedFromExternalSecret') {
                  const es = (installValues as any)?.ingress?.externalSecret || {};
                  return {
                    ingressTlsExternalSecret: {
                      secretStoreName: es.secretStoreName,
                      secretStoreKind: es.secretStoreKind || 'ClusterSecretStore',
                      remoteKey: es.remoteKey,
                      refreshInterval: es.refreshInterval || '1h',
                      secretName: `${installValues.releaseName}-ingress-tls`,
                      ingressHost: (installValues as any)?.ingress?.host,
                    },
                  };
                }
                return {};
              })(),
              // Only send securityProfile if user explicitly picked one.
              securityProfile: (installValues as any)?.securityProfile || undefined,
              deploymentMode: (installValues as any)?.deploymentMode || 'DIRECT_HELM',
              git: (installValues as any)?.git || undefined,
              // Snapshot of raw form state (envelope keys stripped) so backend can read
              // excludeFromValues form fields when rendering OIDC redirectUriTemplate etc.
              // Without this, JupyterHub's "{{jupyterHost}}" renders empty server-side and
              // Keycloak rejects the client registration with HTTP 400 invalid_input.
              formValues: ((): Record<string, any> => {
                const snap = JSON.parse(JSON.stringify(installValues || {}));
                // Strip wizard envelope/meta keys + the def-seeded blocks that
                // are passed in their own request fields. formValues is for
                // server-side template rendering of form-field values; nested
                // def-seeded objects (kerberos/tls/oidc) aren't form input.
                ['installMode','chartDirect','chartOverride','repoId','version','svcKey',
                 'deploymentMode','git',
                 'kerberos','tls','oidc'].forEach(k => { delete snap[k]; });
                // ② Operator's Atlas-restart authorization from the pre-deploy confirmation modal.
                // The ATLAS_USER_PROVISION_OM step reads this; "false" makes it write the federation
                // user but skip the Atlas restart (the operator restarts manually). Default authorized.
                snap._atlasRestartAuthorized = String(atlasRestartAuthorized);
                return snap;
              })(),
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

  // ② Pre-deploy restart confirmation. A federation deploy provisions the OM user in Atlas and must
  // restart ATLAS_SERVER for Atlas to load it (file/basic auth is read at startup). We warn the
  // operator and let them authorize the restart or skip it (deploy continues; they restart later).
  // Non-federation deploys and upgrades are unaffected — they deploy immediately, default authorized.
  const [atlasRestartModalOpen, setAtlasRestartModalOpen] = React.useState(false);
  const atlasFederationOn = !!((installValues as any)?.atlasFederation?.enabled);
  const handleDeploy = () => {
      if (atlasFederationOn && !isUpgrade) { setAtlasRestartModalOpen(true); return; }
      void doDeploy(true);
  };

  const steps = React.useMemo(() => {
    if (!def) return [];
    return [
      { title: isUpgrade ? 'Upgrade – General' : 'General Info', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="general" repos={repos} securityProfiles={securityProfiles.profiles} /> },
      { title: 'Storage', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="storage" repos={repos} securityProfiles={securityProfiles.profiles} /> },
      { title: 'Chart Settings', content: <InstallStep definition={def} data={installValues} onChange={setInstallValues} mode="chart" repos={repos} securityProfiles={securityProfiles.profiles} resolvedContext={resolvedSel as any} /> },
      { title: 'Configuration', content: <ConfigurationStep configs={configs} overrides={configOverrides} onChange={setConfigOverrides} passwordValidity={passwordValidity} onPasswordValidityChange={setPasswordValidity} /> },
      { title: 'Review', content: <ReviewStep def={def} install={installValues} repoLabel={repos.find(r => r.id === installValues.repoId)?.name || installValues.repoId} /> },
    ];
  }, [def, installValues, repos, configs, configOverrides]);

  // Disable Next if any password field reports invalid (empty, unconfirmed, or
  // mismatched). PropertyRenderer pushes the boolean validity here via its
  // onPasswordValidityChange callback whenever its local confirm state's validity
  // flips. We default *required* passwords with no entry yet to invalid; optional
  // fields default to valid until reported otherwise.
  const hasInvalidPasswords = React.useMemo(() => {
    for (const cfg of configs) {
      for (const prop of (cfg.properties || [])) {
        if (prop.type !== 'password') continue;
        const key = `${cfg.name}/${prop.name}`;
        if (key in passwordValidity) {
          if (!passwordValidity[key]) return true;
        } else if (prop.required) {
          // Field never touched but required → block by default.
          return true;
        }
      }
    }
    return false;
  }, [configs, passwordValidity]);

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
          {/* The platform context is a GLOBAL KDPS concept: every service is deployed wired to one,
              and the engine resolves that context's capabilities (Atlas, Ranger, Hive, Kerberos,
              OIDC, …) into whichever fields the service's service.json declares as context-resolved.
              So the selector is shown for ALL services, not just those declaring atlasFederation. */}
          {(() => {
            const selectedId = (installValues as any)?.platformContextId || 'default';
            return (
              <Card style={{ marginBottom: 16 }}
                title={<span style={{ fontSize: 15 }}><ApiOutlined /> Platform context</span>}
                extra={<Button size="small" type="link" onClick={() => setCtxDetailsOpen(true)}>Details</Button>}>
                <Select
                  size="large"
                  style={{ width: '100%' }}
                  value={selectedId}
                  onChange={(v) => setInstallValues((prev: any) => ({ ...prev, platformContextId: v }))}
                  options={(contexts.length ? contexts : [{ id: 'default', name: 'Ambari-managed cluster', kind: 'MANAGED' } as PlatformContext])
                    .map((c) => ({ value: c.id, label: `${c.name}${c.kind === 'MANAGED' ? ' (managed)' : ' (external)'}` }))}
                  dropdownRender={(menu) => (
                    <>
                      {menu}
                      <Divider style={{ margin: '6px 0' }} />
                      <Button type="text" icon={<PlusOutlined />} style={{ width: '100%', textAlign: 'left' }}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => window.open('#/contexts', '_blank')}>
                        Add / manage platform contexts
                      </Button>
                    </>
                  )}
                />
                {unmetContextReqs.length > 0 && (
                  <Alert style={{ marginTop: 10 }} type="warning" showIcon
                    message={<span>Context missing: <b>{unmetContextReqs.join(', ')}</b></span>} />
                )}
              </Card>
            );
          })()}

          {/* #6: context details on demand (replaces the inline undertitle) */}
          <Modal title={<span><ApiOutlined /> Platform context details</span>}
            open={ctxDetailsOpen} onCancel={() => setCtxDetailsOpen(false)}
            footer={<Button onClick={() => setCtxDetailsOpen(false)}>Close</Button>} width={560}>
            {resolvedSel ? (
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="Context">
                  {resolvedSel.name} {resolvedSel.kind === 'MANAGED' ? <Tag color="blue">Managed</Tag> : <Tag color="purple">External</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="Atlas">
                  {resolvedSel.atlasUrl
                    ? <span>{resolvedSel.atlasUrl}<br/><Text type="secondary" style={{ fontSize: 12 }}>auth: {resolvedSel.atlasAuthMode} · authz: {resolvedSel.atlasAclMode}</Text></span>
                    : <Text type="secondary">not present</Text>}
                </Descriptions.Item>
                <Descriptions.Item label="Ranger">
                  {resolvedSel.kind === 'MANAGED'
                    ? <Text type="secondary">managed by Ambari (policies applied server-side)</Text>
                    : (resolvedSel.rangerUrl || <Text type="secondary">—</Text>)}
                </Descriptions.Item>
                <Descriptions.Item label="Kerberos">
                  {resolvedSel.kerberosRealm ? <Tag color="geekblue">{resolvedSel.kerberosRealm}</Tag> : <Text type="secondary">none</Text>}
                </Descriptions.Item>
                {/* Generic view of every capability.field the KDPS engine resolved for this
                    context — so services beyond Atlas (Hive, Trino, OIDC, …) are visible too. */}
                {resolvedSel.resolvedFields && Object.keys(resolvedSel.resolvedFields).length > 0 && (
                  <Descriptions.Item label="Resolved fields">
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                      {Object.entries(resolvedSel.resolvedFields).map(([k, v]) => (
                        <Text key={k} style={{ fontSize: 12 }}>
                          <Text code style={{ fontSize: 11 }}>{k}</Text> {String(v)}
                        </Text>
                      ))}
                    </div>
                  </Descriptions.Item>
                )}
              </Descriptions>
            ) : <Text type="secondary">Resolving…</Text>}
          </Modal>
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
    <Modal
      open={atlasRestartModalOpen}
      title={<span><WarningOutlined style={{ color: '#faad14' }} /> Apache Atlas restart required</span>}
      onCancel={() => setAtlasRestartModalOpen(false)}
      footer={[
        <Button key="cancel" onClick={() => setAtlasRestartModalOpen(false)}>Cancel</Button>,
        <Button key="skip" onClick={() => { setAtlasRestartModalOpen(false); void doDeploy(false); }}>
          Deploy, skip restart
        </Button>,
        <Button key="go" type="primary" onClick={() => { setAtlasRestartModalOpen(false); void doDeploy(true); }}>
          Authorize restart &amp; deploy
        </Button>,
      ]}
    >
      <Typography.Paragraph>
        This deployment enables <strong>OpenMetadata → Apache Atlas federation</strong>. KDPS provisions a
        federation user in Atlas, but Atlas loads its user store only at startup — so <strong>ATLAS_SERVER
        must be restarted</strong> for ingestion to authenticate. Without it, ingestion gets HTTP&nbsp;401
        and no metadata is federated.
      </Typography.Paragraph>
      <Alert
        type="warning" showIcon
        message="Restarting Atlas briefly interrupts it on this Hadoop cluster"
        description="The restart runs as an Ambari operation during the post-deploy steps (a couple of minutes) and only if Atlas config actually changed (idempotent). Other running OpenMetadata releases are unaffected."
      />
      <ul style={{ marginTop: 12, marginBottom: 0 }}>
        <li><strong>Authorize restart &amp; deploy</strong> — KDPS restarts ATLAS_SERVER automatically once the federation user is written.</li>
        <li><strong>Deploy, skip restart</strong> — the user is still provisioned, but federation stays inactive until you restart ATLAS_SERVER yourself (e.g. from Ambari).</li>
      </ul>
    </Modal>
    </>
  );
};
export default ServiceWizardPage;
