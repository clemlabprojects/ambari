import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

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

  it('an operator-supplied keystore password is pinned in the coordinator config', () => {
    const v = render(seededTls('trino', { password: 's3cret' }));
    expect(v.coordinator.additionalConfigProperties).toContain('http-server.https.keystore.key=s3cret');
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
