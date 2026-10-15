import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';
import { buildChartValues } from '../chartValues';

/**
 * Some OpenShift clusters run an admission policy that refuses any Route without the router's
 * ip_whitelist annotation. Every chart copies ingress.annotations onto its Route, so the optional
 * form field must land there under the literal annotation key — and add nothing when left blank.
 */
const ANNOTATION = 'haproxy.router.openshift.io/ip_whitelist';

describe.each(['TRINO', 'SUPERSET', 'OPENMETADATA'])('%s service.json — OpenShift Route allowed source IPs', (svc) => {
  const def = JSON.parse(fs.readFileSync(
    path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));
  const VARS = (def.variables || []).filter((v: any) => v.name === 'routeIpWhitelist');
  const BINDINGS = (def.bindings || []).filter((b: any) => b.name === 'openshift-route-allowlist');

  function render(ipWhitelist?: string) {
    const form = { releaseName: 'r', ingress: { enabled: true, route: { ipWhitelist } } };
    const varCtx = buildVarContext(VARS as any, form, {}, {});
    const merged: any = {};
    applyBindingTargets(merged, BINDINGS as any, {}, form, 'r', varCtx);
    return merged;
  }

  it('offers the field in the form, kept out of the raw chart values', () => {
    const json = JSON.stringify(def.form);
    expect(json).toContain('"name":"ingress.route.ipWhitelist"');
    expect(VARS).toHaveLength(1);
    expect(BINDINGS).toHaveLength(1);
  });

  it('writes the allowed sources as the Route annotation', () => {
    const v = render('10.0.0.0/8 172.16.20.0/24');
    expect(v.ingress.annotations[ANNOTATION]).toBe('10.0.0.0/8 172.16.20.0/24');
  });

  it('sends the annotation but not the form field itself in the full chart values', () => {
    const values = buildChartValues({
      form: def.form, bindings: def.bindings, variables: def.variables,
      formValues: { releaseName: 'r', ingress: { enabled: true, host: 'r.apps.example.com', route: { ipWhitelist: '10.1.2.0/24' } } },
      platform: 'kubernetes', chartVersion: def.version, retiredValues: def.retiredValues,
    });
    expect(values.ingress.annotations[ANNOTATION]).toBe('10.1.2.0/24');
    expect(values.ingress.route?.ipWhitelist).toBeUndefined();
  });

  it('adds no annotation when the field is blank', () => {
    expect(render('')?.ingress?.annotations?.[ANNOTATION]).toBeUndefined();
    expect(render(undefined)?.ingress?.annotations?.[ANNOTATION]).toBeUndefined();
  });
});
