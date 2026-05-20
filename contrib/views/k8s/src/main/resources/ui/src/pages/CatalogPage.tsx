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
import { useNavigate } from 'react-router-dom';
import { Card, Col, Empty, Input, Row, Spin, Tag, Tooltip, Typography, Space, Alert } from 'antd';
import {
  AppstoreOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  DatabaseOutlined,
  ShopOutlined,
} from '@ant-design/icons';
import { getAvailableServices, getClusterCapabilities, getCatalogAppVersions, type ClusterCapabilities } from '../api/client';
import { SERVICE_ICONS } from '../assets/services';

const { Title, Paragraph } = Typography;

/** Compute the implied capability requirements of a service by scanning its
 *  fields/bindings. A service "needs" cert-manager if any tlsMode option is
 *  tagged with capability "certManager"; same for external-secrets. OIDC
 *  is implied by a non-empty `oidc` array in the service.json. Kept loose
 *  on purpose — the wizard does authoritative gating, this is just badges. */
const detectCapabilities = (svc: any): { oidc: boolean; certManager: boolean; externalSecrets: boolean; chartManagedTls: boolean } => {
  const r = { oidc: false, certManager: false, externalSecrets: false, chartManagedTls: false };
  if (Array.isArray(svc?.oidc) && svc.oidc.length > 0) r.oidc = true;
  const sc = svc?.securityCoupling;
  if (sc && sc.tlsProvisioning === 'chart-managed') r.chartManagedTls = true;
  const walkOptions = (fields: any[]): void => {
    if (!Array.isArray(fields)) return;
    for (const f of fields) {
      if (Array.isArray(f.fields)) walkOptions(f.fields);
      if (Array.isArray(f.options)) {
        for (const o of f.options) {
          if (o.capability === 'certManager') r.certManager = true;
          if (o.capability === 'externalSecrets') r.externalSecrets = true;
        }
      }
    }
  };
  walkOptions(svc?.form);
  return r;
};

const CapabilityBadge: React.FC<{
  label: string;
  needed: boolean;
  available: boolean;
  helpAvailable: string;
  helpMissing: string;
  icon: React.ReactNode;
}> = ({ label, needed, available, helpAvailable, helpMissing, icon }) => {
  if (!needed) return null;
  return (
    <Tooltip title={available ? helpAvailable : helpMissing}>
      <Tag color={available ? 'green' : 'volcano'} icon={icon} style={{ margin: 0 }}>{label}</Tag>
    </Tooltip>
  );
};

const CatalogPage: React.FC = () => {
  const navigate = useNavigate();
  const [services, setServices] = React.useState<Record<string, any>>({});
  const [capabilities, setCapabilities] = React.useState<ClusterCapabilities | undefined>(undefined);
  const [appVersions, setAppVersions] = React.useState<Record<string, string>>({});
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);
  const [filter, setFilter] = React.useState('');

  React.useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      getAvailableServices().catch(e => { throw e; }),
      getClusterCapabilities().catch(() => undefined),
    ]).then(([svcs, caps]) => {
      if (cancelled) return;
      setServices(svcs || {});
      setCapabilities(caps);
    }).catch(e => {
      if (!cancelled) setError(e?.message || String(e));
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    // appVersion resolution is slow (helm show chart per service); load it
    // out-of-band so the catalog renders immediately and component versions
    // fade in once the backend cache is warm.
    getCatalogAppVersions().then(av => { if (!cancelled) setAppVersions(av || {}); }).catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const entries = React.useMemo(() => {
    const list = Object.entries(services).map(([name, svc]) => ({ name, svc }));
    const needle = filter.trim().toLowerCase();
    if (!needle) return list.sort((a, b) => (a.svc?.label || a.name).localeCompare(b.svc?.label || b.name));
    return list.filter(({ name, svc }) => {
      const hay = `${name} ${svc?.label || ''} ${svc?.description || ''} ${svc?.chart || ''}`.toLowerCase();
      return hay.includes(needle);
    });
  }, [services, filter]);

  return (
    <>
      <div style={{ marginBottom: 16 }}>
        <Title level={3} style={{ marginTop: 0, marginBottom: 4 }}>
          <AppstoreOutlined style={{ marginRight: 8 }} />Service Catalog
        </Title>
        <Paragraph type="secondary" style={{ marginBottom: 12 }}>
          Pick a service to deploy. Each card shows what cluster capabilities the install needs;
          missing ones are flagged in orange so you can install the underlying operator first.
        </Paragraph>
        <Input.Search
          allowClear
          placeholder="Filter by name, chart or description"
          onChange={e => setFilter(e.target.value)}
          style={{ maxWidth: 360 }}
        />
      </div>

      {error && (
        <Alert
          type="error"
          showIcon
          message="Failed to load catalog"
          description={error}
          style={{ marginBottom: 16 }}
        />
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>
      ) : entries.length === 0 ? (
        <Empty description={filter ? 'No services match the filter' : 'No services available'} />
      ) : (
        <Row gutter={[16, 16]} align="stretch">
          {entries.map(({ name, svc }) => {
            const needs = detectCapabilities(svc);
            const certManagerOk = !!capabilities?.certManager?.installed;
            const externalSecretsOk = !!capabilities?.externalSecrets?.installed;
            const iconUrl = SERVICE_ICONS[name.toUpperCase()];
            const appVersion = appVersions[name];
            return (
              <Col xs={24} sm={12} md={12} lg={8} xl={6} key={name} style={{ display: 'flex' }}>
                <Card
                  size="small"
                  hoverable
                  onClick={() => navigate(`/services/${name}`)}
                  className="kdps-catalog-card"
                  style={{ width: '100%' }}
                  styles={{ body: { display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 220 } }}
                  title={
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                      {iconUrl ? (
                        <img src={iconUrl} alt="" aria-hidden="true"
                             style={{ width: 24, height: 24, flexShrink: 0, objectFit: 'contain' }} />
                      ) : (
                        <ShopOutlined style={{ fontSize: 18, color: '#6b7280' }} />
                      )}
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {svc?.label || name}
                      </span>
                    </div>
                  }
                >
                  <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 12, flex: '0 0 auto', minHeight: 56 }}>
                    {svc?.description || <i>No description.</i>}
                  </Paragraph>
                  {/* Version row: pinned chart version + (when resolved) component appVersion.
                      Putting them on their own row prevents them from wrapping into the
                      capability tags below. */}
                  <div style={{ marginTop: 'auto', display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
                    {svc?.version && (
                      <Tooltip title="Helm chart version (the packaging)">
                        <Tag color="blue" style={{ margin: 0 }}>chart {svc.version}</Tag>
                      </Tooltip>
                    )}
                    {appVersion && (
                      <Tooltip title="Component version (what the chart actually deploys, from chart's appVersion)">
                        <Tag color="purple" style={{ margin: 0 }}>app {appVersion}</Tag>
                      </Tooltip>
                    )}
                    {svc?.chart && (
                      <Tag style={{ margin: 0 }}>{svc.chart}</Tag>
                    )}
                  </div>
                  {/* Capability badges. Constrained to wrap inside the card via flexWrap.
                      Without explicit wrapper a Space wrap on tags can overflow the card
                      when a row contains many badges. */}
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    <CapabilityBadge
                      label="OIDC"
                      needed={needs.oidc}
                      available={true}
                      helpAvailable="Wires Keycloak OIDC at install"
                      helpMissing="No OIDC profile detected"
                      icon={<LockOutlined />}
                    />
                    <CapabilityBadge
                      label="cert-manager"
                      needed={needs.certManager}
                      available={certManagerOk}
                      helpAvailable="cert-manager is installed; this TLS mode works"
                      helpMissing="cert-manager CRDs missing — install it to use this TLS mode"
                      icon={<SafetyCertificateOutlined />}
                    />
                    <CapabilityBadge
                      label="external-secrets"
                      needed={needs.externalSecrets}
                      available={externalSecretsOk}
                      helpAvailable="external-secrets is installed; this TLS mode works"
                      helpMissing="external-secrets CRDs missing — install ESO to use this TLS mode"
                      icon={<DatabaseOutlined />}
                    />
                    {needs.chartManagedTls && (
                      <Tooltip title="Chart manages its own Ingress and TLS — view does not write spec.tls[].">
                        <Tag style={{ margin: 0 }}>chart-managed TLS</Tag>
                      </Tooltip>
                    )}
                  </div>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </>
  );
};

export default CatalogPage;
