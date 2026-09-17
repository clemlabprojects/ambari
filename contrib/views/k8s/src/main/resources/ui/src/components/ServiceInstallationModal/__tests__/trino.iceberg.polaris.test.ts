import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets, interpolate } from '../bindings';

/**
 * Trino "iceberg" catalog backed by the Apache Polaris REST catalog of the selected Platform
 * Context, run against the REAL KDPS/services/TRINO/service.json + polaris capability so the test
 * can never drift from what ships.
 *
 * Contract under test:
 *  - the catalog is emitted ONLY when the toggle is on AND both the REST URI and the warehouse
 *    (Polaris catalog) are known — from the context resolver (managed ODP: Ambari POLARIS
 *    service) or from the operator's Override fields (external contexts);
 *  - the OAuth2 client credential is NEVER written into the catalog text by the view: the view
 *    only fills `polarisCredential.*` and the Trino chart (>=1.43.9) appends
 *    `iceberg.rest-catalog.oauth2.credential=${ENV:POLARIS_CLIENT_ID}:${ENV:POLARIS_CLIENT_SECRET}`
 *    itself — because the view's interpolator blanks any `${...}` it cannot resolve (asserted
 *    below), a literal `${ENV:...}` in a view template would be erased.
 */
const SERVICE_JSON = path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json');
const POLARIS_CAP = path.join(__dirname, '../../../../../KDPS/contexts/capabilities/polaris.json');
const def = JSON.parse(fs.readFileSync(SERVICE_JSON, 'utf8'));
const polaris = JSON.parse(fs.readFileSync(POLARIS_CAP, 'utf8'));

const VARS = (def.variables || []).filter((v: any) => /^(polaris|iceberg)/.test(v.name));
const BINDINGS = (def.bindings || []).filter((b: any) => /^iceberg-/.test(b.name));

/** The wizard keeps form state NESTED (a field named `icebergCatalog.enabled` lives at
 *  form.icebergCatalog.enabled, Helm-values style) — that is what getAtStr() walks. */
function nest(flat: Record<string, any>): any {
  const out: any = {};
  for (const [k, v] of Object.entries(flat)) {
    const parts = k.split('.');
    let cur = out;
    for (const part of parts.slice(0, -1)) cur = cur[part] ??= {};
    cur[parts[parts.length - 1]] = v;
  }
  return out;
}

function resolve(flatForm: any, resolved: Record<string, string> = {}) {
  const form = nest(flatForm);
  const varCtx = buildVarContext(VARS as any, form, {}, resolved);
  const merged: any = {};
  applyBindingTargets(merged, BINDINGS as any, {}, form, 'trino', varCtx);
  return { varCtx, merged, catalog: merged.additionalCatalogs?.iceberg as string | undefined };
}

const CTX = { 'polaris.restUri': 'https://polaris01.dev21:8443/api/catalog', 'polaris.catalog': 'ozone' };
const ON = { 'icebergCatalog.enabled': true, 'icebergCatalog.s3Region': 'us-east-1' };

describe('TRINO service.json — Iceberg catalog on Polaris', () => {
  it('declares the chart pin, the four bindings and the polaris requiresContext entry', () => {
    expect(def.version).toBe('1.43.10');
    expect(BINDINGS.map((b: any) => b.name).sort()).toEqual([
      'iceberg-catalog',
      'iceberg-catalog-s3-endpoint',
      'iceberg-polaris-credential-inline',
      'iceberg-polaris-credential-secret',
      'iceberg-s3-credential-inline',
      'iceberg-s3-credential-secret',
    ]);
    const rc = (def.requiresContext || []).find((r: any) => r.capability === 'polaris');
    expect(rc).toEqual({ capability: 'polaris', fields: ['restUri', 'catalog'], when: 'icebergCatalog.enabled' });
    const toggle = def.form.find((g: any) => g.name === 'icebergIntegration').fields.find((f: any) => f.name === 'icebergCatalog.enabled');
    expect(toggle.autoEnableWhenContext).toBe('polaris');
  });

  it('polaris capability: every field is operator-providable on EXTERNAL/CDP/REMOTE and resolver-backed on MANAGED', () => {
    expect(polaris.capability).toBe('polaris');
    for (const f of polaris.fields) {
      const kinds = String(f.appliesTo).split(',').map((s: string) => s.trim());
      expect(kinds).toEqual(expect.arrayContaining(['EXTERNAL', 'CDP', 'REMOTE']));
      expect(f.managedResolver).toBe(`polaris.${f.name}`);
    }
    expect(polaris.fields.map((f: any) => f.name)).toEqual(expect.arrayContaining(['restUri', 'catalog', 'realm', 'realmHeaderRequired']));
  });

  it('toggle OFF → no iceberg catalog and no credential, even with a Polaris-capable context', () => {
    const { merged, catalog } = resolve({ 'icebergCatalog.enabled': false, 'icebergCatalog.clientId': 'x' }, CTX);
    expect(catalog).toBeUndefined();
    expect(merged.polarisCredential).toBeUndefined();
  });

  it('managed context resolves URI + warehouse; inline credential goes to polarisCredential, never into the catalog text', () => {
    const { merged, catalog } = resolve({ ...ON, 'icebergCatalog.clientId': 'trino-svc', 'icebergCatalog.clientSecret': 's3cr3t' }, CTX);
    expect(catalog).toContain('connector.name=iceberg\n');
    expect(catalog).toContain('iceberg.catalog.type=rest\n');
    expect(catalog).toContain('iceberg.rest-catalog.uri=https://polaris01.dev21:8443/api/catalog\n');
    expect(catalog).toContain('iceberg.rest-catalog.warehouse=ozone\n');
    expect(catalog).toContain('iceberg.rest-catalog.security=OAUTH2\n');
    expect(catalog).toContain('iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL\n');
    expect(catalog).toContain('iceberg.rest-catalog.vended-credentials-enabled=true\n');
    expect(catalog).toContain('fs.native-s3.enabled=true\n');
    expect(catalog).not.toContain('oauth2.credential');   // appended by the chart from ${ENV:…}
    expect(catalog).not.toContain('s3cr3t');
    expect(catalog).not.toContain('s3.endpoint');          // no S3 override requested
    expect(merged.polarisCredential).toEqual({ clientId: 'trino-svc', clientSecret: 's3cr3t' });
  });

  it('operator Override wins over the context-resolved URI/warehouse (external context path)', () => {
    const { catalog } = resolve({ ...ON, 'icebergCatalog.restUri': 'https://cdp-polaris:8181/api/catalog', 'icebergCatalog.warehouse': 'lake' }, CTX);
    expect(catalog).toContain('iceberg.rest-catalog.uri=https://cdp-polaris:8181/api/catalog\n');
    expect(catalog).toContain('iceberg.rest-catalog.warehouse=lake\n');
  });

  it('toggle ON but the context has no Polaris and nothing overridden → catalog skipped (never a blank URI)', () => {
    const { merged, catalog } = resolve({ ...ON, 'icebergCatalog.clientId': 'trino-svc' }, {});
    expect(catalog).toBeUndefined();
    expect(merged.polarisCredential).toBeUndefined(); // credential bindings are gated on the URI too
  });

  it('S3 endpoint override (Ozone S3 gateway) adds endpoint, path-style and region', () => {
    const { catalog } = resolve({ ...ON, 'icebergCatalog.s3Endpoint': 'http://ozone-s3g.dev21:9878' }, CTX);
    expect(catalog).toContain('s3.endpoint=http://ozone-s3g.dev21:9878\n');
    expect(catalog).toContain('s3.path-style-access=true\n');
    expect(catalog).toContain('s3.region=us-east-1\n');
    expect(catalog).toContain('iceberg.rest-catalog.uri=https://polaris01.dev21:8443/api/catalog\n'); // base lines kept
  });

  it('an existing Kubernetes Secret is referenced instead of inline values', () => {
    const { merged } = resolve({ ...ON, 'icebergCatalog.credentialSecret': 'trino-polaris', 'icebergCatalog.clientId': 'ignored' }, CTX);
    expect(merged.polarisCredential.secretRef).toEqual({ name: 'trino-polaris' });
    // the inline binding also fires (clientId typed) — chart precedence: secretRef.name wins.
    expect(merged.polarisCredential.clientId).toBe('ignored');
  });

  it('static S3 keys (no-STS store such as Ozone/MinIO) go to s3Credential, never into the catalog text; vending stays chart-controlled', () => {
    const { merged, catalog } = resolve({ ...ON, 'icebergCatalog.s3Endpoint': 'http://ozone-s3g.dev21:9878', 'icebergCatalog.s3AccessKey': 'AKIA', 'icebergCatalog.s3SecretKey': 'sekret' }, CTX);
    expect(merged.s3Credential).toEqual({ accessKey: 'AKIA', secretKey: 'sekret' });
    expect(catalog).not.toContain('sekret');
    expect(catalog).not.toContain('s3.aws-access-key'); // appended by the chart from ${ENV:…}
    expect(catalog).toContain('iceberg.rest-catalog.vended-credentials-enabled=true\n'); // chart flips it to false when s3Credential is set
  });

  it('an existing S3 Secret is referenced instead of inline keys', () => {
    const { merged } = resolve({ ...ON, 'icebergCatalog.s3CredentialSecret': 'ozone-s3-keys' }, CTX);
    expect(merged.s3Credential).toEqual({ secretRef: { name: 'ozone-s3-keys' } });
  });

  it('no S3 keys → no s3Credential block at all', () => {
    const { merged } = resolve({ ...ON, 'icebergCatalog.clientId': 'x', 'icebergCatalog.clientSecret': 'y' }, CTX);
    expect(merged.s3Credential).toBeUndefined();
  });

  it('documents WHY the credential line lives in the chart: the view interpolator blanks unknown ${…} tokens', () => {
    expect(interpolate('cred=${ENV:POLARIS_CLIENT_ID}:${ENV:POLARIS_CLIENT_SECRET}', {})).toBe('cred=:');
    for (const b of BINDINGS) {
      for (const t of b.targets) expect(String(t.from.template)).not.toMatch(/\$\{ENV:/);
    }
  });
});
