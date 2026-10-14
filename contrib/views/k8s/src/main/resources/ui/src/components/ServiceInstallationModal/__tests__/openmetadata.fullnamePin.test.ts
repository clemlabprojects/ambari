import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * Verifies the OpenMetadata "om-fullname-pin" binding names the chart's Service exactly like the release, so
 * the post-install steps (ingestion, Atlas federation, Ranger TagSync) and the endpoint shown for the release
 * find it whatever the release is called. The chart only names its Service after the release when the release
 * name contains "openmetadata". Loads the REAL service.json so the test fails if the wiring drifts.
 */
const svc = JSON.parse(
  fs.readFileSync(
    path.join(__dirname, '../../../../../KDPS/services/OPENMETADATA/service.json'),
    'utf8'
  )
);
const binding = svc.bindings.find((b: any) => b.name === 'om-fullname-pin');

const apply = (releaseName: string) => {
  const formValues = { releaseName };
  const varCtx = buildVarContext(svc.variables, formValues, {});
  const values: any = { fullnameOverride: '' };
  applyBindingTargets(values, [binding], {}, formValues, 'om', varCtx);
  return values;
};

describe('OpenMetadata om-fullname-pin binding', () => {
  it('exists and writes only fullnameOverride', () => {
    expect(binding).toBeTruthy();
    expect(binding.targets.map((t: any) => t.path)).toEqual(['fullnameOverride']);
  });

  it.each(['datacatalog', 'om-prod', 'openmetadata', 'my-openmetadata'])(
    'sets fullnameOverride to the release name %s',
    (name) => {
      expect(apply(name).fullnameOverride).toBe(name);
    }
  );

  it('is mirrored by the server (releaseNameValues)', () => {
    expect(svc.releaseNameValues).toEqual(['fullnameOverride']);
  });

  it('builds the endpoint host and certificate names from the release name alone', () => {
    const host = svc.endpoints[0].properties.host.template;
    expect(host).toBe('{{releaseName}}.{{namespace}}.svc.cluster.local');
    const tls = svc.tls.find((c: any) => c.key === 'https');
    expect(tls.dnsTemplates).toEqual([
      '{{releaseName}}.{{namespace}}.svc',
      '{{releaseName}}.{{namespace}}.svc.cluster.local',
      '{{ingress.host}}',
    ]);
  });

  it('has no host built as <release>-openmetadata anywhere', () => {
    expect(JSON.stringify(svc)).not.toContain('{{releaseName}}-openmetadata.');
  });
});
