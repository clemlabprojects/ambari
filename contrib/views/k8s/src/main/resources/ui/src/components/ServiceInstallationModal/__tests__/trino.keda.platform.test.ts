import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * AMBARI-538 — platform-conditional Trino KEDA autoscaling.
 *
 * The binding engine now seeds `isOpenShift` / `isVanillaK8s` from the detected platform.
 * Trino's service.json uses them to branch its KEDA triggers:
 *   - vanilla Kubernetes → scrape the kube-prometheus-stack Prometheus directly, no auth
 *   - OpenShift          → scrape the thanos-querier tenancy endpoint with bearer auth,
 *                          plus a TriggerAuthentication is enabled on the chart.
 *
 * Loads the REAL service.json so the test fails if the wiring drifts. Also asserts the
 * fail-safe: when the platform hasn't been detected yet, the vanilla path is used (never
 * "no triggers at all", and never a fail-open OpenShift path).
 */
const svc = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'),
    'utf8'
  )
);
const variables = svc.variables;
const kedaBindings = svc.bindings.filter((b: any) =>
  ['keda-triggers', 'keda-triggers-openshift', 'keda-trigger-auth-openshift'].includes(b.name)
);

// A realistic form so trino.fullname / prometheus.serverAddress / thresholds all resolve.
const FORM = {
  releaseName: 'trino',
  nameOverride: 'trino',
  namespace: 'trino',
  monitoring: { namespace: 'monitoring', release: 'kube-prometheus-stack' },
  requiredWorkers: { window: '5m', threshold: '1' },
  queuedQueries: { threshold: '5', activation: '1' },
  runningQueries: { threshold: '5', activation: '1' },
  heap: { threshold: '80', activation: '50' },
};

const build = (platform?: string) => {
  const varCtx = buildVarContext(variables, FORM, {}, {}, platform);
  const values: any = { server: { keda: { enabled: true, triggers: [] } } };
  applyBindingTargets(values, kedaBindings, {}, FORM, 'trino', varCtx);
  return values.server.keda;
};

describe('Trino KEDA — platform-conditional triggers', () => {
  it('seeds isOpenShift / isVanillaK8s gate variables', () => {
    expect(buildVarContext([], {}, {}, {}, 'openshift')).toMatchObject({ isOpenShift: 'true', isVanillaK8s: '' });
    expect(buildVarContext([], {}, {}, {}, 'kubernetes')).toMatchObject({ isOpenShift: '', isVanillaK8s: 'true' });
    // Fail-safe: unknown platform (probe not resolved) defaults to the vanilla path.
    expect(buildVarContext([], {}, {}, {}, undefined)).toMatchObject({ isOpenShift: '', isVanillaK8s: 'true' });
  });

  it('vanilla Kubernetes → 4 Prometheus triggers, no auth, no TriggerAuthentication', () => {
    const keda = build('kubernetes');
    expect(keda.triggers).toHaveLength(4);
    for (const t of keda.triggers) {
      expect(t.metadata.serverAddress).toBe('http://kube-prometheus-stack-prometheus.monitoring.svc:9090');
      expect(t.metadata.authModes).toBeUndefined();
      expect(t.authenticationRef).toBeUndefined();
    }
    // TriggerAuthentication stays off on vanilla.
    expect(keda.triggerAuthentication?.enabled).toBeUndefined();
  });

  it('OpenShift → 4 Thanos-tenancy triggers with bearer auth + a TriggerAuthentication', () => {
    const keda = build('openshift');
    expect(keda.triggers).toHaveLength(4);
    for (const t of keda.triggers) {
      expect(t.metadata.serverAddress).toBe('https://thanos-querier.openshift-monitoring.svc:9092');
      expect(t.metadata.authModes).toBe('bearer');
      expect(t.authenticationRef).toEqual({ name: 'trino-trino-keda-auth' });
    }
    expect(keda.triggerAuthentication.enabled).toBe(true);
    expect(keda.triggerAuthentication.name).toBe('trino-trino-keda-auth');
    expect(keda.triggerAuthentication.secretName).toBe('trino-trino-thanos-token');
  });

  it('unknown platform → vanilla triggers (fail-safe, never empty)', () => {
    const keda = build(undefined);
    expect(keda.triggers).toHaveLength(4);
    expect(keda.triggers[0].metadata.serverAddress).toContain('kube-prometheus-stack-prometheus');
    expect(keda.triggerAuthentication?.enabled).toBeUndefined();
  });
});
