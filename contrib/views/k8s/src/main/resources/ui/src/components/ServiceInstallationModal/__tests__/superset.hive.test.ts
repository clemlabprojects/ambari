import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * End-to-end (in-process) test of the Superset → platform-Hive wiring: a resolved context +
 * the hiveDb.enabled toggle must drive the `import_datasources.yaml` extraConfig that the chart's
 * init-job imports, with the context-resolved HiveServer2 host:port and impersonation enabled.
 *
 * The SQLAlchemy driver is chosen from the resolved HS2 transport mode (hive.transportMode):
 *   - binary  → hive://  (PyHive),   database labelled "Platform Hive"
 *   - http    → impala:// (impyla),  database labelled "Platform Hive (impyla, HTTP)"
 *   - unknown → impala:// (safe default, preserves the pre-transport-detection behaviour)
 * When the toggle is off, no Hive datasource is emitted.
 *
 * The var/binding shapes below mirror KDPS/services/SUPERSET/service.json.
 */
const HIVE_VARS = [
  { name: 'hiveHostPort', from: { type: 'context' as const, key: 'hive.hs2HostPort', enabledField: 'hiveDb.enabled', overrideFields: ['hiveDb.hostPort'] } },
  { name: 'hiveTransportMode', from: { type: 'context' as const, key: 'hive.transportMode', enabledField: 'hiveDb.enabled', overrideFields: ['hiveDb.transportMode'] } },
  { name: 'hiveModeBinary', from: { type: 'equals' as const, var: 'hiveTransportMode', value: 'binary', caseInsensitive: true } },
  { name: 'hiveModeHttp', from: { type: 'equals' as const, var: 'hiveTransportMode', value: 'binary', negate: true, caseInsensitive: true } },
];

const HTTP_BINDING = {
  name: 'superset-hive-datasource-http',
  skipIfVarEmpty: ['hiveHostPort', 'hiveModeHttp'],
  targets: [
    { path: 'extraConfigs', op: 'merge', from: { type: 'template', template: {
      'import_datasources.yaml':
        "databases:\n- database_name: Platform Hive (impyla, HTTP)\n  sqlalchemy_uri: impala://${hiveHostPort}/default\n  extra: '{\"engine_params\": {\"connect_args\": {\"auth_mechanism\": \"GSSAPI\", \"kerberos_service_name\": \"hive\", \"use_http_transport\": true, \"http_path\": \"cliservice\", \"use_ssl\": true}}}'\n  impersonate_user: true\n  tables: []\n",
    } } },
  ],
};
const BINARY_BINDING = {
  name: 'superset-hive-datasource-binary',
  skipIfVarEmpty: ['hiveHostPort', 'hiveModeBinary'],
  targets: [
    { path: 'extraConfigs', op: 'merge', from: { type: 'template', template: {
      'import_datasources.yaml':
        "databases:\n- database_name: Platform Hive\n  sqlalchemy_uri: hive://${hiveHostPort}/default\n  extra: '{\"engine_params\": {\"connect_args\": {\"auth\": \"KERBEROS\", \"kerberos_service_name\": \"hive\"}}}'\n  impersonate_user: true\n  tables: []\n",
    } } },
  ],
};
const ALL_BINDINGS = [HTTP_BINDING, BINARY_BINDING] as any[];

const base = { 'hive.hs2HostPort': 'master02.dev01:10001', 'hive.authMode': 'kerberos' };

describe('Superset → platform Hive import_datasources', () => {
  it('http transport → impala:// (impyla) with the "(impyla, HTTP)" label', () => {
    const form = { hiveDb: { enabled: true } };
    const resolved = { ...base, 'hive.transportMode': 'http' };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    expect(varCtx.hiveHostPort).toBe('master02.dev01:10001');
    expect(varCtx.hiveModeHttp).toBe('true');
    expect(varCtx.hiveModeBinary).toBe('');
    const merged: any = {};
    applyBindingTargets(merged, ALL_BINDINGS, {}, form, 'superset', varCtx);
    const yaml = merged.extraConfigs?.['import_datasources.yaml'];
    expect(yaml).toContain('database_name: Platform Hive (impyla, HTTP)');
    expect(yaml).toContain('sqlalchemy_uri: impala://master02.dev01:10001/default');
    expect(yaml).toContain('"use_http_transport": true');
    expect(yaml).toContain('impersonate_user: true');
  });

  it('binary transport → hive:// (PyHive), labelled plain "Platform Hive"', () => {
    const form = { hiveDb: { enabled: true } };
    const resolved = { ...base, 'hive.transportMode': 'binary' };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    expect(varCtx.hiveModeBinary).toBe('true');
    expect(varCtx.hiveModeHttp).toBe('');
    const merged: any = {};
    applyBindingTargets(merged, ALL_BINDINGS, {}, form, 'superset', varCtx);
    const yaml = merged.extraConfigs?.['import_datasources.yaml'];
    expect(yaml).toContain('sqlalchemy_uri: hive://master02.dev01:10001/default');
    expect(yaml).toContain('database_name: Platform Hive\n');
    expect(yaml).toContain('"auth": "KERBEROS"');
    expect(yaml).not.toContain('impala://');
  });

  it('unknown transport → impala:// default (no regression when the context omits the mode)', () => {
    const form = { hiveDb: { enabled: true } };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, base); // no hive.transportMode
    expect(varCtx.hiveModeHttp).toBe('true');
    expect(varCtx.hiveModeBinary).toBe('');
    const merged: any = {};
    applyBindingTargets(merged, ALL_BINDINGS, {}, form, 'superset', varCtx);
    expect(merged.extraConfigs?.['import_datasources.yaml']).toContain('impala://master02.dev01:10001/default');
  });

  it('toggle OFF → no Hive datasource emitted (both bindings skipped)', () => {
    const form = { hiveDb: { enabled: false } };
    const resolved = { ...base, 'hive.transportMode': 'binary' };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    expect(varCtx.hiveHostPort).toBeUndefined();
    const merged: any = {};
    applyBindingTargets(merged, ALL_BINDINGS, {}, form, 'superset', varCtx);
    expect(merged.extraConfigs?.['import_datasources.yaml']).toBeUndefined();
  });

  it('operator override host:port wins over the context value', () => {
    const form = { hiveDb: { enabled: true, hostPort: 'my-hs2:10000' } };
    const resolved = { ...base, 'hive.transportMode': 'http' };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    const merged: any = {};
    applyBindingTargets(merged, ALL_BINDINGS, {}, form, 'superset', varCtx);
    expect(merged.extraConfigs['import_datasources.yaml']).toContain('impala://my-hs2:10000/default');
  });
});
