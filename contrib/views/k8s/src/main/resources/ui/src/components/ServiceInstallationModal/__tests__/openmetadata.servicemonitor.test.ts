import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues } from '../chartValues';

/**
 * The OpenMetadata ServiceMonitor, as the wizard sends it. On OpenShift it must not name the kube-prometheus
 * "monitoring" namespace (it does not exist there, and a project-scoped account may not write to it) nor carry the
 * kube-prometheus 'release' label; the chart places it in the release namespace. On plain Kubernetes the 'release'
 * label is kept so the kube-prometheus-stack still scrapes it. Starts from the defaults of the REAL service.json, as
 * the wizard does, so the test fails if the wiring drifts.
 *
 * The values must also render with the charts before 1.13.34, which existing releases run and keep on a redeploy:
 * those read `monitoring.namespace` and `monitoring.release` with no default, so the (now empty) `monitoring` block has
 * to stay in the values, and with no namespace in it their template falls back to the release namespace.
 */
const svc = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/OPENMETADATA/service.json'), 'utf8')
);

const wizardStart = (): any => {
  const values: any = { releaseName: 'openmetadata', namespace: 'project-a', deploymentMode: 'DIRECT_HELM' };
  applyFormDefaults(svc.form, values);
  return values;
};

const build = (platform?: string, formValues: any = wizardStart()) => buildChartValues({
  form: svc.form, bindings: svc.bindings, variables: svc.variables, formValues, platform, chartVersion: svc.version,
});

describe('OpenMetadata ServiceMonitor', () => {
  it('starts from the kube-prometheus defaults of the Monitoring group', () => {
    const start = wizardStart();
    expect(start.monitoring).toMatchObject({ namespace: 'monitoring', release: 'kube-prometheus-stack' });
    expect(start.serviceMonitor).toEqual({ enabled: true });
  });

  it('OpenShift: enabled, with no namespace, no release label and nothing left of the monitoring block', () => {
    const values = build('openshift');
    expect(values.serviceMonitor).toEqual({ enabled: true });
    expect(values.monitoring).toEqual({});
  });

  it('OpenShift: labels found by an earlier Prometheus discovery are not sent either', () => {
    const start = wizardStart();
    start.monitoring = { namespace: 'observability', release: 'obs', serviceMonitorLabels: 'release=obs, team=data' };
    expect(build('openshift', start).serviceMonitor).toEqual({ enabled: true });
  });

  it('plain Kubernetes: the kube-prometheus-stack release label, and no namespace', () => {
    const values = build('kubernetes');
    expect(values.serviceMonitor).toEqual({ enabled: true, labels: { release: 'kube-prometheus-stack' } });
    expect(values.monitoring).toEqual({});
  });

  it('plain Kubernetes: the release and labels found by discovery', () => {
    const start = wizardStart();
    start.monitoring = { namespace: 'observability', release: 'obs', serviceMonitorLabels: 'release=obs, team=data' };
    expect(build('kubernetes', start).serviceMonitor).toEqual({
      enabled: true, labels: { release: 'obs', team: 'data' },
    });
  });

  it('plain Kubernetes: "Expose ServiceMonitor" turned off is sent as off', () => {
    const start = wizardStart();
    start.serviceMonitor.enabled = false;
    expect(build('kubernetes', start).serviceMonitor.enabled).toBe(false);
  });

  it('platform not detected yet: the plain Kubernetes values, still without a namespace', () => {
    const values = build(undefined);
    expect(values.serviceMonitor).toEqual({ enabled: true, labels: { release: 'kube-prometheus-stack' } });
  });

  it('no binding writes a ServiceMonitor namespace', () => {
    const targets = svc.bindings.flatMap((b: any) => b.targets || []).map((t: any) => t.path);
    expect(targets).not.toContain('serviceMonitor.namespace');
  });

  it('installs the chart that always places the ServiceMonitor in the release namespace', () => {
    // 1.13.34 or a later chart, which keeps that behaviour.
    const pinned = /^(\d+)\.(\d+)\.(\d+)$/.exec(svc.version);
    expect(pinned).not.toBeNull();
    const [major, minor, patch] = pinned!.slice(1).map(Number);
    expect([major, minor]).toEqual([1, 13]);
    expect(patch).toBeGreaterThanOrEqual(34);
  });

  it('still accepts the chart existing releases run, so they can be redeployed', () => {
    // A resync, security refresh or values edit keeps the deployed chart version, which the range is checked against.
    const floor = /^>=(\d+)\.(\d+)\.(\d+)$/.exec(svc.requiredChartVersion);
    expect(floor).not.toBeNull();
    const [major, minor, patch] = floor!.slice(1).map(Number);
    expect([major, minor]).toEqual([1, 13]);
    expect(patch).toBeLessThanOrEqual(33);
  });
});
