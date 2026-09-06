import * as fs from 'fs';
import * as path from 'path';
import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * End-to-end (in-process) test of the Superset → platform-Trino wiring, run against the REAL
 * KDPS/services/SUPERSET/service.json (loaded from disk) so it can never drift from what ships.
 *
 * The auth mode (ui_trino_auth_mode) selects exactly one of the five per-mode bindings via
 * `skipIfVarEmpty: [trinoHost, trinoMode<X>]`; every mode emits a Trino database with
 * impersonate_user: true into import_datasources_trino.yaml — a SEPARATE file from the Hive
 * import_datasources.yaml so the two never collide. OIDC additionally flips trinoJwt.enabled.
 */
const SERVICE_JSON = path.join(
  __dirname,
  '../../../../../KDPS/services/SUPERSET/service.json',
);
const def = JSON.parse(fs.readFileSync(SERVICE_JSON, 'utf8'));
// Trino vars = the ones the trino bindings reference; load them ALL from the real def so the
// equals/order semantics are exactly production's.
const VARS = (def.variables || []).filter((v: any) =>
  /^trino/.test(v.name),
);
const TRINO_BINDINGS = (def.bindings || []).filter((b: any) =>
  /^superset-trino-datasource-/.test(b.name),
);

function resolve(form: any) {
  const varCtx = buildVarContext(VARS as any, form, {}, {});
  const merged: any = {};
  applyBindingTargets(merged, TRINO_BINDINGS as any, {}, form, 'superset', varCtx);
  return { varCtx, merged, yaml: merged.extraConfigs?.['import_datasources_trino.yaml'] };
}

const HOST = { ui_trino_host: 'rel-clemlab-trino-coordinator.trino.svc', ui_trino_port: 8080, ui_trino_catalog: 'hive' };

describe('Superset → platform Trino import_datasources_trino.yaml', () => {
  it('service.json exposes all five per-mode bindings + mode vars', () => {
    expect(TRINO_BINDINGS.map((b: any) => b.name).sort()).toEqual([
      'superset-trino-datasource-kerberos',
      'superset-trino-datasource-ldap',
      'superset-trino-datasource-none',
      'superset-trino-datasource-oidc',
      'superset-trino-datasource-tls',
    ]);
  });

  it('none → plain trino:// URL + impersonation, no encrypted_extra', () => {
    const { varCtx, yaml } = resolve({ ...HOST, ui_trino_auth_mode: 'none' });
    expect(varCtx.trinoModeNone).toBe('true');
    expect(varCtx.trinoModeTls).toBe('');
    expect(yaml).toContain('sqlalchemy_uri: trino://rel-clemlab-trino-coordinator.trino.svc:8080/hive');
    expect(yaml).toContain('impersonate_user: true');
    expect(yaml).not.toContain('encrypted_extra');
    expect(yaml).not.toContain('https');
  });

  it('tls → http_scheme https + verify against the mounted CA', () => {
    const { yaml } = resolve({ ...HOST, ui_trino_auth_mode: 'tls' });
    expect(yaml).toContain('"http_scheme": "https"');
    expect(yaml).toContain('"verify": "/etc/security/truststore/ca.crt"');
    expect(yaml).toContain('impersonate_user: true');
    expect(yaml).not.toContain('encrypted_extra');
  });

  it('ldap → encrypted_extra basic auth with the operator service account', () => {
    const { yaml } = resolve({ ...HOST, ui_trino_auth_mode: 'ldap', ui_trino_svc_user: 'svc-superset', ui_trino_svc_password: 'p@ss' });
    expect(yaml).toContain('"auth_method": "basic"');
    expect(yaml).toContain('"username": "svc-superset"');
    expect(yaml).toContain('"password": "p@ss"');
    expect(yaml).toContain('impersonate_user: true');
  });

  it('kerberos → encrypted_extra kerberos using krb5.conf + ccache', () => {
    const { yaml } = resolve({ ...HOST, ui_trino_auth_mode: 'kerberos' });
    expect(yaml).toContain('"auth_method": "kerberos"');
    expect(yaml).toContain('"config": "/etc/krb5.conf"');
    expect(yaml).toContain('"service_name": "trino"');
    expect(yaml).toContain('impersonate_user: true');
  });

  it('oidc → encrypted_extra kdps_file_jwt + flips trinoJwt.enabled', () => {
    const { merged, yaml } = resolve({ ...HOST, ui_trino_auth_mode: 'oidc' });
    expect(yaml).toContain('"auth_method": "kdps_file_jwt"');
    expect(yaml).toContain('impersonate_user: true');
    // The binding sets trinoJwt.enabled truthy (the engine may coerce "true"→true); the chart's
    // `if .Values.trinoJwt.enabled` accepts either.
    expect([true, 'true']).toContain(merged.trinoJwt?.enabled);
  });

  it('exactly ONE datasource fires per mode (skipIfVarEmpty gating)', () => {
    for (const mode of ['none', 'tls', 'ldap', 'kerberos', 'oidc']) {
      const form: any = { ...HOST, ui_trino_auth_mode: mode };
      if (mode === 'ldap') { form.ui_trino_svc_user = 'u'; form.ui_trino_svc_password = 'p'; }
      const { yaml } = resolve(form);
      // one databases: block only
      expect((yaml.match(/database_name: Platform Trino/g) || []).length).toBe(1);
    }
  });

  it('no Trino host → no datasource emitted (all five skipped)', () => {
    const { merged } = resolve({ ui_trino_auth_mode: 'none' });
    expect(merged.extraConfigs?.['import_datasources_trino.yaml']).toBeUndefined();
  });

  it('writes a SEPARATE file from Hive so the two coexist (no collision)', () => {
    const { merged } = resolve({ ...HOST, ui_trino_auth_mode: 'none' });
    expect(merged.extraConfigs?.['import_datasources_trino.yaml']).toBeDefined();
    expect(merged.extraConfigs?.['import_datasources.yaml']).toBeUndefined();
  });
});
