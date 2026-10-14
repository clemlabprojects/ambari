import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues, hiddenPaths } from '../chartValues';

/**
 * What the wizard leaves out of the chart values on each platform, for the REAL service definitions: the settings of
 * the other platform are dropped, while a field the wizard fills in by itself (the ingress class it detects) and a
 * switch declared for both platforms are still sent.
 */
const servicesDir = path.join(__dirname, '../../../../../KDPS/services');
const services = fs.readdirSync(servicesDir).filter(d => fs.existsSync(path.join(servicesDir, d, 'service.json')));
const load = (name: string) => JSON.parse(fs.readFileSync(path.join(servicesDir, name, 'service.json'), 'utf8'));

const build = (svc: any, platform: string) => {
  const formValues: any = { releaseName: 'svc', namespace: 'project-a' };
  applyFormDefaults(svc.form, formValues);
  return buildChartValues({
    form: svc.form, bindings: svc.bindings, variables: svc.variables, formValues, platform, chartVersion: svc.version,
  });
};

describe('fields hidden on the platform, per service', () => {
  it.each(services)('%s: the ingress class is still sent on OpenShift', (name) => {
    const hidden = hiddenPaths(load(name).form, 'openshift');
    expect(hidden).not.toContain('ingress.ingressClassName');
    expect(hidden).not.toContain('ingress.className');
  });

  it('Trino: kube-prometheus settings on Kubernetes only; metrics switch and scrape interval on both', () => {
    const form = load('TRINO').form;
    expect(hiddenPaths(form, 'openshift')).toEqual(expect.arrayContaining([
      'monitoring.namespace', 'monitoring.release', 'monitoring.url',
    ]));
    for (const kept of ['serviceMonitor.enabled', 'serviceMonitor.interval']) {
      expect(hiddenPaths(form, 'openshift')).not.toContain(kept);
      expect(hiddenPaths(form, 'kubernetes')).not.toContain(kept);
    }
  });

  it('Trino on OpenShift: the metrics switch and scrape interval, not the kube-prometheus namespace', () => {
    const values = build(load('TRINO'), 'openshift');
    expect(values.serviceMonitor.enabled).toBe(true);
    expect(values.serviceMonitor.interval).toBe('30s');
    expect(values.monitoring ?? {}).toEqual({});
  });

  it('OpenMetadata on Kubernetes: the OpenShift-only external OpenSearch defaults are not sent', () => {
    const svc = load('OPENMETADATA');
    expect(hiddenPaths(svc.form, 'kubernetes')).toEqual(expect.arrayContaining([
      'runAsUser', 'externalOpensearch.host', 'externalOpensearch.port', 'externalOpensearch.scheme',
    ]));
    expect(build(svc, 'kubernetes').externalOpensearch ?? {}).toEqual({});
    expect(build(svc, 'openshift').externalOpensearch).toEqual({ port: 9200, scheme: 'http' });
  });
});
