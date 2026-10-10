import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets, parseLabels, formatLabels } from '../bindings';
import { monitoringFieldsFromDiscovery, parseDiscoveryChoice } from '../discovery';

/**
 * AMBARI-726 — a Prometheus found by discovery (any namespace, any release name) drives the Trino wiring: its
 * address replaces the one built from namespace + release, and its ServiceMonitor selector labels end up on the
 * ServiceMonitors. Loads the REAL service.json so the test fails if the wiring drifts.
 */
const svc = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'), 'utf8')
);
const labelBinding = svc.bindings.filter((b: any) => b.name === 'service-monitor-label');

const form = (monitoring: any) => ({
  releaseName: 'trino',
  nameOverride: 'trino',
  namespace: 'trino',
  monitoring,
});

describe('label strings', () => {
  it('parse "k=v, k2=v2" and format back', () => {
    expect(parseLabels('release=obs, team = data\nprometheus=main')).toEqual({ release: 'obs', team: 'data', prometheus: 'main' });
    expect(parseLabels('')).toEqual({});
    expect(parseLabels('novalue, =x')).toEqual({});
    expect(formatLabels({ release: 'obs', team: 'data' })).toBe('release=obs, team=data');
    expect(formatLabels(undefined)).toBe('');
  });
});

describe('Trino monitoring wiring', () => {
  it('without discovery the address is built from namespace and release', () => {
    const ctx = buildVarContext(svc.variables, form({ namespace: 'monitoring', release: 'kube-prometheus-stack' }), {}, {}, 'kubernetes');
    expect(ctx.prometheus.serverAddress).toBe('http://kube-prometheus-stack-prometheus.monitoring.svc:9090');
  });

  it('a discovered address wins over the built one', () => {
    const ctx = buildVarContext(svc.variables,
      form({ namespace: 'observability', release: 'obs', url: 'http://prometheus-operated.observability.svc:9090' }),
      {}, {}, 'kubernetes');
    expect(ctx.prometheus.serverAddress).toBe('http://prometheus-operated.observability.svc:9090');
  });

  it('the discovered selector labels are put on the ServiceMonitors next to the release label', () => {
    const f = form({ namespace: 'observability', release: 'obs', serviceMonitorLabels: 'release=obs, team=data' });
    const values: any = {};
    applyBindingTargets(values, labelBinding, {}, f, 'trino', buildVarContext(svc.variables, f, {}, {}, 'kubernetes'));
    expect(values.serviceMonitor.labels).toEqual({ release: 'obs', team: 'data' });
  });

  it('no labels given: only the release label, as before', () => {
    const f = form({ namespace: 'monitoring', release: 'kube-prometheus-stack', serviceMonitorLabels: '' });
    const values: any = {};
    applyBindingTargets(values, labelBinding, {}, f, 'trino', buildVarContext(svc.variables, f, {}, {}, 'kubernetes'));
    expect(values.serviceMonitor.labels).toEqual({ release: 'kube-prometheus-stack' });
  });
});

describe('discovery picker', () => {
  const found = {
    namespace: 'observability', release: 'obs',
    url: 'https://prometheus.example.com', queryUrl: 'http://obs-kube-prometheus-prometheus.observability.svc:9090',
    serviceMonitorLabels: { release: 'obs' },
  };

  it('accepts the option value or the whole option', () => {
    expect(parseDiscoveryChoice(JSON.stringify(found))).toEqual(found);
    expect(parseDiscoveryChoice({ label: 'obs (observability)', value: JSON.stringify(found) })).toEqual(found);
    expect(parseDiscoveryChoice('not json')).toBeUndefined();
    expect(parseDiscoveryChoice(undefined)).toBeUndefined();
  });

  it('fills the in-cluster address, never the external one, and keeps the other monitoring fields', () => {
    expect(monitoringFieldsFromDiscovery(found, { interval: '30s' })).toEqual({
      interval: '30s', namespace: 'observability', release: 'obs',
      url: 'http://obs-kube-prometheus-prometheus.observability.svc:9090', serviceMonitorLabels: 'release=obs',
    });
    expect(monitoringFieldsFromDiscovery({ ...found, queryUrl: undefined }).url).toBe('');
  });

  it('keeps the address in the deployed values, so an upgrade still queries the same Prometheus', () => {
    const fields = svc.form.flatMap((g: any) => g.fields || []);
    const url = fields.find((f: any) => f.name === 'monitoring.url');
    expect(url).toBeDefined();
    expect(url.excludeFromValues).toBeFalsy();
  });
});
