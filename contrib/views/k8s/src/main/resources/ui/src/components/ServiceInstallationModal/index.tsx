import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Button, Modal, Form, Input, Select, message, Spin, Alert, Checkbox, Card, Progress, Radio, Segmented, Space, Steps, Switch, Tabs, Tooltip, Typography,
Upload} from 'antd';

import { InfoCircleOutlined } from '@ant-design/icons';
import Editor from '@monaco-editor/react';
import yaml from 'yaml';
import debounce from 'lodash.debounce';

import { getHelmRepos, getAvailableServices, submitHelmDeploy, getCommandStatus, type CommandStatus } from '../../api/client';
import type { FormField, AvailableServices } from '../../types/ServiceTypes';
import type { HelmRepo } from '../../types';
import type { MountSpec } from '../../types/MountSpec';

import { UploadOutlined, LinkOutlined } from '@ant-design/icons';
import * as pako from 'pako';
import { untar } from 'js-untar';

import VolumeEditor from './VolumeEditor';
import DynamicFormField from './DynamicFormField';
import type { BindingSpec } from './types';
import {
  makeTargetsPatch,
  deepMerge,
  toPath, getAt, setAt,
  applyBindingTargets, interpolate, interpolateStr,
  buildVarContext,
} from './bindings';

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

/* ---------------------------------- props ----------------------------------- */

type ServiceInstallationModalProps = {
  visible: boolean;
  onClose: () => void;
  onDeploy: () => void;
  mode?: 'deploy' | 'upgrade';
  initialRelease?: { name: string; namespace: string; chart?: string };
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
  const [repoLoading, setRepoLoading] = useState(false);
  const [installMode, setInstallMode] = useState<'repo' | 'direct'>('repo');
  const [overrideChart, setOverrideChart] = useState(false);

  const [step, setStep] = useState(0);
  const [percent, setPct] = useState(0);
  const [stepTitles, setStepTitles] = useState<string[]>([]);
  const [showProgress, setShowProgress] = useState(false);

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

    setStep(0); setPct(0); setStepTitles([]); setShowProgress(false);

    if (mode === 'upgrade' && initialRelease) {
      form.setFieldsValue({ releaseName: initialRelease.name, namespace: initialRelease.namespace });
    }

    let cancelled = false;
    setIsLoading(true);
    setRepoLoading(true);
    setError(null);

    (async () => {
      try {
        const [services, reposList] = await Promise.all([getAvailableServices(), getHelmRepos()]);
        if (cancelled) return;

        setAvailableServices(services);
        setRepos(reposList);

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
            ...initialValues,
            ...(mode === 'upgrade' && initialRelease ? { releaseName: initialRelease.name, namespace: initialRelease.namespace } : {}),
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
  }, [visible, mode, initialRelease]);

  useEffect(() => { if (selectedServiceKey) form.setFieldsValue({ svcKey: selectedServiceKey }); }, [selectedServiceKey]);

  useEffect(() => {
    if (installMode === 'direct') {
      setView('yaml');          // show YAML editor
    } else {
      setView('form');          // show configuration form
    }
  }, [installMode]);
  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setSelectedServiceKey('');
      setInstallMode('repo');
      setOverrideChart(false);
      setStep(0); setPct(0); setStepTitles([]); setShowProgress(false);
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
    setSelectedServiceKey(value);
    prevMountsRef.current = null;

    const { installMode, repoId: prevRepoId, version } = form.getFieldsValue(['installMode', 'repoId', 'version']);
    const initialValues: any = {};
    setInitialValues((availableServices as any)[value].form, initialValues);

    let nextRepoId = prevRepoId;
    const preferred = (availableServices as any)[value]?.defaultRepo;
    if (preferred && repos.find(r => r.id === preferred)) nextRepoId = preferred;

    form.setFieldsValue({
      svcKey: value,
      installMode: installMode ?? 'repo',
      repoId: nextRepoId,
      version,
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

  const watchedMounts = Form.useWatch(['mounts'], form);
  const releaseNameWatch = Form.useWatch(['releaseName'], form);

  const allValues = Form.useWatch([], form); // watch the whole form

  // --- Live values.yaml preview (form values + targets applied) ---
  const previewYaml = useMemo(() => {
    try {
      const raw = form.getFieldsValue(true) || {};
      const mounts = raw.mounts || {};
      const mergedValues = { ...raw };
      // NEW: resolve charts.json variables + templates before applying targets
      const svcAny = (availableServices as any)?.[selectedServiceKey] as any;
      const varCtx = buildVarContext(svcAny?.variables, raw, mounts);
      applyBindingTargets(mergedValues, bindings, mounts, raw, raw.releaseName || '', varCtx);
      delete mergedValues.installMode;
      delete mergedValues.chartDirect;
      delete mergedValues.chartOverride;
      delete mergedValues.repoId;
      delete mergedValues.version;
      delete mergedValues.svcKey;
      return yaml.stringify(mergedValues, { aliasDuplicateObjects: false });
    } catch (e) {
      return '# (preview unavailable)';
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allValues, JSON.stringify(form.getFieldsValue(true)), JSON.stringify(bindings), JSON.stringify(watchedMounts)]);

  // --- 2) Local editor state
  const [editMode, setEditMode] = useState(false);
  const [editorYaml, setEditorYaml] = useState(previewYaml);
  const [parseError, setParseError] = useState<string | null>(null);
  const isDirtyRef = useRef(false);

  useEffect(() => {
    if (!isDirtyRef.current) {
      setEditorYaml(previewYaml);
      setParseError(null);
    }
  }, [previewYaml]);

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

  const buildFinalValues = () => {
    const raw = form.getFieldsValue(true) || {};
    if (editMode && !parseError && parsedRef.current) {
      return { ...raw, ...parsedRef.current };
    }
    return raw;
  };

  useEffect(() => {
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
  }, [JSON.stringify(watchedMounts), JSON.stringify(bindings), JSON.stringify(mountsSpec), releaseNameWatch]);

  /* -------- status/progress wiring -------- */

  const applyStatusToProgress = (s: CommandStatus & any) => {
    const titles =
      (Array.isArray(s?.steps) && s.steps.length > 0 && (s.steps as string[])) ||
      (Array.isArray(s?.progress?.steps) && s.progress.steps.length > 0 && (s.progress.steps as string[])) ||
      null;
    if (titles) setStepTitles(titles || []);

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

    if (titles && typeof pct === 'number' && !showProgress) setShowProgress(true);
    if (s?.state === 'SUCCEEDED') {
      setPct(100);
      if (titles && !showProgress) setShowProgress(true);
    }
  };

  const pollStatus = async (id: string, onTick: (s: CommandStatus) => void) => {
    for (;;) {
      const s = await getCommandStatus(id);
      onTick(s);
      applyStatusToProgress(s as any);
      if (s.state === 'SUCCEEDED' || s.state === 'FAILED' || s.state === 'CANCELLED') return s;
      await new Promise(r => setTimeout(r, 1300));
    }
  };

  /* -------- submit -------- */

  const handleDeploy = async () => {
    if (!availableServices) return;
    try {
      // Validate visible fields, but read ALL values (including unmounted)
      await form.validateFields();
      const allValues = form.getFieldsValue(true) || {};
      setIsDeploying(true);

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

      // --- repo sanity helper (works even if repoId field is unmounted) ---
      const defaultRepo = (availableServices as any)[svcKey]?.defaultRepo;
      if (
        installMode === 'repo' &&
        defaultRepo &&
        repoId !== defaultRepo &&
        !overrideChart &&
        !chartDirect &&
        !(form.getFieldValue('chartOverride') || '').includes('/')
      ) {
        const ok = await confirmMismatch(allValues.chart ?? defaultChartName, repoId, defaultRepo);
        if (!ok) return;
        form.setFieldsValue({ repoId: defaultRepo });
        repoId = defaultRepo; // keep local copy in sync
      }

      // --- resolve chart reference ---
      let chartRef: string;
      if (installMode === 'repo') {
        if (lockChart && releaseChartRef) {
          chartRef = releaseChartRef;
        } else {
          const override = (form.getFieldValue('chartOverride') || '').trim();
          chartRef = overrideChart && override ? override : defaultChartName;
        }
        if (!chartRef) throw new Error('Chart not found: check charts.json or enter an override.');
      } else {
        const direct = (chartDirect || '').trim();
        if (direct) chartRef = direct;
        else if (
          currentService.chart &&
          (currentService.chart.startsWith('oci://') ||
            currentService.chart.endsWith('.tgz') ||
            currentService.chart.includes('/'))
        ) {
          chartRef = currentService.chart;
        } else {
          throw new Error('Direct reference required (oci://, URL .tgz, or repo/chart).');
        }
      }

      // --- query params ---
      const params = new URLSearchParams();
      if (installMode === 'repo' && repoId) params.set('repoId', String(repoId));
      if (version) params.set('version', String(version));

      const dependencies = currentService.dependencies || null;
      const secretName = currentService.secretName || null;

      // --- values merging (bindings.targets) ---
      const mergedValues = { ...configValues };
      const mounts = allValues.mounts || {};
      const bindingList = (currentService?.bindings || []) as BindingSpec[];
      // NEW: build var context from charts.json service.variables (generic)
      const varCtx = buildVarContext((currentService as any)?.variables, allValues, mounts);

      const finalPatch = makeTargetsPatch(bindingList, mounts, allValues, allValues.releaseName, varCtx);
      if (Object.keys(finalPatch).length > 0) deepMerge(mergedValues, finalPatch);

      const payload = {
        chart: chartRef,
        releaseName: allValues.releaseName,
        namespace: allValues.namespace,
        values: mergedValues,
        mounts,
        serviceKey: (allValues.svcKey ?? selectedServiceKey) || undefined,
        dependencies,
        secretName,
      };

      const { id } = await (await import('../../api/client')).submitHelmDeploy(payload, params);

      const final = await pollStatus(id, s => {
        if ((s as any).message) message.loading({ content: (s as any).message, key: 'deploy', duration: 0 });
      });

      if (final.state === 'SUCCEEDED') {
        message.success({ content: 'Deployment completed', key: 'deploy', duration: 3 });
        onDeploy();
        onClose();
        form.resetFields();
      } else {
        message.error(final.error || 'Deployment failed');
      }
    } catch (e: any) {
      console.error(e);
      message.error(e?.message ?? 'Deployment failed');
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

  const [view, setView] = useState<'form' | 'yaml'>('form');
  const [loadingChart, setLoadingChart] = useState(false);


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
    if (repoLoading) return <Spin tip="Loading repositories..." />;
    if (isLoading) return <div style={{ textAlign: 'center', padding: '40px 0' }}><Spin tip="Loading services..." /></div>;
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
    <Card
      title={
        <Space split={<span style={{ opacity: 0.4 }}>|</span>}>
          <Text strong>values.yaml</Text>
          <Space>
            <Text type="secondary">Edit mode</Text>
            <Switch
              checked={editMode}
              onChange={(on) => {
                setEditMode(on);
                isDirtyRef.current = false;
                if (!on) setParseError(null);
              }}
            />
          </Space>
        </Space>
      }
      size="small"
      style={{ marginTop: 8, marginBottom: 16 }}
    >
      {loadingChart ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spin tip="Loading chart..." /></div>
      ) : !editMode ? (
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
  )}
</Space>


    {showProgress && stepTitles.length > 0 && (
      <>
        <Steps current={Math.min(step, Math.max(0, stepTitles.length - 1))} size="small" style={{ marginBottom: 16 }}>
          {stepTitles.map((t: string, i: number) => (
            <Step key={`${i}-${t}`} title={t} />
          ))}
        </Steps>
        <Progress percent={percent} status={percent < 100 ? 'active' : 'success'} />
      </>
    )}
  </Form>
);

  };

  return (
    <Modal
      title={mode === 'upgrade' ? `Update ${initialRelease?.name ?? ''}` : 'Install a Service via Helm'}
      open={visible}
      onCancel={onClose}
      width={700}
      okText="Deploy"
      okButtonProps={{
        form: 'install_form',
        htmlType: 'submit',
        loading: isDeploying || isLoading,
        disabled: installMode === 'direct' && !editorYaml, // <— add this
      }}
      maskClosable={false}
    >
      {renderForm()}
    </Modal>
  );

  
};



export default ServiceInstallationModal;
