import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, Typography, message } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { getVaultConfig, getVaultProfileUsage, getVaultSchema, saveVaultConfig, deleteVaultProfile } from '../api/client';
import type { VaultConfig, VaultProfiles } from '../api/client';

const { Title, Text } = Typography;
const { Option } = Select;

const GlobalVaultPage: React.FC = () => {
  const [form] = Form.useForm<VaultConfig>();
  const [loading, setLoading] = useState(false);
  const [profiles, setProfiles] = useState<Record<string, VaultConfig>>({});
  const [defaultProfile, setDefaultProfile] = useState<string | undefined>('default');
  const [currentProfile, setCurrentProfile] = useState<string | undefined>('default');
  const [isNewProfile, setIsNewProfile] = useState(false);
  const [schema, setSchema] = useState<any>();

  const defaultMode =
    schema?.properties?.find((p: any) => p.name === 'auth.method')?.default ||
    schema?.sections?.find((s: any) => s.name === 'auth.method')?.default ||
    'kubernetes';

  const schemaProperties = useMemo(() => {
    const props: any[] = [];
    if (Array.isArray(schema?.properties)) {
      props.push(...schema.properties);
    }
    if (Array.isArray(schema?.sections)) {
      schema.sections.forEach((section: any) => {
        const sectionProps = section?.properties || section?.fields;
        if (Array.isArray(sectionProps)) {
          props.push(...sectionProps);
        }
      });
    }
    return props.filter((prop) => prop?.name);
  }, [schema]);

  const mode = Form.useWatch(['auth', 'method'], form);

  const modeProperty = schemaProperties.find((prop: any) => prop.name === 'auth.method');
  const modeOptions = Array.isArray(modeProperty?.options)
    ? modeProperty.options
    : [
        { label: 'Kubernetes', value: 'kubernetes' },
        { label: 'AppRole', value: 'approle' },
      ];
  const modeLabel =
    modeOptions.find((option: any) => option?.value === mode)?.label || mode;

  const [deleteProfileLoading, setDeleteProfileLoading] = useState(false);
  const [usageModalVisible, setUsageModalVisible] = useState(false);
  const [usageReleases, setUsageReleases] = useState<string[]>([]);
  const [usageProfileLabel, setUsageProfileLabel] = useState('');

  const lookupHelmPath = (fieldPath: string, modeValue?: string) => {
    const search = (list?: any[]) => {
      if (!Array.isArray(list)) return undefined;
      const prop = list.find((p: any) => p.name === fieldPath);
      return prop?.helmPath;
    };
    let helmPath: any = search(schema?.properties);
    if (!helmPath && Array.isArray(schema?.sections)) {
      for (const section of schema.sections) {
        helmPath = search(section?.properties || section?.fields);
        if (helmPath) break;
      }
    }
    if (!helmPath) return undefined;
    if (typeof helmPath === 'string') return helmPath;
    if (modeValue && typeof helmPath === 'object') {
      return helmPath[modeValue] || helmPath[modeValue?.toLowerCase()];
    }
    return undefined;
  };

  const helmTitle = (fieldPath: string, modeValue?: string) => {
    const hp = lookupHelmPath(fieldPath, modeValue);
    return `Helm path: ${hp || `global.vault.${fieldPath}`}`;
  };

  const helmMeta = (fieldPath: string, modeValue?: string) => {
    const title = helmTitle(fieldPath, modeValue);
    return title ? <Text type="secondary" style={{ fontSize: 12 }}>{title}</Text> : undefined;
  };

  const schemaFieldMatchesMode = (prop: any, modeValue?: string) => {
    if (!modeValue) return true;
    if (!Array.isArray(prop?.modes) || prop.modes.length === 0) return true;
    return prop.modes.includes(modeValue);
  };

  const buildFieldExtra = (prop: any, modeValue?: string) => {
    const items: React.ReactNode[] = [];
    if (prop?.description) {
      items.push(
        <Text key={`${prop.name}-desc`} type="secondary" style={{ fontSize: 12 }}>
          {prop.description}
        </Text>
      );
    }
    const helmInfo = helmMeta(prop?.name, modeValue);
    if (helmInfo) {
      items.push(<span key={`${prop.name}-helm`}>{helmInfo}</span>);
    }
    if (!items.length) return undefined;
    return <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>{items}</div>;
  };

  const renderSchemaField = (prop: any, modeValue?: string) => {
    const namePath = String(prop?.name || '').split('.').filter(Boolean);
    const label = prop?.displayName || prop?.name;
    const isBoolean = prop?.type === 'boolean';
    const isPassword = prop?.type === 'password';
    const isSelect = prop?.type === 'select';
    const rules = prop?.required ? [{ required: true, message: `${label} is required` }] : undefined;
    let input = <Input />;
    if (isBoolean) {
      input = <Switch />;
    } else if (isPassword) {
      input = <Input.Password />;
    } else if (isSelect) {
      input = (
        <Select>
          {(prop?.options || []).map((option: any) => (
            <Option key={option?.value ?? option?.label} value={option?.value}>
              {option?.label ?? option?.value}
            </Option>
          ))}
        </Select>
      );
    }
    return (
      <Form.Item
        key={prop?.name}
        label={label}
        name={namePath}
        rules={rules}
        tooltip={helmTitle(prop?.name, modeValue)}
        extra={buildFieldExtra(prop, modeValue)}
        valuePropName={isBoolean ? 'checked' : undefined}
      >
        {input}
      </Form.Item>
    );
  };

  const applyProfileToForm = (cfg?: VaultConfig, fallbackMode?: string) => {
    const extraList = Object.entries(cfg?.extraProperties || {}).map(([key, value]) => ({
      key,
      value: value ?? '',
    }));
    form.setFieldsValue({
      auth: { ...(cfg?.auth || {}), method: cfg?.auth?.method || fallbackMode || defaultMode || 'kubernetes' },
      ...cfg,
      extraPropertiesList: extraList,
    } as any);
  };

  const load = async () => {
    setLoading(true);
    try {
      const [cfg, schemaDef] = await Promise.all([
        getVaultConfig(),
        getVaultSchema().catch(() => undefined)
      ]);
      setSchema(schemaDef);
      const schemaDefaultMode =
        schemaDef?.properties?.find((p: any) => p.name === 'auth.method')?.default ||
        schemaDef?.sections?.find((s: any) => s.name === 'auth.method')?.default;
      setProfiles(cfg.profiles || {});
      const initial = cfg.defaultProfile || Object.keys(cfg.profiles || {})[0] || 'default';
      setDefaultProfile(cfg.defaultProfile || initial);
      const selected = initial || 'default';
      setCurrentProfile(selected);
      applyProfileToForm(cfg.profiles?.[selected], schemaDefaultMode);
    } catch (e: any) {
      message.error(e?.message || 'Failed to load vault config');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const handleSave = async () => {
    try {
      const values: any = await form.validateFields();
      const profileName = currentProfile || 'default';
      const { extraPropertiesList, ...rest } = values;
      const extraProperties: Record<string, string> = {};
      (extraPropertiesList || []).forEach((row: any) => {
        if (row?.key) {
          extraProperties[row.key] = row.value ?? '';
        }
      });
      const nextProfiles = { ...profiles, [profileName]: { ...rest, extraProperties } };
      const payload: VaultProfiles = {
        defaultProfile,
        profiles: nextProfiles,
      };
      await saveVaultConfig(payload);
      setProfiles(nextProfiles);
      message.success('Vault configuration saved');
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || 'Failed to save vault config');
    }
  };

  const handleProfileSelect = (profileName: string) => {
    setCurrentProfile(profileName);
    setIsNewProfile(false);
    applyProfileToForm(profiles?.[profileName], defaultMode);
  };

  const handleCreateProfile = () => {
    let idx = 1;
    let base = 'profile';
    let name = `${base}-${idx}`;
    while (profiles[name]) {
      idx++;
      name = `${base}-${idx}`;
    }
    setProfiles({ ...profiles, [name]: { auth: { method: defaultMode } } });
    setCurrentProfile(name);
    setIsNewProfile(true);
    applyProfileToForm({ auth: { method: defaultMode } }, defaultMode);
  };

  const handleDelete = async () => {
    const profileToDelete = currentProfile;
    if (!profileToDelete) return;
    try {
      setDeleteProfileLoading(true);
      const usage = await getVaultProfileUsage(profileToDelete);
      if (usage?.releases?.length) {
        setUsageProfileLabel(profileToDelete);
        setUsageReleases(usage.releases);
        setUsageModalVisible(true);
        return;
      }
      Modal.confirm({
        title: `Delete vault profile "${profileToDelete}"?`,
        content: `This profile is not referenced by any releases.`,
        okType: 'danger',
        onOk: async () => {
          await deleteVaultProfile(profileToDelete);
          const updated = { ...profiles };
          delete updated[profileToDelete];
          setProfiles(updated);
          const next = Object.keys(updated)[0];
          setCurrentProfile(next);
          applyProfileToForm(updated[next], defaultMode);
          message.success('Vault profile deleted');
        }
      });
    } catch (error: any) {
      message.error(error?.message || 'Failed to delete vault profile');
    } finally {
      setDeleteProfileLoading(false);
    }
  };

  const handleDefaultChange = (profile: string) => {
    setDefaultProfile(profile);
  };

  return (
    <div style={{ padding: 16 }}>
      <Space style={{ marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>Vault Profiles</Title>
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>Reload</Button>
      </Space>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`Vault auth method: ${modeLabel || 'n/a'}`}
        description="These profiles feed global.vault.* helm values and CSI SecretProviderClass parameters."
      />

      <Space style={{ marginBottom: 16 }}>
        <Select
          value={currentProfile}
          onChange={handleProfileSelect}
          style={{ width: 260 }}
          placeholder="Select profile"
        >
          {Object.keys(profiles).map((p) => (
            <Option key={p} value={p}>{p}</Option>
          ))}
        </Select>
        <Button icon={<PlusOutlined />} onClick={handleCreateProfile}>New profile</Button>
        <Select
          value={defaultProfile}
          onChange={handleDefaultChange}
          style={{ width: 260 }}
          placeholder="Default profile"
        >
          {Object.keys(profiles).map((p) => (
            <Option key={p} value={p}>{p}</Option>
          ))}
        </Select>
      </Space>

      <Form form={form} layout="vertical" onFinish={handleSave}>
        {schemaProperties
          .filter((prop: any) => schemaFieldMatchesMode(prop, mode))
          .map((prop: any) => renderSchemaField(prop, mode))}

        <Form.List name="extraPropertiesList">
          {(fields, { add, remove }) => (
            <div style={{ marginTop: 12 }}>
              <Text strong>Extra overrides</Text>
              {fields.map(field => (
                <Space key={field.key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                  <Form.Item name={[field.name, 'key']} rules={[{ required: true, message: 'Key is required' }]}>
                    <Input placeholder="helm.path" />
                  </Form.Item>
                  <Form.Item name={[field.name, 'value']}>
                    <Input placeholder="value" />
                  </Form.Item>
                  <Button onClick={() => remove(field.name)} danger>Remove</Button>
                </Space>
              ))}
              <Button onClick={() => add()} type="dashed">Add override</Button>
            </div>
          )}
        </Form.List>

        <Space style={{ marginTop: 16 }}>
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={loading}>Save</Button>
          <Button danger icon={<DeleteOutlined />} onClick={handleDelete} disabled={!currentProfile} loading={deleteProfileLoading}>Delete</Button>
        </Space>
      </Form>

      <Modal
        title={`Vault profile "${usageProfileLabel}" is in use`}
        open={usageModalVisible}
        onCancel={() => setUsageModalVisible(false)}
        footer={[<Button key="ok" type="primary" onClick={() => setUsageModalVisible(false)}>OK</Button>]}
      >
        <p>Releases using this profile:</p>
        <ul>
          {usageReleases.map((r) => <li key={r}>{r}</li>)}
        </ul>
      </Modal>
    </div>
  );
};

export default GlobalVaultPage;
