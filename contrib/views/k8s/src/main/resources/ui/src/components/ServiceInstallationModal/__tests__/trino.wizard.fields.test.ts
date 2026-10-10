import * as fs from 'fs';
import * as path from 'path';
import { openShiftMonitoringNotice } from '../OpenShiftMonitoringNotice';

/**
 * AMBARI-727 — the Trino wizard shows monitoring and KEDA fields only where they are used. Loads the REAL
 * service.json so the test fails if the form drifts.
 */
const svc = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'), 'utf8')
);
const group = (name: string) => svc.form.find((g: any) => g.name === name);
const field = (g: string, name: string) => group(g).fields.find((f: any) => f.name === name);

describe('Trino wizard fields', () => {
  it('KEDA: no release field, and the namespace only when no operator was found', () => {
    expect(field('autoscalingGeneric', 'keda.release')).toBeUndefined();
    expect(field('autoscalingGeneric', 'keda.namespace').condition).toEqual({ field: 'keda.discovery', operator: 'empty' });
    expect(field('autoscalingGeneric', 'keda.discovery').autoSelectSingle).toBe(true);
  });

  it('Prometheus namespace and release only when discovery found nothing; address and labels stay editable', () => {
    for (const name of ['monitoring.namespace', 'monitoring.release']) {
      expect(field('monitoringGeneric', name).condition).toEqual({ field: 'monitoring.discovery', operator: 'empty' });
    }
    expect(field('monitoringGeneric', 'monitoring.url').condition).toBeUndefined();
    expect(field('monitoringGeneric', 'monitoring.serviceMonitorLabels').condition).toBeUndefined();
  });

  it('the Prometheus and KEDA groups are plain-Kubernetes only; OpenShift gets the monitoring notice', () => {
    expect(group('monitoringGeneric').capability).toBe('kubernetes');
    expect(group('autoscalingGeneric').capability).toBe('kubernetes');
    expect(group('monitoringOpenShift').capability).toBe('openshift');
    expect(field('monitoringOpenShift', 'monitoring.openshiftNotice').type).toBe('openshift-monitoring-notice');
  });

  it('labels and help texts are in English', () => {
    const texts: string[] = [];
    const walk = (fields: any[]) => fields.forEach((f: any) => {
      if (f.label) texts.push(f.label);
      if (f.help) texts.push(f.help);
      if (Array.isArray(f.fields)) walk(f.fields);
    });
    walk(svc.form);
    const french = texts.filter(t => /[éèêàçù]|\b(Seuil|Chemin|Nombre|Limite|Activer|Nom du)\b/.test(t));
    expect(french).toEqual([]);
  });
});

describe('OpenShift monitoring notice', () => {
  it('warns when user workload monitoring is off, confirms when on, explains when unknown', () => {
    expect(openShiftMonitoringNotice(false).type).toBe('warning');
    expect(openShiftMonitoringNotice(false).description).toContain('enableUserWorkload: true');
    expect(openShiftMonitoringNotice(true).type).toBe('success');
    expect(openShiftMonitoringNotice(null).type).toBe('info');
    expect(openShiftMonitoringNotice(undefined).description).toContain('cannot check');
    for (const s of [true, false, null]) expect(openShiftMonitoringNotice(s).description).toContain('monitoring-edit');
  });
});
