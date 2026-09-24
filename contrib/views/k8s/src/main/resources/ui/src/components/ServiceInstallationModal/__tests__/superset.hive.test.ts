import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * Superset → platform-Hive wiring, driven by the real KDPS/services/SUPERSET/service.json: the
 * resolved context + the hiveDb.enabled toggle produce the `import_datasources.yaml` extraConfig
 * the chart's init job imports. Every datasource is PyHive (hive:// or hive+http(s)://) with
 * impersonate_user, so Ranger authorizes and audits the logged-in user, and the binding is picked
 * from the context's transport (binary/http) × authentication (kerberos/ldap/none).
 */
const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/SUPERSET/service.json'), 'utf8'));
const HIVE_VARS = def.variables.filter((v: any) => /^hive/.test(v.name));
const HIVE_BINDINGS = def.bindings.filter((b: any) => /^superset-hive-datasource-/.test(b.name));

const base = { 'hive.hs2HostPort': 'master02.dev01:10001', 'hive.authMode': 'kerberos', 'hive.scheme': 'https' };
const form = (extra: Record<string, any> = {}) => ({ hiveDb: { enabled: true, httpPath: 'cliservice', sslCert: 'none', ...extra } });

function render(resolved: Record<string, string>, formValues: any) {
  const varCtx = buildVarContext(HIVE_VARS as any, formValues, {}, resolved);
  const merged: any = {};
  applyBindingTargets(merged, HIVE_BINDINGS as any, {}, formValues, 'superset', varCtx);
  return { varCtx, yaml: merged.extraConfigs?.['import_datasources.yaml'] as string | undefined };
}

describe('Superset → platform Hive import_datasources (PyHive, impersonation)', () => {
  it('declares only PyHive datasources and no Hive SELECT grant', () => {
    expect(HIVE_BINDINGS.length).toBe(6);
    for (const b of HIVE_BINDINGS) {
      const tpl = b.targets[0].from.template['import_datasources.yaml'];
      expect(tpl).toMatch(/sqlalchemy_uri: hive(\+\$\{hiveScheme\})?:\/\//);
      expect(tpl).not.toContain('impala://');
      expect(tpl).toContain('impersonate_user: true');
      // Always emitted: the v0 importer keeps a database's existing `extra` when the file omits it,
      // so an upgrade would inherit stale connect_args from a previous driver.
      expect(tpl).toContain("extra: '{");
    }
    expect((def.platformOps || []).map((o: any) => o.op)).not.toContain('ranger.hiveGrant');
  });

  it('http + kerberos → hive+https with SPNEGO, http_path and ssl_cert from the form', () => {
    const { varCtx, yaml } = render({ ...base, 'hive.transportMode': 'http' }, form());
    expect(varCtx.hiveModeHttp).toBe('true');
    expect(varCtx.hiveAuthKerberos).toBe('true');
    expect(yaml).toContain('sqlalchemy_uri: hive+https://master02.dev01:10001/default?auth=KERBEROS&kerberos_service_name=hive&http_path=cliservice&ssl_cert=none');
    expect(yaml!.match(/database_name:/g)!.length).toBe(1);
  });

  it('binary + kerberos → hive:// with SASL Kerberos connect_args', () => {
    const { varCtx, yaml } = render({ ...base, 'hive.transportMode': 'binary' }, form());
    expect(varCtx.hiveModeBinary).toBe('true');
    expect(yaml).toContain('sqlalchemy_uri: hive://master02.dev01:10001/default\n');
    expect(yaml).toContain('"auth": "KERBEROS"');
    expect(yaml!.match(/database_name:/g)!.length).toBe(1);
  });

  it('unknown transport is treated as http', () => {
    const { yaml } = render(base, form());
    expect(yaml).toContain('hive+https://master02.dev01:10001/default?auth=KERBEROS');
  });

  it('http + ldap through Knox → BASIC with the service account and the topology path', () => {
    const { yaml } = render({ ...base, 'hive.authMode': 'ldap', 'hive.transportMode': 'http', 'hive.hs2HostPort': 'knox.example.com:8443' },
      form({ ldapUser: 'svc-superset', ldapPassword: 's3cret', httpPath: 'gateway/default/hive', sslCert: 'required' }));
    expect(yaml).toContain('sqlalchemy_uri: hive+https://svc-superset:s3cret@knox.example.com:8443/default?auth=BASIC&http_path=gateway/default/hive&ssl_cert=required');
  });

  it('ldap without a service account emits nothing rather than a broken URL', () => {
    const { yaml } = render({ ...base, 'hive.authMode': 'ldap', 'hive.transportMode': 'binary' }, form());
    expect(yaml).toBeUndefined();
  });

  it('binary + none → plain hive:// auth=NONE', () => {
    const { yaml } = render({ ...base, 'hive.authMode': 'none', 'hive.transportMode': 'binary' }, form());
    expect(yaml).toContain('sqlalchemy_uri: hive://master02.dev01:10001/default?auth=NONE');
  });

  it('toggle OFF → no Hive datasource emitted', () => {
    const { varCtx, yaml } = render({ ...base, 'hive.transportMode': 'http' }, { hiveDb: { enabled: false } });
    expect(varCtx.hiveHostPort).toBeUndefined();
    expect(yaml).toBeUndefined();
  });

  it('operator overrides (host:port, scheme) win over the context', () => {
    const { yaml } = render({ ...base, 'hive.transportMode': 'http' }, form({ hostPort: 'my-hs2:10001', scheme: 'http' }));
    expect(yaml).toContain('hive+http://my-hs2:10001/default?auth=KERBEROS');
  });
});
