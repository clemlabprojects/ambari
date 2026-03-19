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
import {
  Button, Modal, Form, Input, Select, message, Spin, Alert, Checkbox, Card, Progress, Radio, Segmented, Space, Steps, Switch, Tabs, Tooltip, Typography,
Upload, Tag, Divider
} from 'antd'; // Added Tag and Divider imports

import { InfoCircleOutlined, UploadOutlined, LinkOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import yaml from 'yaml';
import debounce from 'lodash.debounce';

import { getHelmRepos, getAvailableServices, submitHelmDeploy, getCommandStatus, getSecurityConfig, type CommandStatus } from '../../api/client';
import type { FormField, AvailableServices } from '../../types/ServiceTypes';
import type { HelmRepo } from '../../types';
import type { MountSpec } from '../../types/MountSpec';
import { getGitRepos } from '../../pages/GitRepositoriesPage';

import * as pako from 'pako';
import { untar } from 'js-untar';

import VolumeEditor from './VolumeEditor';
import DynamicFormField from './DynamicFormField';
import type { BindingSpec } from './types';
import {
  makeTargetsPatch,
  deleteAtStr,
  deepMerge,
  toPath, getAt, setAt,
  applyBindingTargets, interpolate, interpolateStr,
  buildVarContext,
} from './bindings';

import type { UploadFile } from 'antd/es/upload/interface';

const { Text } = Typography;
const { Option } = Select;
const { confirm: confirmModal } = Modal;
const { Step } = Steps;

/* ------------------------------- small helpers ------------------------------- */

const confirmMismatch = (chart: string, currentRepo: string, suggestedRepo: string): Promise<boolean> =>
  new Promise(resolve => {
    confirmModal({
      title: 'Chart and repository inconsistent',
      content: (
        <span>
          Chart <b>{chart}</b> probably does not exist in repository <b>{currentRepo}</b>.<br />
          Do you want to use repository <b>{suggestedRepo}</b>?
        </span>
      ),
      okText: `Switch to ${suggestedRepo}`,
      cancelText: 'Cancel',
      onOk() { resolve(true); },
      onCancel() { resolve(false); },
    });
  });


/* Helper to gather all field names that have excludeFromValues: true */
const getExcludedPaths = (fields: FormField[]): string[] => {
  let paths: string[] = [];
  fields.forEach(f => {
    // Check if this specific field is marked to be excluded
    if ((f as any).excludeFromValues) {
      paths.push(f.name);
    }
    // Recurse if it is a group
    if (f.type === 'group' && (f as any).fields) {
      paths = [...paths, ...getExcludedPaths((f as any).fields)];
    }
  });
  return paths;
};

const pickAtPath = (obj: any, path: string) => {
  if (!path) return undefined;
  const parts = path.split('.').map(p => p.trim()).filter(Boolean);
  let cur = obj;
  for (const p of parts) {
    if (cur == null) return undefined;
    cur = cur[p];
  }
  return cur;
};

const renderTemplate = (tpl: string, ctx: any) => {
  if (!tpl) return '';
  return tpl.replace(/{{\s*([^}]+)\s*}}/g, (_m, raw) => {
    const path = String(raw || '').trim();
    const val = pickAtPath(ctx, path);
    return val == null ? '' : String(val);
  });
};

/* ---------------------------------- props ----------------------------------- */

type ServiceInstallationModalProps = {
  visible: boolean;
  onClose: () => void;
  onDeploy: () => void;
  mode?: 'deploy' | 'upgrade';
  initialRelease?: {
    name: string;
    namespace: string;
    chart?: string;
    repoId?: string;
    currentValues?: any;
    version?: string;
    deploymentMode?: string;
    gitRepoUrl?: string;
    gitBranch?: string;
    gitPath?: string;
    gitPrNumber?: string;
  };
};

/* =============================== main component ============================== */

const ServiceInstallationModal: React.FC<ServiceInstallationModalProps> = ({
  visible, onClose, onDeploy, mode = 'deploy', initialRelease,
}) => {
  const [form] = Form.useForm();
  const prevMountsRef = React.useRef<any | null>(null);

  const [isDeploying, setIsDeploying] = useState(false);
  const [availableServices, setAvailableServices] = useState<AvailableServices | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedServiceKey, setSelectedServiceKey] = useState<string>('');

  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [gitRepos, setGitRepos] = useState<Array<{ id: string; name: string; url: string; credentialAlias?: string }>>([]);
  const [repoLoading, setRepoLoading] = useState(false);
  const [installMode, setInstallMode] = useState<'repo' | 'direct'>('repo');
  const [overrideChart, setOverrideChart] = useState(false);
  const [securityProfiles, setSecurityProfiles] = useState<{ defaultProfile?: string; profiles: Record<string, any> }>({ profiles: {} });

  const [step, setStep] = useState(0);
  const [percent, setPct] = useState(0);
  const [stepTitles, setStepTitles] = useState<string[]>([]);
  const [statusMsg, setStatusMsg] = useState<string>();
  const [showProgress, setShowProgress] = useState(false);

  // New state for upgrade mode raw values
  const [upgradeValues, setUpgradeValues] = useState('');

  // View and editor state - must be declared before useEffects that use them
  const [view, setView] = useState<'form' | 'yaml'>('form');
  const [editorYaml, setEditorYaml] = useState('');
  const [editMode, setEditMode] = useState(false);
  const [parseError, setParseError] = useState<string | null>(null);
  const [loadingChart, setLoadingChart] = useState(false);

  const lockChart = mode === 'upgrade';
  const releaseChartRef = initialRelease?.chart || '';

  const defaultChartName = useMemo(() => {
    if (lockChart && releaseChartRef) return releaseChartRef;
    const svc = availableServices?.[selectedServiceKey];
    if (!svc?.chart) return '';
    return svc.chart.split('/').pop() || svc.chart;
  }, [lockChart, releaseChartRef, availableServices, selectedServiceKey]);

  const setInitialValues = (fields: FormField[], currentValues: any) => {
    fields.forEach(field => {
      if (field.type === 'group') {
        setInitialValues((field as any).fields, currentValues);
      } else if ((field as any).defaultValue !== undefined) {
        const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map(p => p.replace(/__DOT__/g, '.'));
        let current = currentValues;
        for (let i = 0; i < nameParts.length - 1; i++) current = (current[nameParts[i]] = current[nameParts[i]] || {});
        current[nameParts[nameParts.length - 1]] = (field as any).defaultValue;
      }
    });
  };

  /* -------- initial load -------- */
  useEffect(() => {
    if (!visible) return;

    setStep(0); setPct(0); setStepTitles([]); setStatusMsg(undefined); setShowProgress(false);

    // --- UPGRADE MODE INIT ---
    if (mode === 'upgrade' && initialRelease) {
      form.setFieldsValue({ releaseName: initialRelease.name, namespace: initialRelease.namespace });
      form.setFieldsValue({ deploymentMode: initialRelease?.deploymentMode || 'DIRECT_HELM' });
      const gitDefaults = initialRelease.gitRepoUrl ? {
        repoUrl: initialRelease.gitRepoUrl,
        branch: initialRelease.gitBranch,
        commitMode: initialRelease.gitPrNumber ? 'PR_MODE' : 'DIRECT_COMMIT'
      } : {};
      form.setFieldsValue({
        git: { ...(form.getFieldValue('git') || {}), ...gitDefaults },
        repoId: initialRelease.repoId,
        version: initialRelease.version
      });
      
      // If we have current values (fetched by the parent component), switch to YAML view and populate
      if (initialRelease.currentValues) {
        setView('yaml');
        const rawYaml = yaml.stringify(initialRelease.currentValues);
        setEditorYaml(rawYaml);
        setUpgradeValues(rawYaml);
        
        // Ensure form has basic metadata even if we don't use the form fields for values
        form.setFieldsValue({
            chart: initialRelease.chart,
            repoId: initialRelease.repoId,
            version: initialRelease.version
        });
      }
      return; // Skip the rest of init for upgrade mode
    }

    // --- DEPLOY MODE INIT ---
    let cancelled = false;
    setIsLoading(true);
    setRepoLoading(true);
    setError(null);

    (async () => {
      try {
        const [services, reposList, secProfiles] = await Promise.all([getAvailableServices(), getHelmRepos(), getSecurityConfig()]);
        if (cancelled) return;

        setAvailableServices(services);
        setRepos(reposList);
        setSecurityProfiles(secProfiles);
        
        // Load Git repositories
        try {
          const gitReposList = getGitRepos();
          setGitRepos(gitReposList);
          // Update global reference for compatibility
          if (typeof window !== 'undefined') {
            (window as any).__gitRepos = gitReposList;
          }
        } catch (e) {
          console.warn('Failed to load Git repositories:', e);
        }

        let deducedKey = '';
        if (mode === 'upgrade' && initialRelease?.chart) {
          const live = initialRelease.chart.toLowerCase();
          const liveLast = live.split('/').pop() || live;
          const match = Object.entries(services).find(([_, v]: any) => {
            const def = (v.chart || '').toLowerCase();
            const defLast = def.split('/').pop() || def;
            return def === live || defLast === liveLast;
          });
          if (match) deducedKey = match[0];
        }

        let svcKey = selectedServiceKey || deducedKey;
        if (!svcKey) {
          if (mode === 'upgrade' && initialRelease?.chart) {
            const wanted = initialRelease.chart.split('/').pop()?.toLowerCase();
            const match = Object.entries(services).find(([_, v]: any) => (v.chart || '').split('/').pop()?.toLowerCase() === wanted);
            svcKey = match?.[0];
          }
          if (!svcKey) {
            const keys = Object.keys(services);
            svcKey = keys[0] || '';
          }
        }

          if (svcKey) {
            setSelectedServiceKey(svcKey);
            const initialValues: any = {};
            if ((services as any)[svcKey]?.form) setInitialValues((services as any)[svcKey].form, initialValues);
            form.setFieldsValue({
              svcKey,
              deploymentMode: 'DIRECT_HELM',
              ...initialValues,
              ...(mode === 'upgrade' && initialRelease ? { releaseName: initialRelease.name, namespace: initialRelease.namespace } : {}),
              securityProfile: secProfiles.defaultProfile,
            });
          }

         // Post-process: if serviceMonitor.labels.release is a templated string, resolve it now
         const cur = form.getFieldsValue(true);
         const currentVal = cur?.serviceMonitor?.labels?.release;
         if (typeof currentVal === 'string' && /\$\{[^}]+\}/.test(currentVal)) {
           const resolved = interpolateStr(currentVal, cur);
           form.setFieldsValue({ serviceMonitor: { ...cur.serviceMonitor, labels: { ...(cur.serviceMonitor?.labels || {}), release: resolved } } });
         }
        if (!form.getFieldValue('repoId') && reposList.length > 0) {
          const pref = (services as any)[svcKey!]?.defaultRepo;
          const repoId = (pref && reposList.find(r => r.id === pref)?.id) || reposList[0].id;
          form.setFieldsValue({ repoId });
        }

        prevMountsRef.current = form.getFieldValue(['mounts']) || null;
      } catch (e: any) {
        if (!cancelled) {
          console.error(e);
          setError('Unable to load services or repositories. Please try again.');
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
          setRepoLoading(false);
        }
      }
    })();

    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, mode, initialRelease]); // initialRelease dependency is key for upgrade re-init

  useEffect(() => { if (selectedServiceKey) form.setFieldsValue({ svcKey: selectedServiceKey }); }, [selectedServiceKey]);

  // Form watchers - must be declared before useEffects that use them
  const watchedMounts = Form.useWatch(['mounts'], form);
  const releaseNameWatch = Form.useWatch(['releaseName'], form);
  const deploymentModeWatch = Form.useWatch(['deploymentMode'], form);
  const allValues = Form.useWatch([], form); // watch the whole form

  useEffect(() => {
    // Only switch views automatically in DEPLOY mode. Upgrade mode forces YAML.
    if (mode === 'deploy') {
        if (installMode === 'direct') {
        setView('yaml');          // show YAML editor
        } else {
        setView('form');          // show configuration form
        }
    }
  }, [installMode, mode]);

  useEffect(() => {
    if (deploymentModeWatch === 'FLUX_GITOPS') {
      const currentGit = form.getFieldValue('git') || {};
      form.setFieldsValue({
        git: {
          baseBranch: currentGit.baseBranch || 'main',
          pathPrefix: currentGit.pathPrefix || 'clusters/default',
          commitMode: currentGit.commitMode || 'DIRECT_COMMIT',
          ...currentGit
        }
      });
    }
  }, [deploymentModeWatch, form]);

  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setSelectedServiceKey('');
      setInstallMode('repo');
      setOverrideChart(false);
      setStep(0); setPct(0); setStepTitles([]); setStatusMsg(undefined); setShowProgress(false);
      setEditorYaml(''); // Clear editor
      setUpgradeValues('');
    }
  }, [visible]);

  useEffect(() => {
    const unsubscribe = form.subscribe?.(() => {}) as any; // ignore, not all antd versions expose subscribe
    const cur = form.getFieldsValue(true);
    const monRel = cur?.monitoring?.release;
    const sm = cur?.serviceMonitor;
    const labelVal = sm?.labels?.release;

    // If the field is empty or equals the previous templated/default value, update it
    if (monRel && (labelVal == null || labelVal === '' || labelVal === '${monitoring.release}')) {
      form.setFieldsValue({ serviceMonitor: { ...(sm || {}), labels: { ...(sm?.labels || {}), release: monRel } } });
    }
  }, [Form.useWatch(['monitoring', 'release'], form)]);


const handleServiceChange = (value: string) => {
    if (!availableServices) return;
    
    // 1. Capture "Sticky" values we want to preserve across service switches
    const currentValues = form.getFieldsValue(['namespace', 'installMode', 'repoId', 'version']);
    
    setSelectedServiceKey(value);
    prevMountsRef.current = null;

    // 2. Wipe the form state completely
    form.resetFields(); 

    // 3. Prepare defaults for the NEW service
    const initialValues: any = {};
    setInitialValues((availableServices as any)[value].form, initialValues);

    let nextRepoId = currentValues.repoId;
    const preferred = (availableServices as any)[value]?.defaultRepo;
    // Only switch repo if the user hasn't manually selected one, or if we want to enforce preference
    if (preferred && repos.find(r => r.id === preferred)) nextRepoId = preferred;

    // 4. Set the new clean state
    form.setFieldsValue({
      svcKey: value,
      installMode: currentValues.installMode ?? 'repo',
      repoId: nextRepoId,
      version: currentValues.version,
      namespace: currentValues.namespace, // Keep the namespace the user typed
      mounts: undefined, // reset mounts
      tls: undefined,
      ...initialValues,
    });
  };
  /* -------- mounts ↔ form sync using bindings -------- */

  const mountsSpec = useMemo<MountSpec[]>(() => {
    const svc = (availableServices as any)?.[selectedServiceKey] as any;
    return Array.isArray(svc?.mounts) ? (svc.mounts as MountSpec[]) : [];
  }, [availableServices, selectedServiceKey]);

  const bindings = useMemo<BindingSpec[]>(() => {
    const svc = (availableServices as any)?.[selectedServiceKey] as any;
    return Array.isArray(svc?.bindings) ? (svc.bindings as BindingSpec[]) : [];
  }, [availableServices, selectedServiceKey]);

  // --- Live values.yaml preview (form values + targets applied) ---
  const previewYaml = useMemo(() => {
    // Only generate preview from form in DEPLOY mode. In UPGRADE mode, the editor IS the source of truth.
    if (mode === 'upgrade') return '';

    try {
      const raw = form.getFieldsValue(true) || {};
      const mounts = raw.mounts || {};
      // const mergedValues = { ...raw };
      // need to deep clone to avoid mutating form values
      const mergedValues = JSON.parse(JSON.stringify(raw));

      const svcAny = (availableServices as any)?.[selectedServiceKey] as any;
      
      // 1. Build context
      const varCtx = buildVarContext(svcAny?.variables, raw, mounts);
      
      // 2. Apply Bindings
      applyBindingTargets(mergedValues, bindings, mounts, raw, raw.releaseName || '', varCtx);
      
      // 3. Remove standard internal keys
      delete mergedValues.installMode;
      delete mergedValues.chartDirect;
      delete mergedValues.chartOverride;
      delete mergedValues.repoId;
      delete mergedValues.version;
      delete mergedValues.svcKey;

      // 4. Remove fields marked as excludeFromValues in charts.json
      if (svcAny?.form) {
        const excluded = getExcludedPaths(svcAny.form);
        excluded.forEach(path => deleteAtStr(mergedValues, path));
      }

      return yaml.stringify(mergedValues, { aliasDuplicateObjects: false });
    } catch (e) {
      return '# (preview unavailable)';
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allValues, JSON.stringify(form.getFieldsValue(true)), JSON.stringify(bindings), JSON.stringify(watchedMounts), mode]);

  // --- 2) Local editor state (already declared above)
  const isDirtyRef = useRef(false);

  useEffect(() => {
    // In Deploy mode, sync preview to editor unless user is typing
    if (mode === 'deploy' && !isDirtyRef.current) {
      setEditorYaml(previewYaml);
      setParseError(null);
    }
  }, [previewYaml, mode]);

  const parsedRef = useRef<Record<string, any> | null>(null);
  const applyYamlDebounced = useRef(
    debounce((text: string) => {
      try {
        const doc = yaml.parse(text ?? '') ?? {};
        parsedRef.current = doc;
        setParseError(null);
      } catch (e: any) {
        parsedRef.current = null;
        setParseError(e.message || 'Invalid YAML');
      }
    }, 300)
  ).current;

  const onEditorChange = (value?: string) => {
    isDirtyRef.current = true;
    setEditorYaml(value ?? '');
    applyYamlDebounced(value ?? '');
  };

  /**
   * Build a lightweight Flux manifest preview (HelmRepository + HelmRelease) so users
   * can see what will be committed when using GitOps mode. This is client-side only.
   */
  const fluxManifestsYaml = useMemo(() => {
    if (deploymentModeWatch !== 'FLUX_GITOPS') return '';
    try {
      const rawValues = (mode === 'upgrade') ? (parsedRef.current || yaml.parse(editorYaml || '') || {}) : (previewYaml ? yaml.parse(previewYaml) : {});
      const formValues = form.getFieldsValue(true) || {};
      const releaseName = formValues.releaseName || initialRelease?.name || 'release';
      const ns = formValues.namespace || initialRelease?.namespace || 'default';
      const repoName = formValues.repoId || 'repo';
      const chartRef = formValues.chartOverride || formValues.chartDirect || defaultChartName || formValues.chart || 'chart';
      const selectedRepo = repos.find(r => r.id === (formValues.repoId || initialRelease?.repoId));
      const helmRepoUrl = formValues?.git?.repoUrl || selectedRepo?.url || 'resolved-by-backend';
      const pathPrefix = formValues?.git?.pathPrefix || 'clusters/default';
      const branch = formValues?.git?.branch || formValues?.git?.baseBranch || 'main';
      const targetPath = `${pathPrefix}/${ns}/${releaseName}`;
      const hr: any = {
        apiVersion: 'source.toolkit.fluxcd.io/v1',
        kind: 'HelmRepository',
        metadata: { name: repoName, namespace: 'flux-system' },
        spec: {
          interval: '5m',
          url: helmRepoUrl
        }
      };
      const release: any = {
        apiVersion: 'helm.toolkit.fluxcd.io/v2',
        kind: 'HelmRelease',
        metadata: { name: releaseName, namespace: ns },
        spec: {
          interval: '5m',
          chart: {
            spec: {
              chart: chartRef,
              sourceRef: {
                kind: 'HelmRepository',
                name: repoName,
                namespace: 'flux-system'
              }
            }
          },
          values: rawValues || {}
        }
      };
      const docs = [
        `# Path: ${targetPath} (branch ${branch})`,
        yaml.stringify(hr, { aliasDuplicateObjects: false }).trim(),
        yaml.stringify(release, { aliasDuplicateObjects: false }).trim()
      ].join('\n---\n');
      return docs;
    } catch (e) {
      return '# Flux manifest preview unavailable';
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deploymentModeWatch, previewYaml, editorYaml, defaultChartName, repos, initialRelease]);

  const buildFinalValues = () => {
    if (mode === 'upgrade') {
        // In upgrade mode, what's in the editor is final
        return parsedRef.current || {};
    }

    const raw = form.getFieldsValue(true) || {};
    if (editMode && !parseError && parsedRef.current) {
      return { ...raw, ...parsedRef.current };
    }
    return raw;
  };

  useEffect(() => {
    // Skip bindings logic in Upgrade mode
    if (mode === 'upgrade') return;

    if (!bindings.length || !watchedMounts) {
      prevMountsRef.current = watchedMounts || prevMountsRef.current;
      return;
    }

    const prevMounts = prevMountsRef.current || {};
    const current = form.getFieldsValue(true);
    const nextValues: any = {};
    let changed = false;

    // 1) primary field sync
    for (const b of bindings) {
      if (!(b as any).field || !(b as any).mountKey) continue;
      const spec = mountsSpec.find(s => s.key === (b as any).mountKey);
      const defMount = spec?.defaultMountPath || '/data';

      const newCfg = (watchedMounts as any)?.[(b as any).mountKey] || {};
      const oldCfg = prevMounts?.[(b as any).mountKey] || {};

      const newMountPath: string = newCfg.mountPath || defMount;
      const oldMountPath: string = oldCfg.mountPath || defMount;

      const suggested = `${newMountPath}${(b as any).suffix ?? ''}`;
      const fieldPath = toPath((b as any).field as string);
      const cur = getAt(current, fieldPath);

      const shouldSet = cur == null || cur === '' || (typeof cur === 'string' && cur.startsWith(oldMountPath));
      if (shouldSet && suggested && cur !== suggested) {
        setAt(nextValues, fieldPath, suggested);
        changed = true;
      }
    }

    // 2) targets[] patch
    const svcAny = (availableServices as any)?.[selectedServiceKey] as any;
    const varCtx = buildVarContext(svcAny?.variables, current, watchedMounts);
    const targetsPatch = makeTargetsPatch(bindings, watchedMounts, current, releaseNameWatch, varCtx);

    if (Object.keys(targetsPatch).length > 0) {
      deepMerge(nextValues, targetsPatch);
      changed = true;
    }

    if (changed) form.setFieldsValue(nextValues);
    prevMountsRef.current = watchedMounts;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(watchedMounts), JSON.stringify(bindings), JSON.stringify(mountsSpec), releaseNameWatch, mode]);

  /* -------- status/progress wiring -------- */

  const applyStatusToProgress = (s: CommandStatus & any) => {
    const titles =
      (Array.isArray(s?.steps) && s.steps.length > 0 && (s.steps as string[])) ||
      (Array.isArray(s?.progress?.steps) && s.progress.steps.length > 0 && (s.progress.steps as string[])) ||
      null;
    if (titles) setStepTitles(titles || []);
    if (typeof s?.message === 'string' && s.message) setStatusMsg(s.message);

    let idx: number | undefined;
    if (typeof s?.step === 'number') idx = s.step;
    else if (typeof s?.currentStepIndex === 'number') idx = Math.max(0, (s.currentStepIndex as number) - 1);
    else if (typeof s?.progress?.currentStepIndex === 'number') idx = Math.max(0, (s.progress?.currentStepIndex as number) - 1);
    if (typeof idx === 'number' && Number.isFinite(idx)) setStep(idx);

    let pct: number | undefined = typeof s?.percent === 'number' ? s.percent : undefined;
    if (typeof pct !== 'number') {
      const pstr = s?.progress?.percentage;
      if (typeof pstr === 'string') {
        const m = pstr.match(/\d+/);
        if (m) pct = parseInt(m[0], 10);
      }
    }
    if (typeof pct === 'number' && Number.isFinite(pct)) setPct(Math.max(0, Math.min(100, pct)));

    if (typeof pct === 'number' && !showProgress) setShowProgress(true);
    if (s?.state === 'SUCCEEDED') {
      setPct(100);
      if (!showProgress) setShowProgress(true);
    }
  };

  const pollStatus = async (id: string, onTick: (s: CommandStatus) => void) => {
    for (;;) {
      try {
        const s = await getCommandStatus(id);
        onTick(s);
        applyStatusToProgress(s as any);
        if (s.state === 'SUCCEEDED' || s.state === 'FAILED' || s.state === 'CANCELLED' || s.state === 'CANCELED') return s;
      } catch (err) {
        // keep polling even if one tick fails
        console.error('status poll failed', err);
      }
      await new Promise(r => setTimeout(r, 1300));
    }
  };

  /* -------- submit -------- */

  const handleDeploy = async () => {
    try {
      setIsDeploying(true);
      let payload: any = {};
      let params = new URLSearchParams();
      const modeFlag = form.getFieldValue('deploymentMode') || 'DIRECT_HELM';
      if (modeFlag === 'FLUX_GITOPS') {
        const gitCfg = form.getFieldValue('git') || {};
        if (!gitCfg.repoUrl) {
          throw new Error('Git repository is required for Flux GitOps.');
        }
      }

      if (mode === 'upgrade') {
        // --- UPGRADE MODE: Trust the YAML editor ---
        if (!editorYaml) {
            throw new Error("Configuration YAML is empty.");
        }
        const parsedValues = yaml.parse(editorYaml);

        // Basic payload for upgrade - null out the heavy automation parts
        payload = {
            chart: initialRelease?.chart,
            releaseName: initialRelease?.name,
            namespace: initialRelease?.namespace,
            values: parsedValues,
            mounts: null,        // Don't recreate mounts/secrets on upgrade
            dependencies: null,  // Don't redeploy dependencies on upgrade
            ranger: null,        // Don't re-run Ranger logic
            serviceKey: undefined,
            repoId: initialRelease?.repoId || form.getFieldValue('repoId'),
            deploymentMode: form.getFieldValue('deploymentMode') || 'DIRECT_HELM',
            git: form.getFieldValue('git') || undefined
        };

        if (initialRelease?.version) {
            params.set('version', initialRelease.version);
        }
        if (initialRelease?.repoId) {
            params.set('repoId', initialRelease.repoId);
        }

      } else {
        // --- DEPLOY MODE: Use Form + Automation ---
        if (!availableServices) return;

        // Validate visible fields, but read ALL values (including unmounted) so bindings can run on a complete dataset.
        await form.validateFields();
        const allValues = form.getFieldsValue(true) || {};

        const {
            svcKey,
            version,
            chartDirect,
            repoId: repoIdRaw,
            ...configValues
        } = allValues;

        let repoId = repoIdRaw ?? form.getFieldValue('repoId');

        const currentService = (availableServices as any)[svcKey ?? selectedServiceKey] as any;
        if (!currentService) {
            message.error('Please choose a service to install.');
            return;
        }

        message.loading({ content: `Deploying ${currentService.label} in progress...`, key: 'deploy' });

        // Repo sanity check logic...
        const defaultRepo = (availableServices as any)[svcKey]?.defaultRepo;
        if (installMode === 'repo' && defaultRepo && repoId !== defaultRepo && !overrideChart && !chartDirect && !(form.getFieldValue('chartOverride') || '').includes('/')) {
            const ok = await confirmMismatch(allValues.chart ?? defaultChartName, repoId, defaultRepo);
            if (!ok) return;
            form.setFieldsValue({ repoId: defaultRepo });
            repoId = defaultRepo;
        }

        let chartRef: string;
        if (installMode === 'repo') {
            const override = (form.getFieldValue('chartOverride') || '').trim();
            chartRef = overrideChart && override ? override : defaultChartName;
            if (!chartRef) throw new Error('Chart not found.');
        } else {
            const direct = (chartDirect || '').trim();
            if (direct) chartRef = direct;
            else if (currentService.chart) chartRef = currentService.chart;
            else throw new Error('Direct reference required.');
        }

        if (installMode === 'repo' && repoId) params.set('repoId', String(repoId));
        if (version) {
            params.set('version', String(version));
        } else {
            params.set('version', String(currentService.version));
        }

        // Deep-clone form values that go into the chart values to avoid mutating form state.
        const mergedValues = JSON.parse(JSON.stringify(configValues));
        const mounts = allValues.mounts || {};
        const bindingList = (currentService?.bindings || []) as BindingSpec[];
        const variableContext = buildVarContext((currentService as any)?.variables, allValues, mounts);
      // Bindings drive both Helm values and TLS wiring. Merge their results into the live values object.
      // This mirrors what the backend does with service.json bindings to produce final Helm values.
      const finalPatch = makeTargetsPatch(bindingList, mounts, allValues, allValues.releaseName, variableContext);
        
        if (Object.keys(finalPatch).length > 0) deepMerge(mergedValues, finalPatch);

        if (currentService?.form) {
            const excluded = getExcludedPaths(currentService.form);
            excluded.forEach(path => deleteAtStr(mergedValues, path));
        }

        let tlsPayload: any = null;
        const tlsSpec = (currentService as any)?.tls || [];
        if (Array.isArray(tlsSpec) && tlsSpec.length > 0) {
            // Build a TLS payload separate from chart values.
            // Backend (TlsManager) will use this to create keystore/PEM Secrets signed by the internal CA.
            // Chart wiring is still driven by bindings (server.config.https.*, global.security.tls.*).
            tlsPayload = {};
            tlsSpec.forEach((spec: any) => {
                const tlsKey = spec.key || 'tls';
                const formTls = (allValues as any)?.tls?.[tlsKey] || {};
                const merged = { ...(spec.defaults || {}), ...formTls };
                // Generate a password if autoGenerate is on and none provided, so config and Secret stay in sync.
                if ((merged.autoGenerate ?? spec.autoGenerate) && (!merged.password || merged.password === '')) {
                    merged.password = Math.random().toString(36).slice(2, 10);
                }

                // Resolve secret name and SANs using templates.
                const secretNameTpl = (formTls.secretName || '').trim() || spec.secretNameTemplate;
                const resolvedSecretName = (formTls.secretName || '').trim()
                    || (secretNameTpl ? renderTemplate(secretNameTpl, allValues) : `${allValues.releaseName}-${tlsKey}-tls`);
                const passwordSecretName = (formTls.passwordSecretName || '').trim() || `${resolvedSecretName}-pass`;
                const dnsTemplates: string[] = Array.isArray(spec.dnsTemplates) ? spec.dnsTemplates : [];
                const resolvedDnsNames: string[] = [];
                dnsTemplates.forEach(tpl => {
                    const rendered = renderTemplate(tpl, allValues);
                    if (rendered) resolvedDnsNames.push(rendered);
                });
                if (Array.isArray(formTls.extraDns)) {
                    formTls.extraDns.forEach((dnsEntry: any) => { if (dnsEntry) resolvedDnsNames.push(String(dnsEntry)); });
                }

                // Keep form context in sync so bindings can see resolved defaults.
                // This allows binding templates to reuse the resolved secret name/paths even if user left defaults.
                if (!allValues.tls) allValues.tls = {};
                if (!allValues.tls[tlsKey]) allValues.tls[tlsKey] = {};
                allValues.tls[tlsKey].secretName = resolvedSecretName;
                allValues.tls[tlsKey].passwordSecretName = passwordSecretName;
                allValues.tls[tlsKey].keystoreKey = merged.keystoreKey || spec.keystoreKey || 'keystore.p12';
                allValues.tls[tlsKey].password = merged.password || '';
                allValues.tls[tlsKey].passwordKey = merged.passwordKey || spec.passwordKey || 'truststore.password';
                allValues.tls[tlsKey].mountPath = merged.mountPath || spec.mountPath || '/etc/security/tls/https-keystore.p12';
                allValues.tls[tlsKey].keystorePath = merged.keystorePath || merged.mountPath || spec.keystorePath || '/etc/security/tls/https-keystore.p12';
                allValues.tls[tlsKey].keystoreType = merged.keystoreType || spec.keystoreType || 'PKCS12';
                allValues.tls[tlsKey].passwordMountPath = merged.passwordMountPath || spec.passwordMountPath || '/etc/trino/https-pass/password';

                tlsPayload[tlsKey] = {
                    enabled: merged.enabled ?? false,
                    autoGenerate: merged.autoGenerate ?? spec.autoGenerate ?? true,
                    port: merged.port ?? spec.defaultPort ?? 8443,
                    secretName: resolvedSecretName,
                    passwordSecretName,
                    keystoreKey: merged.keystoreKey || spec.keystoreKey || 'keystore.p12',
                    passwordKey: merged.passwordKey || spec.passwordKey || 'truststore.password',
                    keystoreType: merged.keystoreType || spec.keystoreType || 'PKCS12',
                    mountPath: merged.mountPath || spec.mountPath || '/etc/security/tls/https-keystore.p12',
                    keystorePath: merged.keystorePath || merged.mountPath || spec.keystorePath || '/etc/security/tls/https-keystore.p12',
                    password: merged.password || '',
                    passwordMountPath: merged.passwordMountPath || spec.passwordMountPath || '/etc/trino/https-pass/password',
                    dnsNames: resolvedDnsNames,
                    validityDays: merged.validityDays || spec.validityDays || 365
                };
            });
        }

        payload = {
            chart: chartRef,
            releaseName: allValues.releaseName,
            namespace: allValues.namespace,
            values: mergedValues,
            mounts,
            tls: tlsPayload || undefined,
            serviceKey: (allValues.svcKey ?? selectedServiceKey) || undefined,
            repoId: repoId || undefined,
            dependencies: currentService.dependencies || null,
            imageGlobalRegistryProperty: currentService.imageGlobalRegistryProperty || null,
            ranger: currentService.ranger || null,
            endpoints: currentService.endpoints || null,
            requiredConfigMaps: currentService.requiredConfigMaps || null,
            secretName: currentService.secretName || null,
            // Do not auto-apply a default security profile; only send if the user selected one.
            // Only send when explicitly selected; prevents implicit profile attachment.
            securityProfile: allValues.securityProfile || undefined,
            deploymentMode: allValues.deploymentMode || 'DIRECT_HELM',
            git: allValues.git || undefined,
        };
      } // End Deploy Mode Logic

      // --- Common Submit Logic ---
      const { id } = await (await import('../../api/client')).submitHelmDeploy(payload, params);

      const final = await pollStatus(id, s => {
        if ((s as any).message) message.loading({ content: (s as any).message, key: 'deploy', duration: 0 });
      });

      if (final.state === 'SUCCEEDED') {
        message.success({ content: `${mode === 'upgrade' ? 'Upgrade' : 'Deployment'} completed`, key: 'deploy', duration: 3 });
        onDeploy();
        onClose();
        form.resetFields();
      } else {
        message.error(final.error || 'Operation failed');
      }
    } catch (e: any) {
      console.error(e);
      message.error(e?.message ?? 'Operation failed');
    } finally {
      setIsDeploying(false);
    }
  };


  /* -------- render -------- */

  const modeHelp: Record<'repo' | 'direct', React.ReactNode> = {
    repo: (
      <span>
        Choose a <b>repository</b>, then specify a <b>chart name without "/"</b> (ex. <code>trino</code>). You can specify a <b>version</b> if needed.
      </span>
    ),
    direct: (
      <span>
        Provide a <b>complete reference</b> to the chart: <code>repo/chart</code>, <code>oci://…</code> or URL <code>.tgz</code>.
      </span>
    ),
  };

  // view and loadingChart already declared above


  // try to find "values.yaml" in common locations within a chart tarball
  const pickValuesYamlFile = (files: Array<{ name: string; blob: Blob }>) => {
    // Normalize to forward slashes and lower-case search
    const candidates = files.map(f => ({ ...f, path: f.name.replace(/\\/g, '/') }));
    // Preferred: <chart-name>/values.yaml
    let match = candidates.find(f => /(^|\/)values\.ya?ml$/i.test(f.path) && f.path.split('/').length > 1);
    if (!match) match = candidates.find(f => /(^|\/)values\.ya?ml$/i.test(f.path)); // any values.yaml
    return match || null;
  };

  const loadValuesFromTarGzArrayBuffer = async (buf: ArrayBuffer) => {
    // 1) gunzip
    const gunzipped = pako.ungzip(new Uint8Array(buf)).buffer; // ArrayBuffer
    // 2) untar
    const entries = await untar(gunzipped) as Array<{ name: string; buffer: ArrayBuffer }>;
    // 3) map to blobs
    const files = entries.map(e => ({ name: e.name, blob: new Blob([new Uint8Array(e.buffer)]) }));
    const match = pickValuesYamlFile(files);
    if (!match) throw new Error('values.yaml not found inside chart tarball');
    const text = await match.blob.text();
    return text;
  };

  const loadChartFromUpload = async (file: File) => {
    setLoadingChart(true);
    try {
      const buf = await file.arrayBuffer();
      const text = await loadValuesFromTarGzArrayBuffer(buf);
      setEditorYaml(text);
      setEditMode(true);      // allow editing right away
      setParseError(null);
      setView('yaml');
      message.success(`Loaded values.yaml from ${file.name}`);
    } catch (e: any) {
      console.error(e);
      message.error(e?.message || 'Failed to read chart tarball');
    } finally {
      setLoadingChart(false);
    }
  };

  const loadChartFromUrl = async (url: string) => {
    setLoadingChart(true);
    try {
      const res = await fetch(url, { mode: 'cors' }); // must be CORS-allowed
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = await res.arrayBuffer();
      const text = await loadValuesFromTarGzArrayBuffer(buf);
      setEditorYaml(text);
      setEditMode(true);
      setParseError(null);
      setView('yaml');
      message.success('Loaded values.yaml from URL');
    } catch (e: any) {
      console.error(e);
      message.error(e?.message || 'Failed to fetch or parse chart URL (CORS?)');
    } finally {
      setLoadingChart(false);
    }
  };
  const renderForm = () => {
    const showLoading = repoLoading || isLoading;
    const backdrop = showLoading ? (
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(255,255,255,0.7)', zIndex: 5, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ width: '100%', position: 'absolute', top: 0, left: 0 }}>
          <Progress percent={70} status="active" showInfo={false} strokeColor="#1677ff" />
        </div>
        <Spin size="large" tip={repoLoading ? 'Loading repositories...' : 'Loading services...'} />
      </div>
    ) : null;

    if (repoLoading || isLoading) {
      return (
        <div style={{ position: 'relative', minHeight: 300 }}>
          {backdrop}
        </div>
      );
    }
    if (error) return <Alert message="Error" description={error} type="error" showIcon />;
    if (!availableServices || !selectedServiceKey) {
      return (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin tip="Preparing form..." />
        </div>
      );
    }

    const currentService = (availableServices as any)[selectedServiceKey] as any;
    const serviceLabel = mode === 'upgrade' ? 'Installed service' : 'Service to install';

    return (
  <Form
    id="install_form"
    form={form}
    layout="vertical"
    name="install_form"
    size="small"
    onFinish={handleDeploy}
    onFinishFailed={({ errorFields }) => console.warn('install_form errors:', errorFields)}
  >
    <Space direction="vertical" style={{ width: '100%' }}>
  <Segmented
    value={view}
    onChange={(v) => setView(v as 'form' | 'yaml')}
    options={[
      { label: 'Configuration', value: 'form' },
      { label: 'Editor Preview', value: 'yaml' },
    ]}
    style={{ marginBottom: 8 }}
  />

      {view === 'form' ? (
        <>
      <Form.Item
        name="installMode"
        label={
          <>
            Installation mode{' '}
            <Tooltip title={modeHelp[installMode]}>
              <InfoCircleOutlined style={{ marginLeft: 8, color: 'rgba(0,0,0,.45)' }} />
            </Tooltip>
          </>
        }
        initialValue="repo"
        rules={[{ required: true }]}
      >
        <Radio.Group onChange={e => setInstallMode(e.target.value)} disabled={lockChart}>
          <Radio value="repo">From repository</Radio>
          <Radio value="direct">Direct reference</Radio>
        </Radio.Group>
      </Form.Item>

      <Form.Item
        name="deploymentMode"
        label="Deployment mode"
        initialValue="DIRECT_HELM"
        tooltip="Choose Direct Helm (default) or Flux GitOps (commit manifests to Git)"
        rules={[{ required: true }]}
      >
        <Radio.Group>
          <Radio value="DIRECT_HELM">Direct Helm</Radio>
          <Radio value="FLUX_GITOPS">Flux GitOps</Radio>
        </Radio.Group>
      </Form.Item>

      {deploymentModeWatch === 'FLUX_GITOPS' && (
        <Card size="small" title="Flux / GitOps settings" style={{ marginBottom: 12 }}>
          <Form.Item name={['git','repoId']} label="Git repository" tooltip="Select a saved Git repository or enter URL manually">
            <Select
              placeholder="Select Git repository or enter URL manually"
              allowClear
              showSearch
              optionFilterProp="label"
              dropdownRender={(menu) => (
                <>
                  {menu}
                  <Divider style={{ margin: '8px 0' }} />
                  <Space style={{ padding: '0 8px 4px' }}>
                    <Button
                      type="link"
                      icon={<LinkOutlined />}
                      onClick={() => window.open('/#/git-repositories', '_blank')}
                      size="small"
                    >
                      Manage Git Repositories
                    </Button>
                  </Space>
                </>
              )}
              onChange={(value) => {
                if (value && typeof value === 'string' && value !== '__manual__') {
                  // Load repo details and populate form
                  const repo = gitRepos.find((r) => r.id === value);
                  if (repo) {
                    form.setFieldsValue({
                      git: {
                        ...form.getFieldValue('git'),
                        repoUrl: repo.url,
                        credentialAlias: repo.credentialAlias,
                      }
                    });
                  }
                } else if (value === '__manual__') {
                  // Clear repo selection to allow manual input
                  form.setFieldsValue({
                    git: {
                      ...form.getFieldValue('git'),
                      repoId: undefined,
                    }
                  });
                }
              }}
            >
              <Option value="__manual__">Enter URL manually</Option>
              {gitRepos.map((repo) => (
                <Option key={repo.id} value={repo.id} label={`${repo.name} (${repo.url})`}>
                  {repo.name} - {repo.url}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name={['git','repoUrl']} label="Git repository URL" rules={[{ required: true, message: 'Git repository is required in GitOps mode' }]}>
            <Input placeholder="https://git.example.com/org/cluster-config.git" />
          </Form.Item>
          <Form.Item name={['git','baseBranch']} label="Base branch" tooltip="Branch to base commits/PRs on">
            <Input placeholder="main" />
          </Form.Item>
          <Form.Item
            name={['git','pathPrefix']}
            label="Path prefix"
            tooltip="Directory under the repo for Flux manifests (relative, no leading / or ..)"
            rules={[
              { required: true, message: 'Path prefix is required' },
              { pattern: /^(?!\/)(?!.*\.\.)(?!.*\s).*$/, message: 'Use a relative path without \"..\"' }
            ]}
          >
            <Input placeholder="clusters/default" />
          </Form.Item>
          <Form.Item name={['git','branch']} label="Working branch" tooltip="If empty, a branch will be generated automatically">
            <Input placeholder="feature/ambari-flux" />
          </Form.Item>
          <Form.Item name={['git','commitMode']} label="Commit mode" initialValue="DIRECT_COMMIT">
            <Select>
              <Option value="DIRECT_COMMIT">Direct commit</Option>
              <Option value="PR_MODE">Open PR (token required)</Option>
            </Select>
          </Form.Item>
          <Form.Item name={['git','credentialAlias']} label="Saved credential alias" tooltip="Reuse a previously stored token/SSH key (optional)">
            <Input placeholder="alias from credential store" />
          </Form.Item>
          <Form.Item name={['git','authToken']} label="Auth token" tooltip="HTTPS personal access token (stored securely)" >
            <Input.Password placeholder="Token (optional if using SSH key)" />
          </Form.Item>
          <Form.Item name={['git','sshKey']} label="SSH private key" tooltip="PEM private key (optional)" >
            <Input.TextArea rows={3} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" />
          </Form.Item>
        </Card>
      )}

      {installMode === 'repo' ? (
        <>
          <Form.Item name="repoId" label="Repository" rules={[{ required: true, message: 'Choose a repository' }]}>
            <Select placeholder="Choose a repository" loading={repoLoading}>
              {repos.map(r => (
                <Option key={r.id} value={r.id}>
                  {r.name} ({r.id})
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item label={lockChart ? 'Release chart' : 'Chart (default)'}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Input value={defaultChartName} disabled />
              {!lockChart && (
                <Checkbox
                  checked={overrideChart}
                  onChange={e => {
                    setOverrideChart(e.target.checked);
                    if (!e.target.checked) {
                      form.setFieldsValue({ chartOverride: undefined });
                      form.validateFields(['chartOverride']).catch(() => {});
                    }
                  }}
                >
                  Modify chart
                </Checkbox>
              )}
            </Space>
          </Form.Item>

          {!lockChart && overrideChart && (
            <Form.Item
              name="chartOverride"
              label="Chart (override)"
              tooltip="Simple chart name (without repo/ prefix). Ex: trino, nginx"
              rules={[
                { required: true, message: 'Please enter a chart name.' },
                { pattern: /^[^/]+$/, message: 'Do not include "/" (simple name only).' },
              ]}
            >
              <Input placeholder="ex: trino" />
            </Form.Item>
          )}

          <Form.Item name="version" label="Version (optional)">
            <Input placeholder="1.2.3" />
          </Form.Item>

          <Form.Item name="securityProfile" label="Security Profile" tooltip="Apply a global security profile (LDAP/AD/OIDC truststore wiring)">
            <Select allowClear placeholder="Default profile">
              {Object.keys(securityProfiles.profiles || {}).map(key => (
                <Option key={key} value={key}>{key}</Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item name="svcKey" label={serviceLabel} rules={[{ required: true, message: 'Please choose a service' }]}>
            <Select onChange={handleServiceChange} disabled={mode === 'upgrade'}>
              {Object.keys(availableServices).map(key => (
                <Option key={key} value={key}>
                  {availableServices[key].label}
                </Option>
              ))}
            </Select>
          </Form.Item>

          {Array.isArray(currentService?.mounts) && currentService.mounts.length > 0 && (
            <VolumeEditor specs={currentService.mounts as MountSpec[]} />
          )}

          {currentService.form.map((field: FormField) => (
            <DynamicFormField key={field.name} field={field} />
          ))}
        </>
      ) : (
        // installMode === 'direct' → no dynamic form; let user provide chart and we load values.yaml
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="Direct reference mode"
            description={
              <>
                Upload a Helm chart <code>.tgz</code> or enter a URL to load <code>values.yaml</code> for editing.
                The configuration form is disabled in this mode.
              </>
            }
          />

          {/* File upload */}
          <Upload
            accept=".tgz"
            maxCount={1}
            showUploadList={{ showRemoveIcon: true }}
            beforeUpload={() => false} // don't auto-upload; we parse locally
            onChange={async ({ file }) => {
              const f = file as UploadFile;
              if (f.originFileObj) {
                await loadChartFromUpload(f.originFileObj as File);
                form.setFieldsValue({ chartDirect: f.name });
              }
            }}
          >
            <Button icon={<UploadOutlined />} loading={loadingChart}>Upload chart (.tgz)</Button>
          </Upload>

          {/* Or URL input */}
          <Form.Item
            name="chartDirect"
            label="Chart URL (tgz) or OCI/Repo ref"
            tooltip="If you enter an HTTP(S) URL to a .tgz and CORS allows it, we’ll load values.yaml automatically."
            style={{ marginTop: 12 }}
            rules={[{ required: true }]}
          >
            <Input
              prefix={<LinkOutlined />}
              placeholder="https://.../chart.tgz  or  oci://repo/chart  or  repo/chart"
              onBlur={async (e) => {
                const v = (e.target.value || '').trim();
                if (v && /\.tgz(\?.*)?$/i.test(v)) {
                  await loadChartFromUrl(v);
                }
              }}
            />
          </Form.Item>
        </>
      )}
    </>
      ) : (
    // view === 'yaml'
    <>
      <Card
        title={
          <Space split={<span style={{ opacity: 0.4 }}>|</span>}>
            <Text strong>values.yaml</Text>
            <Space>
              <Text type="secondary">Edit mode</Text>
              {mode !== 'upgrade' && (
                  <Switch
                  checked={editMode}
                  onChange={(on) => {
                      setEditMode(on);
                      isDirtyRef.current = false;
                      if (!on) setParseError(null);
                  }}
                  />
              )}
              {mode === 'upgrade' && <Tag color="warning">Editable</Tag>}
            </Space>
          </Space>
        }
        size="small"
        style={{ marginTop: 8, marginBottom: 16 }}
      >
        {loadingChart ? (
          <div style={{ textAlign: 'center', padding: 24 }}><Spin tip="Loading chart..." /></div>
        ) : !editMode && mode !== 'upgrade' ? (
          <Editor
            height="360px"
            language="yaml"
            value={editorYaml || previewYaml}
            options={{ readOnly: true, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false }}
          />
        ) : (
          <>
            <Editor
              height="360px"
              language="yaml"
              value={editorYaml || previewYaml}
              onChange={onEditorChange}
              options={{ readOnly: false, automaticLayout: true, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false, wordWrap: 'on' }}
            />
            {parseError && (
              <Alert
                style={{ marginTop: 8 }}
                type="error"
                message="YAML error"
                description={<pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{parseError}</pre>}
                showIcon
              />
            )}
          </>
        )}
      </Card>

      {deploymentModeWatch === 'FLUX_GITOPS' && (
        <Card size="small" title="Flux manifests preview" style={{ marginBottom: 16 }}>
          <Typography.Paragraph>
            <Text type="secondary">HelmRepository and HelmRelease that will be committed when using GitOps.</Text>
          </Typography.Paragraph>
          <Editor
            height="280px"
            language="yaml"
            value={fluxManifestsYaml}
            options={{ readOnly: true, minimap: { enabled: false }, lineNumbers: 'on', scrollBeyondLastLine: false, wordWrap: 'on' }}
          />
        </Card>
      )}
    </>
  )}
</Space>


    {showProgress && (
      <>
        {stepTitles.length > 0 && (
          <Steps current={Math.min(step, Math.max(0, stepTitles.length - 1))} size="small" style={{ marginBottom: 12 }}>
            {stepTitles.map((t: string, i: number) => (
              <Step key={`${i}-${t}`} title={t} />
            ))}
          </Steps>
        )}
        {statusMsg && <div style={{ marginBottom: 8, fontWeight: 500 }}>{statusMsg}</div>}
        <Progress percent={percent} status={percent < 100 ? 'active' : 'success'} />
      </>
    )}
  </Form>
);

  };

  return (
    <Modal
      title={mode === 'upgrade' ? `Upgrade ${initialRelease?.name ?? ''}` : 'Install a Service via Helm'}
      open={visible}
      onCancel={onClose}
      width={900}
      okText={mode === 'upgrade' ? 'Update Release' : 'Deploy'}
      okButtonProps={{
        form: 'install_form', // Use form submit for deploy (handled by onFinish)
        htmlType: mode === 'upgrade' ? 'button' : 'submit', // Button for upgrade (manual click)
        onClick: mode === 'upgrade' ? handleDeploy : undefined, // Hook manual click only for upgrade
        loading: isDeploying || isLoading,
        disabled: (installMode === 'direct' && !editorYaml) || (mode === 'upgrade' && !editorYaml),
      }}
      maskClosable={false}
    >
      {renderForm()}
    </Modal>
  );

  
};

export default ServiceInstallationModal;
