import React, { useState, useEffect, useMemo } from 'react';
import { Modal, Form, Input, Select, Button, message, Spin, Alert, Checkbox, InputNumber, Card, Progress, Radio, Space, Steps, Tooltip } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { needsLogin, loginRepo, getHelmRepos, validateChart, deployHelm, getAvailableServices, getClusterServices, upgradeHelm, submitHelmDeploy, getCommandStatus, type CommandStatus } from '../api/client';
import type { FormField, AvailableServices, ClusterService } from '../types/ServiceTypes';
import type { HelmRepo } from '../types';

const { Option } = Select;
const { confirm: confirmModal } = Modal;
const { Step } = Steps;
/* -------------------- helpers -------------------- */

const ServiceSelect: React.FC<{ field: any }> = ({ field }) => {
  const [services, setServices] = useState<ClusterService[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    getClusterServices(field.serviceType)
      .then((s) => !cancelled && setServices(s))
      .finally(() => !cancelled && setIsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [field.serviceType]);

  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
  return (
    <Form.Item name={nameParts} label={field.label} help={field.help}>
      <Select loading={isLoading} allowClear placeholder="Aucun service sélectionné">
        {services.map((s) => (
          <Option key={s.value} value={s.value}>
            {s.label}
          </Option>
        ))}
      </Select>
    </Form.Item>
  );
};


/** Demande à l’utilisateur s’il veut basculer vers le dépôt conseillé. */
const confirmMismatch = (chart: string, currentRepo: string, suggestedRepo: string): Promise<boolean> =>
  new Promise((resolve) => {
    confirmModal({
      title: 'Chart et dépôt incohérents',
      content: (
        <span>
          Le chart <b>{chart}</b> n’existe probablement pas dans le dépôt <b>{currentRepo}</b>.<br />
          Voulez-vous utiliser le dépôt <b>{suggestedRepo}</b> ?
        </span>
      ),
      okText: `Basculer vers ${suggestedRepo}`,
      cancelText: 'Annuler',
      onOk() { resolve(true); },
      onCancel() { resolve(false); },
    });
  });

const DynamicFormField: React.FC<{ field: FormField; upgradeMode?: boolean }> = ({ field, upgradeMode }) => {
  const form = Form.useFormInstance();
  const rules = [{ required: (field as any).required, message: `Le champ '${field.label}' est requis.` }];
  const isLocked = upgradeMode && (field.name === 'releaseName' || field.name === 'namespace');
  const disabledProp = (field as any).disabled || isLocked;
  const nameParts = field.name.split('.');

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

/* -------------------- main component -------------------- */

type ServiceInstallationModalProps = {
  visible: boolean;
  onClose: () => void;
  onDeploy: () => void;
  /** NEW: open the modal in 'deploy' (default) or 'upgrade' mode */
  mode?: 'deploy' | 'upgrade';
  /** NEW: when upgrading, prefill name/ns (and optionally chart) */
  initialRelease?: { name: string; namespace: string; chart?: string };
};

const ServiceInstallationModal: React.FC<ServiceInstallationModalProps> = ({
  visible, onClose, onDeploy, mode = 'deploy', initialRelease
  }) => {
  
  const [form] = Form.useForm();

  // state
  const [isDeploying, setIsDeploying] = useState(false);
  const [availableServices, setAvailableServices] = useState<AvailableServices | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedServiceKey, setSelectedServiceKey] = useState<string>('');

  const [repos, setRepos] = useState<HelmRepo[]>([]);
  const [repoLoading, setRepoLoading] = useState(false);
  const [installMode, setInstallMode] = useState<'repo' | 'direct'>('repo');
  const [overrideChart, setOverrideChart] = useState(false);

  const [step, setStep]   = useState(0);      // 0,1,2
  const [percent, setPct] = useState(0);      // barre de progression
  // compute default chart name from top-level service.chart
  const lockChart = mode === 'upgrade';
  const releaseChartRef = initialRelease?.chart || '';

  const defaultChartName = useMemo(() => {
    if (lockChart && releaseChartRef) {
      // show exactly what the live release uses; don't try to trim the repo part
      return releaseChartRef;
    }
    const svc = availableServices?.[selectedServiceKey];
    if (!svc?.chart) return '';
    return svc.chart.split('/').pop() || svc.chart;
  }, [lockChart, releaseChartRef, availableServices, selectedServiceKey]);

  // helper to build default values for dynamic fields
  const setInitialValues = (fields: FormField[], currentValues: any) => {
    fields.forEach((field) => {
      if (field.type === 'group') {
        setInitialValues((field as any).fields, currentValues);
      } else if ((field as any).defaultValue !== undefined) {
        const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p) => p.replace(/__DOT__/g, '.'));
        let current = currentValues;
        for (let i = 0; i < nameParts.length - 1; i++) {
          current = (current[nameParts[i]] = current[nameParts[i]] || {});
        }
        current[nameParts[nameParts.length - 1]] = (field as any).defaultValue;
      }
    });
  };

    /* single load/init effect */
  useEffect(() => {
    if (!visible) return;

    // reset progress every time we open
    setStep(0);
    setPct(0);
    // En upgrade : pré-remplir nom + namespace d'emblée
    if (mode === 'upgrade' && initialRelease) {
      form.setFieldsValue({
        releaseName: initialRelease.name,
        namespace: initialRelease.namespace,
      });
    }

    let cancelled = false;
    setIsLoading(true);
    setRepoLoading(true);
    setError(null);

    (async () => {
      try {
        const [services, reposList] = await Promise.all([
          getAvailableServices(),
          getHelmRepos(),
        ]);
        if (cancelled) return;

        setAvailableServices(services);
        setRepos(reposList);
       // --- Try to deduce the service from the live release chart (upgrade) ---
       let deducedKey = '';
       if (mode === 'upgrade' && initialRelease?.chart) {
         const live = initialRelease.chart.toLowerCase();                 // e.g. 'prometheus-community/prometheus'
         const liveLast = live.split('/').pop() || live;                  // 'prometheus'
         const match = Object.entries(services).find(([k, v]: any) => {
           const def = (v.chart || '').toLowerCase();                     // e.g. 'prometheus-community/prometheus'
           const defLast = def.split('/').pop() || def;                   // 'prometheus'
           return def === live || defLast === liveLast;
         });
         if (match) deducedKey = match[0];
       }
        // 1) Choisir la clé de service active
        let svcKey = selectedServiceKey || deducedKey
        if (!svcKey) {
          if (mode === 'upgrade' && initialRelease?.chart) {
            // essaie de retrouver le service par le nom du chart (sans préfixe repo/)
            const wanted = initialRelease.chart.split('/').pop()?.toLowerCase();
            const match = Object.entries(services).find(([_, v]: any) =>
              (v.chart || '').split('/').pop()?.toLowerCase() === wanted
            );
            svcKey = match?.[0];
          }
          if (!svcKey) {
            const keys = Object.keys(services);
            svcKey = keys[0] || '';
          }
        }

        if (svcKey) {
          setSelectedServiceKey(svcKey);

          // 2) Valeurs par défaut dynamiques du service
          const initialValues: any = {};
          if (services[svcKey]?.form) {
            setInitialValues(services[svcKey].form, initialValues);
          }

          // 3) Appliquer au formulaire
          form.setFieldsValue({
            svcKey: svcKey, // <- IMPORTANT
            ...initialValues,
            ...(mode === 'upgrade' && initialRelease
              ? { releaseName: initialRelease.name, namespace: initialRelease.namespace }
              : {}
            )
          });
        }

        // 4) Pré-sélection dépôt : 'defaultRepo' si dispo, sinon premier
        if (!form.getFieldValue('repoId') && reposList.length > 0) {
          const pref = services[svcKey!]?.defaultRepo;
          const repoId =
            (pref && reposList.find(r => r.id === pref)?.id) ||
            reposList[0].id;
          form.setFieldsValue({ repoId });
        }
      } catch (e: any) {
        if (!cancelled) {
          console.error(e);
          setError('Impossible de charger les services ou dépôts. Veuillez réessayer.');
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

  useEffect(() => {
    if (selectedServiceKey) {
      form.setFieldsValue({ svcKey: selectedServiceKey });
    }
  }, [selectedServiceKey]);

  useEffect(() => {
    if (!visible) {
      form.resetFields();
      setSelectedServiceKey('');
      setInstallMode('repo');
      setOverrideChart(false);
      setStep(0);
      setPct(0);

    }
  }, [visible]);

  const handleServiceChange = (value: string) => {
    if (!availableServices) return;
    setSelectedServiceKey(value);

    // Preserve a few globals
    const { installMode, repoId: prevRepoId, version } = form.getFieldsValue(['installMode','repoId','version']);

    // Build initial values for the new service
    const initialValues: any = {};
    setInitialValues(availableServices[value].form, initialValues);

    // Prefer defaultRepo if present
    let nextRepoId = prevRepoId;
    const preferred = availableServices[value]?.defaultRepo;
    if (preferred && repos.find(r => r.id === preferred)) {
      nextRepoId = preferred;
    }

    form.setFieldsValue({
      svcKey: value,
      installMode: installMode ?? 'repo',
      repoId: nextRepoId,
      version,
      ...initialValues,
    });
  };

  const pollStatus = async (id: string, onTick: (s: CommandStatus)=>void) => {
    for (;;) {
      const s = await getCommandStatus(id);
      onTick(s);
      if (s.state === 'SUCCEEDED' || s.state === 'FAILED' || s.state === 'CANCELLED') return s;
      await new Promise(r => setTimeout(r, 1300));
    }
  };
  const handleDeploy = async () => {
    if (!availableServices) return;
    try {
      const values = await form.validateFields();
      setIsDeploying(true);

      const { svcKey, repoId, version, chartDirect, ...configValues } = values;
      const currentService = availableServices[svcKey ?? selectedServiceKey];
      if (!currentService) {
        message.error('Veuillez choisir un service à installer.');
        return;
      }

      message.loading({ content: `Déploiement de ${currentService.label} en cours...`, key: 'deploy' });

      // before showing confirm, guard on defaultRepo
      const defaultRepo = availableServices[svcKey]?.defaultRepo;

      if (
        installMode === 'repo' &&
        defaultRepo &&                             // <— guard: only if we have one
        repoId !== defaultRepo &&
        !overrideChart &&
        !chartDirect &&
        !(form.getFieldValue('chartOverride') || '').includes('/')
      ) {
        const ok = await confirmMismatch(values.chart ?? defaultChartName, repoId, defaultRepo);
        if (!ok) return;
        form.setFieldsValue({ repoId: defaultRepo });
        values.repoId = defaultRepo;
      }


      // build chartRef
      let chartRef: string;
      if (installMode === 'repo') {
        if (lockChart && releaseChartRef) {
          chartRef = releaseChartRef; // upgrade = use the live chart ref
        } else {
          const override = (form.getFieldValue('chartOverride') || '').trim();
          chartRef = overrideChart && override ? override : defaultChartName;
        }
        if (!chartRef) throw new Error('Chart introuvable : vérifiez charts.json ou saisissez un override.');
      } else {
        const direct = (chartDirect || '').trim();
        if (direct) chartRef = direct;
        else if (
          currentService.chart &&
          (currentService.chart.startsWith('oci://') || currentService.chart.endsWith('.tgz') || currentService.chart.includes('/'))
        ) {
          chartRef = currentService.chart;
        } else {
          throw new Error('Référence directe requise (oci://, URL .tgz, ou repo/chart).');
        }
      }

      // query params
      const params = new URLSearchParams();
      if (installMode === 'repo') params.set('repoId', repoId);
      if (version) params.set('version', version);

      const payload = {
        chart: chartRef,
        releaseName: values.releaseName,
        namespace: values.namespace,
        values: values,
        serviceKey: (values.svcKey ?? selectedServiceKey) || undefined
      };

      const { id } = await submitHelmDeploy(payload, params);

      // polling
      setStep(0); setPct(10);
      const final = await pollStatus(id, (s) => {
        setStep(s.step);
        setPct(s.percent);
        if (s.message) message.loading({ content: s.message, key:'deploy', duration: 0 });
      });

      if (final.state === 'SUCCEEDED') {
        message.success({ content: 'Déploiement terminé', key: 'deploy', duration: 3 });
        onDeploy();
        onClose();
        form.resetFields();
      } else {
        message.error(final.error || 'Échec du déploiement');
      }
    } catch (e:any) {
      console.error(e);
      message.error(e?.message ?? 'Échec du déploiement');
    } finally {
      setIsDeploying(false);
    }
  };

  /* -------------------- render -------------------- */

  const modeHelp: Record<'repo' | 'direct', React.ReactNode> = {
    repo: (
      <span>
        Choisissez un <b>dépôt</b>, puis indiquez un <b>nom de chart sans “/”</b> (ex. <code>trino</code>).
        Vous pouvez préciser une <b>version</b> si besoin.
      </span>
    ),
    direct: (
      <span>
        Fournissez une <b>référence complète</b> du chart : <code>repo/chart</code>, <code>oci://…</code> ou URL <code>.tgz</code>.
      </span>
    ),
  };

  const renderForm = () => {
    console.log("DEBUG: renderform: rendering form with mode", installMode, "and service", selectedServiceKey, "mode:", mode);
    if (repoLoading) return <Spin tip="Chargement des dépôts..." />;
    if (isLoading) return <div style={{ textAlign: 'center', padding: '40px 0' }}><Spin tip="Chargement des services..." /></div>;
    if (error) return <Alert message="Erreur" description={error} type="error" showIcon />;
    if (!availableServices || !selectedServiceKey) {
      return <div style={{ textAlign: 'center', padding: 40 }}>
        <Spin tip="Préparation du formulaire..." />
      </div>;
    }

    const currentService = availableServices[selectedServiceKey];
    const serviceLabel = mode === 'upgrade' ? 'Service installé' : 'Service à installer';

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
              Mode d'installation
              <Tooltip title={modeHelp[installMode]}>
                <InfoCircleOutlined style={{ marginLeft: 8, color: 'rgba(0,0,0,.45)' }} />
              </Tooltip>
            </>
          }
          initialValue="repo"
          rules={[{ required: true }]}
        >
          <Radio.Group onChange={(e) => setInstallMode(e.target.value)} disabled={lockChart}>
            <Radio value="repo">Depuis un dépôt</Radio>
            <Radio value="direct">Référence directe</Radio>
          </Radio.Group>
        </Form.Item>

        {installMode === 'repo' && (
          <>
              <Form.Item
                name="repoId"
                label="Dépôt"
                rules={[{ required: true, message: 'Choisissez un dépôt' }]}
              >
                <Select placeholder="Choisir un dépôt" loading={repoLoading}>
                  {repos.map((r) => (
                    <Option key={r.id} value={r.id}>
                      {r.name} ({r.id})
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            <Form.Item label={lockChart ? "Chart de la release" : "Chart (par défaut)"}>
              <Space direction="vertical" style={{ width: '100%' }}>
              <Input value={defaultChartName} disabled />
              {!lockChart && (
                <Checkbox
                  checked={overrideChart}
                  onChange={(e) => {
                    setOverrideChart(e.target.checked);
                    if (!e.target.checked) {
                      form.setFieldsValue({ chartOverride: undefined });
                      form.validateFields(['chartOverride']).catch(() => {});
                    }
                  }}
                >
                  Modifier le chart
                </Checkbox>
              )}
              </Space>
            </Form.Item>

            {!lockChart && overrideChart && (
              <Form.Item
                name="chartOverride"
                label="Chart (override)"
                tooltip="Nom simple du chart (sans préfixe repo/). Ex: trino, nginx"
                rules={[
                  { required: true, message: 'Veuillez saisir un nom de chart.' },
                  { pattern: /^[^/]+$/, message: 'Ne pas inclure de “/” (nom simple uniquement).' },
                ]}
              >
                <Input placeholder="ex: trino" />
              </Form.Item>
            )}
          </>
        )}

        {installMode === 'direct' && (
          <Form.Item name="chartDirect" label="Chart / URL / oci://" rules={[{ required: true }]}>
            <Input placeholder="https://.../chart.tgz ou oci://repo/chart" />
          </Form.Item>
        )}

        <Form.Item name="version" label="Version (option)">
          <Input placeholder="1.2.3" />
        </Form.Item>

        <Form.Item
          name="svcKey"
          label={serviceLabel}
          rules={[{ required: true, message: 'Veuillez choisir un service' }]}
        >
          <Select onChange={handleServiceChange} disabled={mode === 'upgrade'}>
            {Object.keys(availableServices).map((key) => (
              <Option key={key} value={key}>{availableServices[key].label}</Option>
            ))}
          </Select>
        </Form.Item>

        {currentService.form.map((field) => (
          <DynamicFormField key={field.name} field={field} />
        ))}
        <Steps current={step} size="small" style={{ marginBottom: 16 }}>
          <Step title="Validation" />
          <Step title="Sync dépôt" />
          <Step title="Déploiement" />
        </Steps>
        <Progress percent={percent} status={percent < 100 ? 'active' : 'success'} />
      </Form>
    );
  };

  return (
    <Modal
      title={mode === 'upgrade'
      ? `Mettre à jour ${initialRelease?.name ?? ''}`
      : 'Installer un Service via Helm'}
      open={visible}
      onCancel={onClose}
      width={700}
      okText="Déployer"
      okButtonProps={{ form: 'install_form', htmlType: 'submit', loading: isDeploying || isLoading }}
      maskClosable={false}
    >
      {renderForm()}
    </Modal>
  );
};

export default ServiceInstallationModal;
