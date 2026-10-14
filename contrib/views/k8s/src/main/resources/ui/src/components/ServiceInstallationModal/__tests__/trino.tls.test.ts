import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets, deepMerge } from '../bindings';
import { applyFormDefaults, buildChartValues, seedFieldsFromValues } from '../chartValues';
import { seedFieldsFromRelease } from '../seedFromRelease';
import { buildTlsPayload } from '../tlsPayload';

/**
 * Trino terminates TLS itself by default. Two things have to move together, so they are asserted
 * against the real service.json: the coordinator gets an HTTPS listener with the keystore KDPS
 * generates, AND the forwarded-header trust is dropped — because `http-server.process-forwarded`
 * tells Trino to believe X-Forwarded-Proto, which is what lets authenticated traffic through. With
 * TLS on the coordinator, leaving it set would let anything inside the cluster hit the plain HTTP
 * port, claim the request arrived over TLS and clear the gate.
 */
const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'), 'utf8'));
const VARS = (def.variables || []).filter((v: any) => /^(tlsHttps|releaseNameVar)/.test(v.name));
const BINDINGS = (def.bindings || []).filter((b: any) => ['https-config', 'trust-proxy-headers'].includes(b.name));

/** The wizard seeds installValues.tls from def.tls[].defaults + secretNameTemplate (ServiceWizardPage). */
function seededTls(release: string, over: Record<string, any> = {}) {
  const t = def.tls[0];
  const secretName = String(t.secretNameTemplate).replace('{{releaseName}}', release);
  return { ...t.defaults, secretName, passwordSecretName: `${secretName}-pass`, ...over };
}

function render(tlsForm: any, release = 'trino') {
  const form = { releaseName: release, tls: { https: tlsForm } };
  const varCtx = buildVarContext(VARS as any, form, {}, {});
  const merged: any = {};
  applyBindingTargets(merged, BINDINGS as any, {}, form, release, varCtx);
  return merged;
}

describe('TRINO service.json — TLS on the coordinator', () => {
  it('ships enabled by default', () => {
    expect(def.tls[0].defaults.enabled).toBe(true);
    expect(def.tls[0].keystoreType).toBe('PKCS12');
    expect(def.tls[0].dnsTemplates).toEqual(expect.arrayContaining(['{{ingress.host}}']));
  });

  it('the SANs cover the Service clients actually connect to', () => {
    // The chart's client-facing Service is `trino.fullname` = <release>-<nameOverride>; there is no
    // <...>-coordinator Service. A certificate that names only the coordinator fails the handshake
    // for every in-cluster client (Superset, dbt, OpenMetadata) the moment TLS is on — and nothing
    // catches it while clients still use plain HTTP or `verify=false`.
    const t = def.tls[0].dnsTemplates as string[];
    expect(t[0]).toBe('{{releaseName}}-{{nameOverride}}.{{namespace}}.svc');
    expect(t).toEqual(expect.arrayContaining([
      '{{releaseName}}-{{nameOverride}}.{{namespace}}.svc',
      '{{releaseName}}-{{nameOverride}}.{{namespace}}.svc.cluster.local',
      '{{ingress.host}}',
    ]));
  });

  it('default install: HTTPS listener wired to the generated keystore, no forwarded-header trust', () => {
    const v = render(seededTls('trino'));
    expect(v.server.config.https.enabled).toBe(true);
    expect(v.server.config.https.port).toBe(8443);
    expect(v.server.config.https.keystore.secretName).toBe('trino-https');
    expect(v.server.config.https.keystore.passwordSecretName).toBe('trino-https-pass');
    expect(v.server.config.https.keystore.key).toBe('keystore.p12');
    // the whole point:
    expect(JSON.stringify(v.additionalConfigProperties || [])).not.toContain('process-forwarded');
    // no empty keystore.key= line when KDPS generated the password
    expect(JSON.stringify(v.coordinator?.additionalConfigProperties || [])).not.toContain('keystore.key=');
  });

  it('an operator-supplied keystore password never goes into the server configuration', () => {
    // The coordinator config is a ConfigMap anyone allowed to read ConfigMaps can read. KDPS keeps the typed password
    // in the <keystore Secret>-pass Secret (TlsManager) and the chart's init container appends it from there.
    const v = render(seededTls('trino', { password: 's3cret', autoGenerate: false }));
    expect(JSON.stringify(v)).not.toContain('s3cret');
    expect(v.server.config.https.keystore.passwordSecretName).toBe('trino-https-pass');
    expect(v.server.config.https.keystore.passwordKey).toBe('truststore.password');
  });

  it('TLS off falls back to edge termination, and only then trusts the router', () => {
    const v = render(seededTls('trino', { enabled: false }));
    expect(v.additionalConfigProperties).toContain('http-server.process-forwarded=true');
    // Said explicitly, not left to the chart default: an upgrade starts from values that may hold true.
    expect(v.server.config.https.enabled).toBe(false);
    expect(v.server.config.https.keystore).toBeUndefined();
  });

  it('does not repoint the CA truststore at this release\'s own keystore', () => {
    // The truststore secret is the platform CA bundle KDPS wires separately (trino-truststore).
    // Overwriting it with the server keystore would leave Trino unable to verify the Keycloak JWKS
    // endpoint or Ranger — breaking the OIDC path TLS is being enabled for.
    const v = render(seededTls('trino'));
    expect(v.global.security.tls.enabled).toBe(true);
    expect(v.global.security.tls.truststore.enabled).toBe(true);
    expect(v.global.security.tls.truststoreSecret).toBeUndefined();
    expect(v.global.security.tls.truststoreKey).toBeUndefined();
  });
});

describe('TRINO service.json — TLS payload the server provisions from', () => {
  const form = (tls: any) => ({ releaseName: 'trino', namespace: 'analytics', nameOverride: 'trino', tls: { https: tls } });

  it('the password Secret follows an edited keystore Secret, as the chart mounts it', () => {
    // The wizard seeds passwordSecretName from the default keystore Secret when the definition loads; the bindings
    // mount `<keystore Secret>-pass` from the field as submitted. A password written under the seeded name would
    // leave the coordinator waiting for a Secret that does not exist.
    const tls = seededTls('trino', { secretName: 'corp-trino-ks', autoGenerate: false, password: 's3cret' });
    expect(tls.passwordSecretName).toBe('trino-https-pass');

    const payload = buildTlsPayload(def.tls, form(tls));

    expect(payload.https.passwordSecretName).toBe('corp-trino-ks-pass');
    expect(payload.https.secretName).toBe('corp-trino-ks');
    expect(render(tls).server.config.https.keystore.passwordSecretName).toBe(payload.https.passwordSecretName);
  });

  it('keeps the default names and resolves the SANs of a default install', () => {
    const payload = buildTlsPayload(def.tls, form(seededTls('trino')));
    expect(payload.https.secretName).toBe('trino-https');
    expect(payload.https.passwordSecretName).toBe('trino-https-pass');
    expect(payload.https.dnsNames).toEqual(expect.arrayContaining([
      'trino-trino.analytics.svc', 'trino-trino.analytics.svc.cluster.local']));
  });

  it('keeps a password Secret the service definition names itself', () => {
    const specs = [{ ...def.tls[0], defaults: { ...def.tls[0].defaults, passwordSecretName: 'shared-https-pass' } }];
    const payload = buildTlsPayload(specs, form(seededTls('trino', { secretName: 'corp-trino-ks' })));
    expect(payload.https.passwordSecretName).toBe('shared-https-pass');
  });
});

describe('TRINO service.json — "Let KDPS manage catalogs" needs internal HTTPS', () => {
  // The chart refuses kdps.serviceUser.enabled without server.config.https.enabled (Trino only accepts password logins
  // over HTTPS), and the option is on by default. Built the way the wizard builds the deploy, from every field's
  // default, with all the bindings and variables of the definition.
  const field = (group: string, name: string) =>
    def.form.find((g: any) => g.name === group).fields.find((f: any) => f.name === name);

  const chartValues = (form: any) => buildChartValues({
    form: def.form, bindings: def.bindings, variables: def.variables, formValues: form,
    platform: 'kubernetes', chartVersion: def.version, retiredValues: def.retiredValues,
  });

  function wizardValues(httpsEnabled: boolean) {
    const form: any = { releaseName: 'trino', namespace: 'analytics' };
    applyFormDefaults(def.form, form);
    form.tls = { https: seededTls('trino', { enabled: httpsEnabled }) };
    return chartValues(form);
  }

  /** The form Upgrade/Config opens with: the defaults, with the release's values merged over them (ServiceWizardPage). */
  function upgradeForm(deployed: any) {
    const form: any = { releaseName: 'trino', namespace: 'analytics' };
    applyFormDefaults(def.form, form);
    form.tls = { https: seededTls('trino') };
    deepMerge(form, JSON.parse(JSON.stringify(deployed)));
    seedFieldsFromValues(def.form, form);
    seedFieldsFromRelease(def.form, form);
    return form;
  }

  it('is on by default, with internal HTTPS', () => {
    const v = wizardValues(true);
    expect(v.kdps.serviceUser.enabled).toBe(true);
    expect(v.server.config.https.enabled).toBe(true);
  });

  it('internal HTTPS off turns it off, although the hidden checkbox keeps its default', () => {
    const v = wizardValues(false);
    expect(v.kdps.serviceUser.enabled).toBe(false);
    expect(v.server.config.https.enabled).toBe(false);
  });

  it('the checkbox is shown only with internal HTTPS, and the two help texts point at each other', () => {
    const option = field('customCatalogs', 'kdps.serviceUser.enabled');
    const https = field('httpsInternal', 'tls.https.enabled');
    expect(option.defaultValue).toBe(true);
    expect(option.condition).toEqual({ field: 'tls.https.enabled', value: true });
    expect(option.help).toContain(`'${https.label}'`);
    expect(https.help).toContain("'Let KDPS manage catalogs'");
  });


  it('Upgrade/Config: turning internal HTTPS off reaches the chart, together with the option', () => {
    // The release's values hold server.config.https.enabled: true from the install. Skipping the binding that writes
    // it would send that true again: HTTPS would stay on while the option is turned off.
    const deployed = { server: { config: { https: { enabled: true } } }, kdps: { serviceUser: { enabled: true } } };
    const form = upgradeForm(deployed);
    expect(form.tls.https.enabled).toBe(true);
    expect(form.kdps.serviceUser.enabled).toBe(true);

    form.tls.https.enabled = false;
    const v = chartValues(form);
    expect(v.server.config.https.enabled).toBe(false);
    expect(v.kdps.serviceUser.enabled).toBe(false);
    expect(v.additionalConfigProperties).toContain('http-server.process-forwarded=true');
  });

  it('Upgrade/Config opens with the internal HTTPS setting the release runs with', () => {
    const httpsOff = upgradeForm(wizardValues(false));
    expect(httpsOff.tls.https.enabled).toBe(false);
    const resent = chartValues(httpsOff);
    expect(resent.server.config.https.enabled).toBe(false);
    expect(resent.kdps.serviceUser.enabled).toBe(false);

    const httpsOn = upgradeForm(wizardValues(true));
    expect(httpsOn.tls.https.enabled).toBe(true);
    const kept = chartValues(httpsOn);
    expect(kept.server.config.https.enabled).toBe(true);
    expect(kept.kdps.serviceUser.enabled).toBe(true);
  });
});
