import React, { useEffect, useState } from 'react';
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

  const mode = Form.useWatch('mode', form);

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
              <Option value="none">None</Option>
              <Option value="ldap">LDAP</Option>
              <Option value="ad">Active Directory</Option>
              <Option value="oidc">OIDC</Option>
            </Select>
          </Form.Item>

          {(mode === 'ldap' || mode === 'ad') && (
            <div style={{ marginBottom: 16 }}>
              <Title level={4} style={{ marginBottom: 8 }}>{mode === 'ad' ? 'Active Directory' : 'LDAP'}</Title>
              <Form.Item
                label="URL"
                name={['ldap', mode === 'ad' ? 'adUrl' : 'url']}
                rules={[{ required: true }]}
                tooltip={helmTitle(mode === 'ad' ? 'ldap.adUrl' : 'ldap.url', mode)}
                extra={helmMeta(mode === 'ad' ? 'ldap.adUrl' : 'ldap.url', mode)}
              >
                <Input placeholder="ldap://host:389" title={helmTitle(mode === 'ad' ? 'ldap.adUrl' : 'ldap.url', mode)} />
              </Form.Item>
              <Form.Item
                label="Bind DN"
                name={['ldap', mode === 'ad' ? 'adBindDn' : 'bindDn']}
                tooltip={helmTitle(mode === 'ad' ? 'ldap.adBindDn' : 'ldap.bindDn', mode)}
                extra={helmMeta(mode === 'ad' ? 'ldap.adBindDn' : 'ldap.bindDn', mode)}
              >
                <Input title={helmTitle(mode === 'ad' ? 'ldap.adBindDn' : 'ldap.bindDn', mode)} />
              </Form.Item>
              <Form.Item
                label="Bind Password"
                name={['ldap', mode === 'ad' ? 'adBindPassword' : 'bindPassword']}
                tooltip={helmTitle(mode === 'ad' ? 'ldap.adBindPassword' : 'ldap.bindPassword', mode)}
                extra={helmMeta(mode === 'ad' ? 'ldap.adBindPassword' : 'ldap.bindPassword', mode)}
              >
                <Input.Password title={helmTitle(mode === 'ad' ? 'ldap.adBindPassword' : 'ldap.bindPassword', mode)} />
              </Form.Item>
              <Form.Item
                label="User DN Template"
                name={['ldap', 'userDnTemplate']}
                tooltip={helmTitle('ldap.userDnTemplate', mode)}
                extra={helmMeta('ldap.userDnTemplate', mode)}
              >
                <Input
                  placeholder="uid={0},ou=users,dc=example,dc=com"
                  title={helmTitle('ldap.userDnTemplate', mode)}
                />
              </Form.Item>
              <Form.Item
                label="Base DN"
                name={['ldap', mode === 'ad' ? 'adBaseDn' : 'baseDn']}
                tooltip={helmTitle(mode === 'ad' ? 'ldap.adBaseDn' : 'ldap.baseDn', mode)}
                extra={helmMeta(mode === 'ad' ? 'ldap.adBaseDn' : 'ldap.baseDn', mode)}
              >
                <Input title={helmTitle(mode === 'ad' ? 'ldap.adBaseDn' : 'ldap.baseDn', mode)} />
              </Form.Item>
              <Form.Item
                label="Group Search Base"
                name={['ldap', 'groupSearchBase']}
                tooltip={helmTitle('ldap.groupSearchBase', mode)}
                extra={helmMeta('ldap.groupSearchBase', mode)}
              >
                <Input title={helmTitle('ldap.groupSearchBase', mode)} />
              </Form.Item>
              <Form.Item
                label="Group Search Filter"
                name={['ldap', 'groupSearchFilter']}
                tooltip={helmTitle('ldap.groupSearchFilter', mode)}
                extra={helmMeta('ldap.groupSearchFilter', mode)}
              >
                <Input
                  placeholder="(member=uid={0},ou=users,...)"
                  title={helmTitle('ldap.groupSearchFilter', mode)}
                />
              </Form.Item>
              <Form.Item
                label="Referral"
                name={['ldap', 'referral']}
                tooltip={helmTitle('ldap.referral', mode)}
                extra={helmMeta('ldap.referral', mode)}
              >
                <Input placeholder="follow|ignore" title={helmTitle('ldap.referral', mode)} />
              </Form.Item>
              <Form.Item
                label="StartTLS"
                name={['ldap', 'startTls']}
                valuePropName="checked"
                tooltip={helmTitle('ldap.startTls', mode)}
                extra={helmMeta('ldap.startTls', mode)}
              >
                <Switch />
              </Form.Item>
              {mode === 'ad' && (
                <>
                  <Form.Item
                    label="AD Domain"
                    name={['ldap', 'adDomain']}
                    tooltip={helmTitle('ldap.adDomain', mode)}
                    extra={helmMeta('ldap.adDomain', mode)}
                  >
                    <Input title={helmTitle('ldap.adDomain', mode)} />
                  </Form.Item>
                  <Form.Item
                    label="AD User Search Filter"
                    name={['ldap', 'adUserSearchFilter']}
                    tooltip={helmTitle('ldap.adUserSearchFilter', mode)}
                    extra={helmMeta('ldap.adUserSearchFilter', mode)}
                  >
                    <Input
                      placeholder="(sAMAccountName={0})"
                      title={helmTitle('ldap.adUserSearchFilter', mode)}
                    />
                  </Form.Item>
                </>
              )}
            </div>
          )}

          {mode === 'oidc' && (
            <div style={{ marginBottom: 16 }}>
              <Title level={4} style={{ marginBottom: 8 }}>OIDC</Title>
              <Form.Item
                label="Issuer URL"
                name={['oidc', 'issuerUrl']}
                rules={[{ required: true }]}
                tooltip={helmTitle('oidc.issuerUrl', mode)}
                extra={helmMeta('oidc.issuerUrl', mode)}
              >
                <Input placeholder="https://idp.example.com" title={helmTitle('oidc.issuerUrl', mode)} />
              </Form.Item>
              <Form.Item
                label="Client ID"
                name={['oidc', 'clientId']}
                rules={[{ required: true }]}
                tooltip={helmTitle('oidc.clientId', mode)}
                extra={helmMeta('oidc.clientId', mode)}
              >
                <Input title={helmTitle('oidc.clientId', mode)} />
              </Form.Item>
              <Form.Item
                label="Client Secret"
                name={['oidc', 'clientSecret']}
                rules={[{ required: true }]}
                tooltip={helmTitle('oidc.clientSecret', mode)}
                extra={helmMeta('oidc.clientSecret', mode)}
              >
                <Input.Password title={helmTitle('oidc.clientSecret', mode)} />
              </Form.Item>
              <Form.Item
                label="Scopes"
                name={['oidc', 'scopes']}
                tooltip={helmTitle('oidc.scopes', mode)}
                extra={helmMeta('oidc.scopes', mode)}
              >
                <Input placeholder="openid profile email" title={helmTitle('oidc.scopes', mode)} />
              </Form.Item>
              <Form.Item
                label="Redirect URI"
                name={['oidc', 'redirectUri']}
                tooltip={helmTitle('oidc.redirectUri', mode)}
                extra={helmMeta('oidc.redirectUri', mode)}
              >
                <Input title={helmTitle('oidc.redirectUri', mode)} />
              </Form.Item>
              <Form.Item
                label="User Claim"
                name={['oidc', 'userClaim']}
                tooltip={helmTitle('oidc.userClaim', mode)}
                extra={helmMeta('oidc.userClaim', mode)}
              >
                <Input placeholder="preferred_username" title={helmTitle('oidc.userClaim', mode)} />
              </Form.Item>
              <Form.Item
                label="Groups Claim"
                name={['oidc', 'groupsClaim']}
                tooltip={helmTitle('oidc.groupsClaim', mode)}
                extra={helmMeta('oidc.groupsClaim', mode)}
              >
                <Input placeholder="groups" title={helmTitle('oidc.groupsClaim', mode)} />
              </Form.Item>
              <Form.Item
                label="Skip TLS Verify"
                name={['oidc', 'skipTlsVerify']}
                valuePropName="checked"
                tooltip={helmTitle('oidc.skipTlsVerify', mode)}
                extra={helmMeta('oidc.skipTlsVerify', mode)}
              >
                <Switch />
              </Form.Item>
              <Form.Item
                label="CA Secret (optional)"
                name={['oidc', 'caSecret']}
                tooltip={helmTitle('oidc.caSecret', mode)}
                extra={helmMeta('oidc.caSecret', mode)}
              >
                <Input placeholder="secret with ca.crt" title={helmTitle('oidc.caSecret', mode)} />
              </Form.Item>
            </div>
          )}

          <div style={{ marginBottom: 16 }}>
            <Title level={4} style={{ marginBottom: 8 }}>Truststore (optional)</Title>
            <Form.Item
              label="Truststore Secret"
              name={['tls', 'truststoreSecret']}
              tooltip={helmTitle('tls.truststoreSecret', mode)}
              extra={helmMeta('tls.truststoreSecret', mode)}
            >
              <Input placeholder="secret name containing truststore" title={helmTitle('tls.truststoreSecret', mode)} />
            </Form.Item>
            <Form.Item
              label="Truststore Key"
              name={['tls', 'truststoreKey']}
              tooltip={helmTitle('tls.truststoreKey', mode)}
              extra={helmMeta('tls.truststoreKey', mode)}
            >
              <Input placeholder="truststore.jks or ca.crt" title={helmTitle('tls.truststoreKey', mode)} />
            </Form.Item>
            <Form.Item
              label="Truststore Password Key"
              name={['tls', 'truststorePasswordKey']}
              tooltip={helmTitle('tls.truststorePasswordKey', mode)}
              extra={helmMeta('tls.truststorePasswordKey', mode)}
            >
              <Input placeholder="truststore.password" title={helmTitle('tls.truststorePasswordKey', mode)} />
            </Form.Item>
          </div>

          <div style={{ marginBottom: 16 }}>
            <Title level={4} style={{ marginBottom: 8 }}>Advanced overrides</Title>
            <Text type="secondary">Add extra Helm overrides (helm path → value) shared across charts.</Text>
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
