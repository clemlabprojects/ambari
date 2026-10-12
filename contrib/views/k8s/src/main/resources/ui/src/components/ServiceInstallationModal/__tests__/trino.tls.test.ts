import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';
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
    expect(v.server?.config?.https?.enabled).toBeUndefined();
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
