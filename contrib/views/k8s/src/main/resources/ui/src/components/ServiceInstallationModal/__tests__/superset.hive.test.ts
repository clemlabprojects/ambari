import { buildVarContext, applyBindingTargets } from '../bindings';

/**
 * End-to-end (in-process) test of the Superset → platform-Hive wiring: a resolved context +
 * the hiveDb.enabled toggle must drive the `import_datasources.yaml` extraConfig that the chart's
 * init-job imports, with the context-resolved HiveServer2 host:port and impersonation enabled.
 * When the toggle is off, no Hive datasource is emitted.
 */
const HIVE_BINDING = {
  name: 'superset-hive-datasource',
  skipIfVarEmpty: 'hiveHostPort',
  targets: [
    {
      path: 'extraConfigs',
      op: 'merge',
      from: {
        type: 'template',
        template: {
          'import_datasources.yaml':
            "databases:\n- database_name: Platform Hive\n  sqlalchemy_uri: impala://${hiveHostPort}/default\n  extra: '{\"engine_params\": {\"connect_args\": {\"auth_mechanism\": \"GSSAPI\", \"kerberos_service_name\": \"hive\", \"use_http_transport\": true, \"http_path\": \"cliservice\", \"use_ssl\": true}}}'\n  impersonate_user: true\n  tables: []\n",
        },
      },
    },
  ],
};
const HIVE_VARS = [
  { name: 'hiveHostPort', from: { type: 'context' as const, key: 'hive.hs2HostPort', enabledField: 'hiveDb.enabled', overrideFields: ['hiveDb.hostPort'] } },
];
const resolved = { 'hive.hs2HostPort': 'master02.dev01:10001', 'hive.authMode': 'kerberos' };

describe('Superset → platform Hive import_datasources', () => {
  it('toggle ON → emits import_datasources.yaml with the context host:port + impersonation', () => {
    const form = { hiveDb: { enabled: true } };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    expect(varCtx.hiveHostPort).toBe('master02.dev01:10001');
    const merged: any = {};
    applyBindingTargets(merged, HIVE_BINDING ? [HIVE_BINDING as any] : [], {}, form, 'superset', varCtx);
    const yaml = merged.extraConfigs?.['import_datasources.yaml'];
    expect(yaml).toBeDefined();
    expect(yaml).toContain('sqlalchemy_uri: impala://master02.dev01:10001/default');
    expect(yaml).toContain('impersonate_user: true');
    expect(yaml).toContain('"auth_mechanism": "GSSAPI"');
    expect(yaml).toContain('"use_http_transport": true');
  });

  it('toggle OFF → no Hive datasource emitted (binding skipped)', () => {
    const form = { hiveDb: { enabled: false } };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    expect(varCtx.hiveHostPort).toBeUndefined();
    const merged: any = {};
    applyBindingTargets(merged, [HIVE_BINDING as any], {}, form, 'superset', varCtx);
    expect(merged.extraConfigs?.['import_datasources.yaml']).toBeUndefined();
  });

  it('operator override host:port wins over the context value', () => {
    const form = { hiveDb: { enabled: true, hostPort: 'my-hs2:10000' } };
    const varCtx = buildVarContext(HIVE_VARS as any, form, {}, resolved);
    const merged: any = {};
    applyBindingTargets(merged, [HIVE_BINDING as any], {}, form, 'superset', varCtx);
    expect(merged.extraConfigs['import_datasources.yaml']).toContain('impala://my-hs2:10000/default');
  });
});
