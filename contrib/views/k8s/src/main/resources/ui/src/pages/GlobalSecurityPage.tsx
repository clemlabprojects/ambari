import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Switch, Typography, message, Tooltip } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { getSecurityConfig, getSecurityProfileUsage, getSecuritySchema, saveSecurityConfig, deleteSecurityProfile } from '../api/client';
import type { SecurityConfig, SecurityProfiles } from '../api/client';

const { Title, Text } = Typography;
const { Option } = Select;

const GlobalSecurityPage: React.FC = () => {
  const [form] = Form.useForm<SecurityConfig>();
  const [loading, setLoading] = useState(false);
  const [profiles, setProfiles] = useState<Record<string, SecurityConfig>>({});
  const [defaultProfile, setDefaultProfile] = useState<string | undefined>('default');
  const [currentProfile, setCurrentProfile] = useState<string | undefined>('default');
  const [isNewProfile, setIsNewProfile] = useState(false);
  const [schema, setSchema] = useState<any>();

  const defaultMode =
    schema?.properties?.find((p: any) => p.name === 'mode')?.default ||
    schema?.sections?.find((s: any) => s.name === 'mode')?.default ||
    'none';

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

  const mode = Form.useWatch('mode', form);

  const modeProperty = schemaProperties.find((prop: any) => prop.name === 'mode');
  const modeOptions = Array.isArray(modeProperty?.options)
    ? modeProperty.options
    : [
        { label: 'None', value: 'none' },
        { label: 'LDAP', value: 'ldap' },
        { label: 'Active Directory', value: 'ad' },
        { label: 'OIDC', value: 'oidc' },
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
    // show a deterministic fallback so users always see something on hover
    return `Helm path: ${hp || `global.security.${fieldPath}`}`;
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

  const modeFields = (prefix: string, modeValue?: string) =>
    schemaProperties.filter(
      (prop: any) =>
        String(prop?.name || '').startsWith(`${prefix}.`) &&
        schemaFieldMatchesMode(prop, modeValue)
    );

  const applyProfileToForm = (cfg?: SecurityConfig, fallbackMode?: string) => {
    const extraList = Object.entries(cfg?.extraProperties || {}).map(([key, value]) => ({
      key,
      value: value ?? '',
    }));
    form.setFieldsValue({
      mode: cfg?.mode || fallbackMode || defaultMode || 'none',
      ...cfg,
      extraPropertiesList: extraList,
    });
  };

  const load = async () => {
    setLoading(true);
    try {
      const [cfg, schemaDef] = await Promise.all([
        getSecurityConfig(),
        getSecuritySchema().catch(() => undefined)
      ]);
      setSchema(schemaDef);
      const schemaDefaultMode =
        schemaDef?.properties?.find((p: any) => p.name === 'mode')?.default ||
        schemaDef?.sections?.find((s: any) => s.name === 'mode')?.default;
      setProfiles(cfg.profiles || {});
      const initial = cfg.defaultProfile || Object.keys(cfg.profiles || {})[0] || 'default';
      setDefaultProfile(cfg.defaultProfile || initial);
      const selected = initial || 'default';
      setCurrentProfile(selected);
      applyProfileToForm(cfg.profiles?.[selected], schemaDefaultMode);
    } catch (e: any) {
      message.error(e?.message || 'Failed to load security config');
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
      const payload: SecurityProfiles = {
        defaultProfile,
        profiles: nextProfiles,
      };
      await saveSecurityConfig(payload);
      setProfiles(nextProfiles);
      message.success('Security configuration saved');
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message || 'Failed to save security config');
    }
  };

  const handleDeleteProfile = async () => {
    if (!currentProfile || isNewProfile || !profiles[currentProfile]) {
      message.warning('Select an existing profile before attempting to delete.');
      return;
    }
    const profileToDelete = currentProfile;
    setDeleteProfileLoading(true);
    try {
      const usage = await getSecurityProfileUsage(profileToDelete);
      if (usage.releases?.length) {
        setUsageProfileLabel(profileToDelete);
        setUsageReleases(usage.releases);
        setUsageModalVisible(true);
        return;
      }
      Modal.confirm({
        title: `Delete security profile "${profileToDelete}"?`,
        content: (
          <span>
            This action will remove the profile permanently. Any releases tied to it will require
            a new security profile before future deploys.
          </span>
        ),
        okText: 'Delete',
        okButtonProps: { danger: true },
        onOk: async () => {
          setDeleteProfileLoading(true);
          try {
            await deleteSecurityProfile(profileToDelete);
            message.success('Security profile deleted');
            setIsNewProfile(false);
            await load();
          } catch (error: any) {
            message.error(error?.message || 'Failed to delete security profile');
          } finally {
            setDeleteProfileLoading(false);
          }
        }
      });
    } catch (error: any) {
      message.error(error?.message || 'Unable to evaluate profile usage');
    } finally {
      setDeleteProfileLoading(false);
    }
  };

  const closeUsageModal = () => {
    setUsageModalVisible(false);
    setUsageReleases([]);
    setUsageProfileLabel('');
  };

  const canDeleteProfile = Boolean(currentProfile && !isNewProfile && profiles[currentProfile]);
  const usageModalTitle = usageProfileLabel ? `Profile "${usageProfileLabel}" is in use` : 'Profile in use';

  return (
    <div>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={3}>Global Security</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>Refresh</Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={loading}>Save</Button>
          <Button
            icon={<DeleteOutlined />}
            danger
            loading={deleteProfileLoading}
            onClick={handleDeleteProfile}
            disabled={!canDeleteProfile}
          >
            Delete profile
          </Button>
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="These settings apply to all Helm installs that support global security wiring (LDAP/AD/OIDC, truststore)."
      />

      <Form form={form} layout="vertical">
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
            <Form.Item label="Profile">
              <Select
                value={currentProfile}
                onChange={(val) => {
                  setIsNewProfile(false);
                  setCurrentProfile(val);
                  applyProfileToForm(profiles[val]);
                }}
                dropdownRender={(menu) => (
                  <>
                    {menu}
                    <div style={{ padding: 8 }}>
                      <Button type="link" onClick={() => { setIsNewProfile(true); setCurrentProfile(undefined); applyProfileToForm(undefined); }}>
                        + New profile
                      </Button>
                    </div>
                  </>
                )}
              >
                {Object.keys(profiles).map((p) => (
                  <Option key={p} value={p}>{p}</Option>
                ))}
              </Select>
            </Form.Item>
            {isNewProfile && (
              <Form.Item label="New Profile Name" required>
                <Input
                  placeholder="e.g. ldap-corp"
                  value={currentProfile}
                  onChange={(e) => setCurrentProfile(e.target.value)}
                />
              </Form.Item>
            )}
          </div>

          <Form.Item label="Set as Default" valuePropName="checked">
            <Switch
              checked={defaultProfile === currentProfile}
              onChange={(checked) => {
                if (checked && currentProfile) {
                    setDefaultProfile(currentProfile);
                }
              }}
            />
          </Form.Item>

          <Form.Item
            label="Mode"
            name="mode"
            initialValue="none"
            rules={[{ required: true }]}
            tooltip={helmTitle('mode')}
            extra={helmMeta('mode')}
          >
            <Select>
              {modeOptions.map((option: any) => (
                <Option key={option?.value ?? option?.label} value={option?.value}>
                  {option?.label ?? option?.value}
                </Option>
              ))}
            </Select>
          </Form.Item>

          {(mode === 'ldap' || mode === 'ad') && (
            <div style={{ marginBottom: 16 }}>
              <Title level={4} style={{ marginBottom: 8 }}>{modeLabel || (mode === 'ad' ? 'Active Directory' : 'LDAP')}</Title>
              {modeFields('ldap', mode).map((prop: any) => renderSchemaField(prop, mode))}
            </div>
          )}

          {mode === 'oidc' && (
            <div style={{ marginBottom: 16 }}>
              <Title level={4} style={{ marginBottom: 8 }}>{modeLabel || 'OIDC'}</Title>
              {modeFields('oidc', mode).map((prop: any) => renderSchemaField(prop, mode))}
            </div>
          )}

          <div style={{ marginBottom: 16 }}>
            <Title level={4} style={{ marginBottom: 8 }}>Truststore (optional)</Title>
            {modeFields('tls', mode).map((prop: any) => renderSchemaField(prop, mode))}
          </div>

          <div style={{ marginBottom: 16 }}>
            <Title level={4} style={{ marginBottom: 8 }}>Advanced overrides</Title>
            <Text type="secondary">Add extra Helm overrides (helm path → value) shared across charts.</Text>
            <div>
              <Text type="secondary">
                New schema fields still require a backend rebuild unless you add them here as overrides.
              </Text>
            </div>
            <Form.List name="extraPropertiesList">
              {(fields, { add, remove }) => (
                <>
                  {fields.map((field) => (
                    <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                      <Form.Item
                        {...field}
                        name={[field.name, 'key']}
                        fieldKey={[field.fieldKey, 'key']}
                        rules={[{ required: true, message: 'Path is required' }]}
                      >
                        <Tooltip title="Helm path for override">
                          <Input placeholder="helm path e.g. global.security.auth.ldap.custom" />
                        </Tooltip>
                      </Form.Item>
                      <Form.Item
                        {...field}
                        name={[field.name, 'value']}
                        fieldKey={[field.fieldKey, 'value']}
                      >
                        <Input placeholder="value" />
                      </Form.Item>
                      <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
                    </Space>
                  ))}
                  <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()} block>
                    Add override
                  </Button>
                </>
              )}
            </Form.List>
          </div>
        </Form>
        <Modal
          title={usageModalTitle}
          open={usageModalVisible}
          onCancel={closeUsageModal}
          footer={[
            <Button key="close" onClick={closeUsageModal}>
              Close
            </Button>
          ]}
          destroyOnClose
        >
          <Alert
            type="warning"
            showIcon
            message={`The profile "${usageProfileLabel || 'selected'}" is still referenced.`}
            description="Please reassign those releases before removing the profile."
            style={{ marginBottom: 16 }}
          />
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {usageReleases.map((release) => (
              <li key={release}>{release}</li>
            ))}
          </ul>
        </Modal>
    </div>
  );
};

export default GlobalSecurityPage;
