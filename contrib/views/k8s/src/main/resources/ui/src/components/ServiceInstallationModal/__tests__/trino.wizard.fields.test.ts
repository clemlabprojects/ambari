import * as fs from 'fs';
import * as path from 'path';
import { openShiftMonitoringNotice } from '../OpenShiftMonitoringNotice';
import { buildChartValues } from '../chartValues';

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

  it('KDPS installs the Prometheus stack in the namespace the form names, as for KEDA', () => {
    expect(svc.dependencies['kube-prometheus-stack'].namespaceFromForm).toBe('monitoring.namespace');
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
    const french = texts.filter(t =>
      /[éèêàçù]|\b(Seuil|Chemin|Nombre|Limite|Activer|Nom|interne|optionnel|Mot de passe|recherche|logique|Fichier)\b/.test(t));
    expect(french).toEqual([]);
  });
});

describe('Trino secrets stay out of ConfigMaps', () => {
  const allFields = (fields: any[]): any[] => fields.flatMap((f: any) => (Array.isArray(f.fields) ? allFields(f.fields) : [f]));
  const targets = (svc.bindings as any[]).flatMap((b: any) => (b.targets || []).map((t: any) => ({ ...t, binding: b.name })));

  it('no longer asks for the internal shared secret: the chart generates it and keeps it in a Secret', () => {
    expect(allFields(svc.form).map((f: any) => f.name)).not.toContain('internalSharedSecret');
    expect((svc.variables as any[]).map((v: any) => v.name)).not.toContain('internalSharedSecret');
    expect(svc.retiredValues).toHaveProperty(['internalSharedSecret']);
  });

  it('no binding writes a shared secret or a keystore password into the server configuration', () => {
    const lines = targets.filter((t: any) => /additionalConfigProperties/.test(t.path))
      .map((t: any) => String(t.value ?? t.from?.template ?? ''));
    expect(lines.filter(l => /shared-secret|keystore\.key|password/.test(l))).toEqual([]);
  });

  it('catalog credentials come from Secrets the operator picks, sent to the chart as a list', () => {
    const f = field('customCatalogs', 'catalogCredentialSecrets');
    expect(f.type).toBe('secret-discovery');
    expect(f.multiple).toBe(true);
    expect(f.excludeFromValues).toBeUndefined();
    expect(f.defaultValue).toEqual([]);
    expect(svc.featureChartVersions.catalogCredentialSecrets).toBe('>=1.43.16');
    expect(field('customCatalogs', 'customCatalogs.text').help).toContain('Secrets with catalog credentials');
  });

  it('an upgrade drops the secrets an earlier version left in the values and keeps the credential Secrets', () => {
    // What an earlier version deployed with a typed shared secret and keystore password (the wizard seeds the form
    // with it on an upgrade).
    const values = buildChartValues({
      form: svc.form, bindings: [], variables: [], retiredValues: svc.retiredValues,
      formValues: {
        releaseName: 'trino',
        internalSharedSecret: 'Typed-Shared-Secret-1',
        additionalConfigProperties: ['internal-communication.shared-secret=Typed-Shared-Secret-1', 'http-server.process-forwarded=true'],
        coordinator: { additionalConfigProperties: ['http-server.https.keystore.key=Typed-Keystore-Pass-2', 'query.max-history=500'] },
        catalogCredentialSecrets: ['pg-credentials'],
      },
    });
    expect(JSON.stringify(values)).not.toContain('Typed-Shared-Secret-1');
    expect(JSON.stringify(values)).not.toContain('Typed-Keystore-Pass-2');
    expect(values.additionalConfigProperties).toEqual(['http-server.process-forwarded=true']);
    expect(values.coordinator.additionalConfigProperties).toEqual(['query.max-history=500']);
    expect(values.catalogCredentialSecrets).toEqual(['pg-credentials']);
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
