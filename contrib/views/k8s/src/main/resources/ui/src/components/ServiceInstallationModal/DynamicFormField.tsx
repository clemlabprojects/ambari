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
import { Card, Checkbox, Collapse, Form, Input, InputNumber, Select, Switch, Tag, Tooltip, Typography } from 'antd';
import type { FormField, ExternalAuthTargetFormField } from '../../types/ServiceTypes';
import { getClusterCapabilities, type ClusterCapabilities } from '../../api/client';
import ServiceSelect from './ServiceSelect';
import ExternalAuthTargetField from './ExternalAuthTargetField';
import { useIsContextLinked, useResolvedContextValue } from './ExternalAuthTargetsContext';
import { fieldCapabilityAvailable } from './capabilities';
import { ApiOutlined } from '@ant-design/icons';

const { Option } = Select;

// Module-level memoization: capability probe is the same for every form on the page,
// so a single fetch is enough — and we cache the Promise so concurrent first renders
// don't fan out into N network calls. Refresh on full reload (or when /cluster/capabilities
// is invalidated server-side, which already happens on a 60s TTL).
let capabilitiesPromise: Promise<ClusterCapabilities> | null = null;
const useCapabilities = (): ClusterCapabilities | undefined => {
  const [caps, setCaps] = React.useState<ClusterCapabilities | undefined>(undefined);
  React.useEffect(() => {
    if (!capabilitiesPromise) capabilitiesPromise = getClusterCapabilities();
    capabilitiesPromise.then(setCaps).catch(() => {
      // On error we leave caps undefined; the consumer should fail-open (show all
      // options). Reset the cached promise so a future render can retry.
      capabilitiesPromise = null;
    });
  }, []);
  return caps;
};

const DynamicFormField: React.FC<{ field: FormField; upgradeMode?: boolean }> = ({ field, upgradeMode }) => {
  const form = Form.useFormInstance();
  const rules = [{ required: (field as any).required, message: `Field '${field.label}' is required.` }];
  const isLocked = upgradeMode && (field.name === 'releaseName' || field.name === 'namespace');
  const disabledProp = (field as any).disabled || isLocked;
  const nameParts = field.name.replace(/\\\./g, '__DOT__').split('.').map((p: string) => p.replace(/__DOT__/g, '.'));
  // Cluster capabilities (cert-manager / external-secrets / OpenShift) drive the
  // adaptive filtering of select options below. We call the hook unconditionally
  // so React's rule-of-hooks holds regardless of field type.
  const caps = useCapabilities();
  // When this field is a `requiresContext.when` trigger (e.g. atlasFederation.enabled), wrap it
  // in a subtle accent + cue so the operator sees it depends on the selected Platform Context.
  const linked = useIsContextLinked(field.name);
  // For a `context-resolved` field: the value KDPS derives for this field's `contextField`
  // (e.g. "hive.hs2HostPort") on the selected Platform Context, plus a local toggle that
  // lets the operator opt into overriding it. Hooks run unconditionally (rule-of-hooks).
  const ctxResolved = useResolvedContextValue((field as any).contextField);
  const [overrideOn, setOverrideOn] = React.useState(false);
  const wrapLinked = (node: React.ReactNode): React.ReactNode => linked ? (
    <div style={{ borderLeft: '3px solid var(--linked)', background: 'rgba(var(--linked-rgb), 0.10)', borderRadius: 4, padding: '8px 12px', marginBottom: 16 }}>
      {node}
      <Typography.Text style={{ color: 'var(--linked)', fontSize: 12 }}>
        <ApiOutlined /> Depends on the selected <strong>Platform context</strong> (right panel) — endpoints &amp; credentials are resolved from there.
      </Typography.Text>
    </div>
  ) : node;
  const capabilityAvailable = (cap?: string): boolean => fieldCapabilityAvailable(cap, caps);

  const condition = (field as any).condition;
  // Always call useWatch in the same order; pass undefined when no condition.
  const watchedValue = Form.useWatch(condition ? condition.field.split('.') : undefined, form);
  // condition.value may be a scalar (legacy) or an array of allowed values
  // (e.g. `"value": ["signedByAmbariCA", "signedByCompanyCA"]` — show this
  // field when the watched value matches ANY entry). Without the array branch,
  // such fields would never render because `array === scalar` is always false.
  //
  // condition.operator: optional, defaults to equality. "non-empty" lets a field
  // gate-show only when another field has a non-blank string value — useful for
  // external-auth-target groups that should only appear when the corresponding
  // URL override has been filled in.
  const isVisible = !condition
      ? true
      : condition.operator === 'non-empty'
          ? watchedValue != null && String(watchedValue).trim().length > 0
          : Array.isArray(condition.value)
              ? condition.value.includes(watchedValue)
              : watchedValue === condition.value;
  if (!isVisible) return null;
  // Field-level capability gate (mirrors the option-level `capability` on selects):
  // hide the whole field when its required cluster capability/platform is absent —
  // e.g. a field with `"capability": "openshift"` only renders on OpenShift clusters.
  if (!capabilityAvailable((field as any).capability)) return null;

  switch (field.type) {
    case 'group': {
      // Groups marked `collapsed: true` render as an antd Collapse panel (closed by default)
      // instead of a Card. Used for "Advanced — usually leave blank" sections like
      // `ingressTuning` so they don't clutter the wizard but stay accessible.
      const isCollapsible = Boolean((field as any).collapsed);
      const children = (field as any).fields.map((subField: FormField) => (
        <DynamicFormField key={subField.name} field={subField} />
      ));
      if (isCollapsible) {
        return (
          <Collapse
            size="small"
            style={{ marginBottom: 16 }}
            items={[{
              key: field.name,
              label: (
                <span>
                  {field.label}
                  {(field as any).help && (
                    <Typography.Text type="secondary" style={{ marginLeft: 8, fontWeight: 'normal' }}>
                      — {(field as any).help.length > 80
                          ? (field as any).help.slice(0, 78) + '…'
                          : (field as any).help}
                    </Typography.Text>
                  )}
                </span>
              ),
              children,
            }]}
          />
        );
      }
      return (
        <Card title={field.label} size="small" style={{ marginBottom: 16 }}>
          {children}
        </Card>
      );
    }
    case 'external-auth-target':
      return <ExternalAuthTargetField field={field as ExternalAuthTargetFormField} />;
    case 'service-select':
    case 'hadoop-discovery': {
      const f = field as any;
      if (f.targetHost || f.targetPort) {
        return (
          <ServiceSelect
            field={f}
            onValueSelect={(svcOrVal) => {
              const host = typeof svcOrVal === 'object' ? svcOrVal?.value : svcOrVal;
              const port = typeof svcOrVal === 'object' ? svcOrVal?.port : undefined;
              if (f.targetHost && host != null) form.setFieldValue(f.targetHost.split('.'), host);
              if (f.targetPort && port != null) form.setFieldValue(f.targetPort.split('.'), Number(port));
            }}
          />
        );
      }
      return <ServiceSelect field={f} />;
    }
    case 'monitoring-discovery':
      return (
        <ServiceSelect
          field={field as any}
          onValueSelect={(val) => {
            try {
              const parsed = typeof val === 'string' ? JSON.parse(val) : val;
              if (parsed?.namespace || parsed?.release) {
                form.setFieldsValue({
                  monitoring: {
                    namespace: parsed.namespace,
                    release: parsed.release,
                  },
                });
              }
            } catch (e) {
              // ignore parse errors
            }
          }}
        />
      );
    case 'context-resolved': {
      const f = field as any;
      // No value resolved from the context. For a MANAGED context this means the capability
      // isn't present; for an EXTERNAL context (no backing Ambari) the operator supplies it
      // directly and the KDPS engine computes the connection (e.g. scheme) from raw props.
      if (!ctxResolved.resolved) {
        // EXTERNAL context + `externalFields` declared: render the raw HiveServer2 properties
        // (transport / TLS) the operator sets, and the KDPS engine derives the scheme from them —
        // the operator never hand-types 'hive+https'. The scheme field's own value stays blank so
        // the backend computes it (computeHiveSchemeFromTransport).
        const ext = f.externalFields as any[] | undefined;
        if (ctxResolved.kind === 'EXTERNAL' && Array.isArray(ext) && ext.length) {
          return wrapLinked(
            <div style={{ borderLeft: '3px solid #d46b08', background: 'rgba(212,107,8,0.04)', borderRadius: 4, padding: '8px 12px', marginBottom: 16 }}>
              <Typography.Text strong>{field.label}</Typography.Text>{' '}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                — external context: KDPS computes this from the properties below
              </Typography.Text>
              <div style={{ marginTop: 8 }}>
                {ext.map((sf: any) => <DynamicFormField key={sf.name} field={sf} />)}
              </div>
            </div>
          );
        }
        return wrapLinked(
          <Form.Item name={nameParts} label={field.label} rules={rules} help={f.help}
            extra={ctxResolved.kind === 'EXTERNAL'
              ? 'External context — provide this value; KDPS computes the connection from it.'
              : undefined}>
            <Input placeholder={f.placeholder} disabled={disabledProp} />
          </Form.Item>
        );
      }
      // MANAGED / resolved → render the KDPS-derived value read-only in "blue" with an
      // Override switch. Left off, the override stays blank and the engine uses the context
      // value (host:port / scheme / auth are all delivered by KDPS, not typed by the operator).
      return wrapLinked(
        <Form.Item label={field.label} help={f.help} style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <Tag color="blue" style={{ fontSize: 13, padding: '2px 10px', margin: 0 }}>
              <ApiOutlined /> {ctxResolved.value}
            </Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>computed by KDPS</Typography.Text>
            <span style={{ flex: 1 }} />
            <Switch size="small" checked={overrideOn}
              onChange={(on) => { setOverrideOn(on); if (!on) form.setFieldValue(nameParts, undefined); }} />
            <Typography.Text style={{ fontSize: 12 }}>Override</Typography.Text>
          </div>
          <Form.Item name={nameParts} noStyle hidden={!overrideOn}>
            <Input style={{ marginTop: 8 }} placeholder={ctxResolved.value} disabled={!overrideOn} />
          </Form.Item>
        </Form.Item>
      );
    }
    case 'k8s-discovery':
    case 'secret-discovery':
    case 'cluster-issuer-discovery':
    case 'secret-store-discovery': {
      const f = field as any;
      if (f.targetHost || f.targetPort) {
        return (
          <ServiceSelect
            field={f}
            onValueSelect={(svcOrVal) => {
              const host = typeof svcOrVal === 'object' ? svcOrVal?.value : svcOrVal;
              const port = typeof svcOrVal === 'object' ? svcOrVal?.port : undefined;
              if (f.targetHost && host != null) form.setFieldValue(f.targetHost.split('.'), host);
              if (f.targetPort && port != null) form.setFieldValue(f.targetPort.split('.'), Number(port));
            }}
          />
        );
      }
      return <ServiceSelect field={f} />;
    }
    case 'select':
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Select disabled={disabledProp}>
            {(field as any).options
              // Options can declare `capability: "certManager" | "externalSecrets"` so the
              // wizard hides modes whose backing operator isn't installed. Until the
              // capabilities probe returns we render everything (fail-open) so the form
              // doesn't briefly appear empty on first paint.
              .filter((opt: any) => capabilityAvailable(opt.capability))
              .map((opt: any) => (
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
      return wrapLinked(
        <Form.Item name={nameParts} valuePropName="checked" help={field.help} style={{ marginBottom: 8 }}>
          <Checkbox disabled={disabledProp}>{field.label}</Checkbox>
        </Form.Item>
      );
    case 'string':
    default:
      return (
        <Form.Item name={nameParts} label={field.label} rules={rules} help={field.help}>
          <Input
            disabled={disabledProp}
            onChange={(e) => {
              // For Hive override, also populate the base metastore field so bindings pick it up.
              if (field.name === 'ui_hive_metastore_uri_override') {
                form.setFieldsValue({ ui_hive_metastore_uri: e.target.value });
              }
            }}
          />
        </Form.Item>
      );
  }
};

export default DynamicFormField;
