import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Modal, Form, Input, Select, message, Spin, Alert, Checkbox, InputNumber, Card, Progress, Radio, Space, Steps, Switch, Tooltip,  Typography } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { getHelmRepos, getAvailableServices, getClusterServices, submitHelmDeploy, getCommandStatus, type CommandStatus } from '../api/client';
import type { FormField, AvailableServices, ClusterService } from '../types/ServiceTypes';
import type { HelmRepo } from '../types';
import type { MountSpec } from '../types/MountSpec';
import Editor from '@monaco-editor/react';
import yaml from 'yaml';
import debounce from 'lodash.debounce';
const { Text } = Typography;

const { Option } = Select;
const { confirm: confirmModal } = Modal;
const { Step } = Steps;

/* =========================================================================================
   1) Types & helpers
   ========================================================================================= */

type BindingSpecBase = { role?: string; field?: string; mountKey?: string; suffix?: string };

// Optional targets enrich the final values (and now also apply reactively)
type BindingTarget =
  | {
      path: string;                                         // dot path; [] means append to array
      kind?: 'volume' | 'mount';                            // for K8s shapes built from mount spec
      mountKey?: string;
      op?: 'set' | 'merge';
      value?: any;
      from?: { type: 'mountPath'; mountKey: string; suffix?: string }
          | { type: 'form'; field: string; suffix?: string };
    };

type BindingSpec = BindingSpecBase & { targets?: BindingTarget[] };

/** Escaped-dot aware path helpers (support "node\.data-dir") */
const pathToParts = (p: string) => p.replace(/\\\./g, '__DOT__').split('.').map(s => s.replace(/__DOT__/g, '.'));
const getAtStr = (obj: any, path: string) => pathToParts(path).reduce((o, k) => (o ? o[k] : undefined), obj);
const ensureAtStr = (obj: any, path: string, init: any) => {
  const parts = pathToParts(path);
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
    cur = cur[k];
  }
  const leaf = parts[parts.length - 1];
  if (cur[leaf] == null) cur[leaf] = typeof init === 'function' ? init() : init;
  return cur[leaf];
};
const setAtStr = (obj: any, path: string, value: any) => {
  const parts = pathToParts(path);
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    const k = parts[i];
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {};
    cur = cur[k];
  }
  cur[parts[parts.length - 1]] = value;
};
const parentOfArrayPath = (p: string) => (p.endsWith('[]') ? p.slice(0, -2) : p);
const pushUniqueBy = (arr: any[], key: (x: any) => string, entry: any) => {
  if (!arr.some(e => key(e) === key(entry))) arr.push(entry);
};

/** Helpers used by the form-field sync half (existing behavior) */
const toPath = (p: string) => pathToParts(p);
const getAt = (obj: any, path: string[]): any => path.reduce((o, k) => (o ? o[k] : undefined), obj);
const setAt = (obj: any, path: string[], value: any) => {
  let o = obj;
  for (let i = 0; i < path.length - 1; i++) {
    o[path[i]] ??= {};
    o = o[path[i]];
  }
  o[path[path.length - 1]] = value;
};

/** Small deep merge for sparse patches */
const deepMerge = (dst: any, src: any) => {
  for (const k of Object.keys(src)) {
    const sv = src[k];
    if (sv && typeof sv === 'object' && !Array.isArray(sv)) {
      dst[k] ??= {};
      deepMerge(dst[k], sv);
    } else {
      dst[k] = sv;
    }
  }
};

/* =========================================================================================
   2) Storage / Mounts editor
   ========================================================================================= */

const VolumeEditor: React.FC<{ specs: MountSpec[] }> = ({ specs }) => {
  const form = Form.useFormInstance();

  // Initialize mount defaults once (on service switch)
  useEffect(() => {
    const initial: any = {};
    for (const s of specs) {
      initial[s.key] = {
        type: s.defaults.type,
        mountPath: s.defaultMountPath,
        size: s.defaults.size || '10Gi',
        storageClass: s.defaults.storageClass || '',
        accessModes: s.defaults.accessModes || ['ReadWriteOnce'],
      };
    }
    form.setFieldsValue({ mounts: initial });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [specs?.map(s => s.key).join(',')]);

  return (
    <Card title="Storage / Mounts" size="small" style={{ marginBottom: 16 }}>
      {specs.map(s => (
        <div key={s.key} style={{ borderTop: '1px solid #f0f0f0', paddingTop: 12, marginTop: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>{s.label}</div>

          <Form.Item name={['mounts', s.key, 'type']} label="Type" initialValue={s.defaults.type}>
            <Select style={{ maxWidth: 240 }}>
              {s.supportedTypes.map(t => (
                <Option key={t} value={t}>
                  {t}
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item
            name={['mounts', s.key, 'mountPath']}
            label="Mount path"
            initialValue={s.defaultMountPath}
            rules={[{ required: true, message: 'Mount path is required' }]}
          >
            <Input style={{ maxWidth: 400 }} />
          </Form.Item>

          {/* PVC-only fields */}
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue(['mounts', s.key, 'type']) === 'pvc' ? (
                <>
                  <Form.Item name={['mounts', s.key, 'size']} label="Size" initialValue={s.defaults.size}>
                    <Input placeholder="e.g. 20Gi" style={{ maxWidth: 240 }} />
                  </Form.Item>
                  <Form.Item name={['mounts', s.key, 'storageClass']} label="StorageClass">
                    <Input placeholder="(default)" style={{ maxWidth: 240 }} />
                  </Form.Item>
                  <Form.Item name={['mounts', s.key, 'accessModes']} label="Access modes" initialValue={s.defaults.accessModes}>
                    <Select mode="multiple" style={{ maxWidth: 400 }}>
                      <Option value="ReadWriteOnce">ReadWriteOnce</Option>
                      <Option value="ReadOnlyMany">ReadOnlyMany</Option>
                      <Option value="ReadWriteMany">ReadWriteMany</Option>
                    </Select>
                  </Form.Item>
                </>
              ) : null
            }
          </Form.Item>

          {/* S3 placeholder */}
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue(['mounts', s.key, 'type']) === 's3' ? (
                <Alert type="info" message="S3-backed PV is not implemented yet in this UI. Choose emptyDir or PVC for now." showIcon />
              ) : null
            }
          </Form.Item>
        </div>
      ))}
    </Card>
  );
};

/* =========================================================================================
   3) Other helpers
   ========================================================================================= */

const ServiceSelect: React.FC<{ field: any }> = ({ field }) => {
  const [services, setServices] = useState<ClusterService[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    getClusterServices(field.serviceType)
      .then(s => !cancelled && setServices(s))
      .finally(() => !cancelled && setIsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [field.serviceType]);

  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
  return (
    <Form.Item name={nameParts} label={field.label} help={field.help}>
      <Select loading={isLoading} allowClear placeholder="No service selected">
        {services.map(s => (
          <Option key={s.value} value={s.value}>
            {s.label}
          </Option>
        ))}
      </Select>
    </Form.Item>
  );
};

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
      onOk() {
        resolve(true);
      },
      onCancel() {
        resolve(false);
      },
    });
  });

const DynamicFormField: React.FC<{ field: FormField; upgradeMode?: boolean }> = ({ field, upgradeMode }) => {
  const form = Form.useFormInstance();
  const rules = [{ required: (field as any).required, message: `Field '${field.label}' is required.` }];
  const isLocked = upgradeMode && (field.name === 'releaseName' || field.name === 'namespace');
  const disabledProp = (field as any).disabled || isLocked;
  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));

  const condition = (field as any).condition;
  let isVisible = true;
  if (condition) {
    const watchedValue = Form.useWatch(condition.field.split('.'), form);
    isVisible = watchedValue === condition.value;
  }
  if (!isVisible) return null;

  switch (field.type) {
    case 'group':
      return (
        <Card title={field.label} size="small" style={{ marginBottom: 16 }}>
          {(field as any).fields.map((subField: FormField) => (
            <DynamicFormField key={subField.name} field={subField} />
          ))}
        </Card>
      );
    case 'service-select':
      return <ServiceSelect field={field as any} />;
    case 'select':
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Select disabled={disabledProp}>
            {(field as any).options.map((opt: any) => (
              <Option key={opt.value} value={opt.value}>
                {opt.label}
              </Option>
            ))}
          </Select>
        </Form.Item>
      );
    case 'number':
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <InputNumber style={{ width: '100%' }} disabled={disabledProp} />
        </Form.Item>
      );
    case 'boolean':
      return (
        <Form.Item name={nameParts} label={field.label} valuePropName="checked" help={field.help}>
          <Checkbox disabled={disabledProp} />
        </Form.Item>
      );
    case 'string':
    default:
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Input disabled={disabledProp} />
        </Form.Item>
      );
  }
};

/* =========================================================================================
   4) Main component
   ========================================================================================= */

type ServiceInstallationModalProps = {
  visible: boolean;
  onClose: () => void;
  onDeploy: () => void;
  mode?: 'deploy' | 'upgrade';
  initialRelease?: { name: string; namespace: string; chart?: string };
};

const ServiceInstallationModal: React.FC<ServiceInstallationModalProps> = ({ visible, onClose, onDeploy, mode = 'deploy', initialRelease }) => {
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

    setStep(0);
    setPct(0);
    setStepTitles([]);
    setShowProgress(false);

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
          if (services[svcKey]?.form) setInitialValues(services[svcKey].form, initialValues);
          form.setFieldsValue({
            svcKey,
            ...initialValues,
            ...(mode === 'upgrade' && initialRelease ? { releaseName: initialRelease.name, namespace: initialRelease.namespace } : {}),
          });
        }

        if (!form.getFieldValue('repoId') && reposList.length > 0) {
          const pref = services[svcKey!]?.defaultRepo;
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

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, mode, initialRelease]);

  useEffect(() => {
    if (selectedServiceKey) form.setFieldsValue({ svcKey: selectedServiceKey });
  }, [selectedServiceKey]);

  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setSelectedServiceKey('');
      setInstallMode('repo');
      setOverrideChart(false);
      setStep(0);
      setPct(0);
      setStepTitles([]);
      setShowProgress(false);
    }
  }, [visible]);

  const handleServiceChange = (value: string) => {
    if (!availableServices) return;
    setSelectedServiceKey(value);
    prevMountsRef.current = null;

    const { installMode, repoId: prevRepoId, version } = form.getFieldsValue(['installMode', 'repoId', 'version']);
    const initialValues: any = {};
    setInitialValues(availableServices[value].form, initialValues);

    let nextRepoId = prevRepoId;
    const preferred = availableServices[value]?.defaultRepo;
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
    const svc = availableServices?.[selectedServiceKey] as any;
    return Array.isArray(svc?.mounts) ? (svc.mounts as MountSpec[]) : [];
  }, [availableServices, selectedServiceKey]);

  const bindings = useMemo<BindingSpec[]>(() => {
    const svc = availableServices?.[selectedServiceKey] as any;
    return Array.isArray(svc?.bindings) ? (svc.bindings as BindingSpec[]) : [];
  }, [availableServices, selectedServiceKey]);

  const watchedMounts = Form.useWatch(['mounts'], form);
  const releaseNameWatch = Form.useWatch(['releaseName'], form);

  // Build a sparse "targets patch" object for current mounts + bindings
  const makeTargetsPatch = (bindingList: BindingSpec[], mounts: any, formVals: any, releaseName?: string) => {
    const patch: any = {};

    const valueFromTargetSource = (t: BindingTarget): any => {
      if (!t.from) return t.value;
      if (t.from.type === 'mountPath') {
        const m = mounts?.[t.from.mountKey];
        const base = m?.mountPath || '/data';
        return base + (t.from.suffix || '');
      }
      if (t.from.type === 'form') {
        const v = getAtStr(formVals, t.from.field);
        return (v == null ? '' : String(v)) + (t.from.suffix || '');
      }
      return undefined;
    };

    for (const b of bindingList || []) {
      if (!Array.isArray(b.targets)) continue;
      for (const t of b.targets) {
        if (t.path.endsWith('[]')) {
          const arr = ensureAtStr(patch, parentOfArrayPath(t.path), []);
          if (t.kind === 'volume') {
            const key = t.mountKey!;
            const m = mounts?.[key] || {};
            const type = String(m?.type || 'emptyDir').toLowerCase();
            const name = key;
            const entry =
              type === 'pvc'
                ? { name, persistentVolumeClaim: { claimName: `${releaseName ?? ''}-${key}`.replace(/^-/, '') } }
                : { name, emptyDir: {} };
            pushUniqueBy(arr, (x: any) => String(x?.name), entry);
          } else if (t.kind === 'mount') {
            const key = t.mountKey!;
            const m = mounts?.[key] || {};
            const entry = { name: key, mountPath: m?.mountPath || '/data' };
            pushUniqueBy(arr, (x: any) => `${x?.name}@@${x?.mountPath}`, entry);
          } else {
            const val = valueFromTargetSource(t);
            if (val !== undefined) pushUniqueBy(arr, (x: any) => JSON.stringify(x), val);
          }
          continue;
        }

        const val = valueFromTargetSource(t);
        if (val === undefined) continue;

        if (t.op === 'merge') {
          const obj = ensureAtStr(patch, t.path, {});
          Object.assign(obj, val);
        } else {
          setAtStr(patch, t.path, val);
        }
      }
    }
    return patch;
  };

function valueFromTargetSource(t: BindingTarget, mounts: Record<string, any>, formVals: any): any {
    if (!t.from) return t.value;
    if (t.from.type === 'mountPath') {
      const m = mounts?.[t.from.mountKey];
      const base = m?.mountPath || '/data';
      return base + (t.from.suffix || '');
    }
    if (t.from.type === 'form') {
      const v = getAtStr(formVals, t.from.field);
      return (v == null ? '' : String(v)) + (t.from.suffix || '');
    }
    return undefined;
  }
function applyBindingTargets(
    mergedValues: any,
    bindingList: BindingSpec[] | undefined,
    mounts: Record<string, any>,
    formValues: any,
    releaseName: string
  ) {
    if (!bindingList) return;
    for (const b of bindingList) {
      if (!Array.isArray(b.targets)) continue;
      for (const t of b.targets) {
        if (t.path.endsWith('[]')) {
          // array append branch
          const arr = ensureAtStr(mergedValues, parentOfArrayPath(t.path), []);
          if (t.kind === 'volume') {
            const key = t.mountKey!;
            const m = mounts?.[key] || {};
            const type = String(m?.type || 'emptyDir').toLowerCase();
            const name = key;
            const entry = type === 'pvc' ? { name, persistentVolumeClaim: { claimName: `${releaseName}-${key}` } } : { name, emptyDir: {} };
            pushUniqueBy(arr, (x: any) => String(x?.name), entry);
          } else if (t.kind === 'mount') {
            const key = t.mountKey!;
            const m = mounts?.[key] || {};
            const entry = { name: key, mountPath: m?.mountPath || '/data' };
            pushUniqueBy(arr, (x: any) => `${x?.name}@@${x?.mountPath}`, entry);
          } else {
            const val = valueFromTargetSource(t, mounts, formValues);
            if (val !== undefined) pushUniqueBy(arr, (x: any) => JSON.stringify(x), val);
          }
          continue;
        }

        // scalar/object branch
        const val = valueFromTargetSource(t, mounts, formValues);
        if (val === undefined) continue;

        if (t.op === 'merge') {
          const obj = ensureAtStr(mergedValues, t.path, {});
          Object.assign(obj, val);
        } else {
          setAtStr(mergedValues, t.path, val);
        }
      }
    }
  }

const allValues = Form.useWatch([], form); // watch the whole form
// --- Live values.yaml preview (form values + targets applied) ---
const previewYaml = useMemo(() => {
  try {
    // Get a deep snapshot of the whole form
    const raw = form.getFieldsValue(true) || {};
    const mounts = raw.mounts || {};
    // Start from the same base we send at submit:
    const mergedValues = { ...raw };
    applyBindingTargets(mergedValues, bindings, mounts, raw, raw.releaseName || '');
    // Don't show noisy client-only keys:
    delete mergedValues.installMode;
    delete mergedValues.chartDirect;
    delete mergedValues.chartOverride;
    delete mergedValues.repoId;
    delete mergedValues.version;
    delete mergedValues.svcKey;
    // Emit YAML
    return yaml.stringify(mergedValues, { aliasDuplicateObjects: false });
  } catch (e) {
    return '# (preview unavailable)';
  }
  // Recompute when form or bindings/mounts change
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [allValues, JSON.stringify(form.getFieldsValue(true)), JSON.stringify(bindings), JSON.stringify(watchedMounts)]);

  // --- 2) Local editor state
    const [editMode, setEditMode] = useState(false);
    const [editorYaml, setEditorYaml] = useState(previewYaml);
    const [parseError, setParseError] = useState<string | null>(null);

    // Helps avoid loops when form updates while the user is mid-edit
    const isDirtyRef = useRef(false);

    // Whenever the preview changes (form changed), update the editor buffer
    // unless the user has unsaved edits.
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
      // Deep-merge YAML over form values (replace with a real deep merge lib if needed)
      return { ...raw, ...parsedRef.current };
    }
    return raw;
  };

  // Reactively apply:
  //  - the primary "field" updates (node.data-dir …)
  //  - plus targets[] patch (upstream.* etc.)
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
      if (!b.field || !b.mountKey) continue;
      const spec = mountsSpec.find(s => s.key === b.mountKey);
      const defMount = spec?.defaultMountPath || '/data';

      const newCfg = watchedMounts?.[b.mountKey] || {};
      const oldCfg = prevMounts?.[b.mountKey] || {};

      const newMountPath: string = newCfg.mountPath || defMount;
      const oldMountPath: string = oldCfg.mountPath || defMount;

      const suggested = `${newMountPath}${b.suffix ?? ''}`;
      const fieldPath = toPath(b.field);
      const cur = getAt(current, fieldPath);

      const shouldSet = cur == null || cur === '' || (typeof cur === 'string' && cur.startsWith(oldMountPath));
      if (shouldSet && suggested && cur !== suggested) {
        setAt(nextValues, fieldPath, suggested);
        changed = true;
      }
    }

    // 2) targets[] patch
    const targetsPatch = makeTargetsPatch(bindings, watchedMounts, current, releaseNameWatch);
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
      const values = await form.validateFields();
      setIsDeploying(true);

      const { svcKey, repoId, version, chartDirect, ...configValues } = values;
      const currentService = availableServices[svcKey ?? selectedServiceKey] as any;
      if (!currentService) {
        message.error('Please choose a service to install.');
        return;
      }

      message.loading({ content: `Deploying ${currentService.label} in progress...`, key: 'deploy' });

      // repo sanity helper
      const defaultRepo = availableServices[svcKey]?.defaultRepo;
      if (installMode === 'repo' && defaultRepo && repoId !== defaultRepo && !overrideChart && !chartDirect && !(form.getFieldValue('chartOverride') || '').includes('/')) {
        const ok = await confirmMismatch(values.chart ?? defaultChartName, repoId, defaultRepo);
        if (!ok) return;
        form.setFieldsValue({ repoId: defaultRepo });
        values.repoId = defaultRepo;
      }

      // chart ref
      let chartRef: string;
      if (installMode === 'repo') {
        if (lockChart && releaseChartRef) chartRef = releaseChartRef;
        else {
          const override = (form.getFieldValue('chartOverride') || '').trim();
          chartRef = overrideChart && override ? override : defaultChartName;
        }
        if (!chartRef) throw new Error('Chart not found: check charts.json or enter an override.');
      } else {
        const direct = (chartDirect || '').trim();
        if (direct) chartRef = direct;
        else if (currentService.chart && (currentService.chart.startsWith('oci://') || currentService.chart.endsWith('.tgz') || currentService.chart.includes('/'))) {
          chartRef = currentService.chart;
        } else {
          throw new Error('Direct reference required (oci://, URL .tgz, or repo/chart).');
        }
      }

      // query params
      const params = new URLSearchParams();
      if (installMode === 'repo') params.set('repoId', repoId);
      if (version) params.set('version', version);

      const dependencies = currentService.dependencies || null;
      const secretName = currentService.secretName || null;

      // === FINAL VALUES ENRICHMENT (bindings.targets) ===
      const mergedValues = { ...configValues };
      const mounts = form.getFieldValue(['mounts']) || {};
      const bindingList = (currentService?.bindings || []) as BindingSpec[];

      // produce sparse patch and merge into mergedValues
      const finalPatch = (() => {
        // reuse the same logic as the reactive pass
        const patch = makeTargetsPatch(bindingList, mounts, values, values.releaseName);
        return patch;
      })();
      if (Object.keys(finalPatch).length > 0) deepMerge(mergedValues, finalPatch);

      const payload = {
        chart: chartRef,
        releaseName: values.releaseName,
        namespace: values.namespace,
        values: mergedValues,      // final values
        mounts,                    // meta for backend PVC creation
        serviceKey: (values.svcKey ?? selectedServiceKey) || undefined,
        dependencies,
        secretName,
      };

      const { id } = await submitHelmDeploy(payload, params);

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

    const currentService = availableServices[selectedServiceKey] as any;
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

        {installMode === 'repo' && (
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
          </>
        )}

        {installMode === 'direct' && (
          <Form.Item name="chartDirect" label="Chart / URL / oci://" rules={[{ required: true }]}>
            <Input placeholder="https://.../chart.tgz or oci://repo/chart" />
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

           <Card
      title={
        <Space split={<span style={{ opacity: 0.4 }}>|</span>}>
          <Text strong>values.yaml</Text>
          <Space>
            <Text type="secondary">Edit mode</Text>
            <Switch checked={editMode} onChange={(on) => {
              setEditMode(on);
              isDirtyRef.current = false; // resync when switching
              if (!on) setParseError(null);
            }} />
          </Space>
        </Space>
      }
      size="small"
      style={{ marginTop: 16, marginBottom: 16 }}
    >
      {!editMode ? (
        // Read-only “preview” mode
        <Editor
          height="320px"
          language="yaml"
          value={previewYaml}
          options={{
            readOnly: true,
            minimap: { enabled: false },
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
          }}
        />
      ) : (
        <>
          <Editor
            height="320px"
            language="yaml"
            value={editorYaml}
            onChange={onEditorChange}
            options={{
              readOnly: false,
              automaticLayout: true,
              minimap: { enabled: false },
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              wordWrap: 'on',
            }}
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

      {/* Example: somewhere in your submit handler, call buildFinalValues() */}
      {/* <Button onClick={() => doSubmit(buildFinalValues())}>Deploy</Button> */}
    </Card>

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
      okButtonProps={{ form: 'install_form', htmlType: 'submit', loading: isDeploying || isLoading }}
      maskClosable={false}
    >
      {renderForm()}
    </Modal>
  );
};

export default ServiceInstallationModal;
