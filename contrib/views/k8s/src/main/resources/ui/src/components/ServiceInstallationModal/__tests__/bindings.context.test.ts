import { buildVarContext } from '../bindings';

/**
 * Tests for the generic `context` variable source: it lets ANY service consume a value the
 * KDPS engine resolved for the selected Platform Context, with operator form fields able to
 * override the platform value (in declared precedence order).
 */
describe('buildVarContext — context variable source', () => {
  const resolved = {
    'hive.metastoreUri': 'thrift://platform-hms.odp:9083',
    'ranger.rangerUrl': 'https://ranger.odp:6182',
  };

  it('resolves the engine value when no override fields are set', () => {
    const vars = [{ name: 'hiveMetastoreUri', from: { type: 'context' as const, key: 'hive.metastoreUri' } }];
    const ctx = buildVarContext(vars as any, {}, {}, resolved);
    expect(ctx.hiveMetastoreUri).toBe('thrift://platform-hms.odp:9083');
  });

  it('lets an override field win over the platform value', () => {
    const vars = [{ name: 'hiveMetastoreUri', from: { type: 'context' as const, key: 'hive.metastoreUri', overrideFields: ['ui_hive_metastore_uri_override', 'ui_hive_metastore_uri'] } }];
    const form = { ui_hive_metastore_uri_override: 'thrift://my-hms.ns:9083' };
    const ctx = buildVarContext(vars as any, form, {}, resolved);
    expect(ctx.hiveMetastoreUri).toBe('thrift://my-hms.ns:9083');
  });

  it('honours overrideFields precedence order (first non-blank wins)', () => {
    const vars = [{ name: 'hiveMetastoreUri', from: { type: 'context' as const, key: 'hive.metastoreUri', overrideFields: ['ui_hive_metastore_uri_override', 'ui_hive_metastore_uri'] } }];
    // explicit override blank, picker set → picker wins over platform
    const form = { ui_hive_metastore_uri_override: '   ', ui_hive_metastore_uri: 'thrift://picked-hms.ns:9083' };
    const ctx = buildVarContext(vars as any, form, {}, resolved);
    expect(ctx.hiveMetastoreUri).toBe('thrift://picked-hms.ns:9083');
  });

  it('falls back to the platform value when all override fields are blank', () => {
    const vars = [{ name: 'hiveMetastoreUri', from: { type: 'context' as const, key: 'hive.metastoreUri', overrideFields: ['ui_hive_metastore_uri_override', 'ui_hive_metastore_uri'] } }];
    const ctx = buildVarContext(vars as any, { ui_hive_metastore_uri_override: '', ui_hive_metastore_uri: '' }, {}, resolved);
    expect(ctx.hiveMetastoreUri).toBe('thrift://platform-hms.odp:9083');
  });

  it('leaves the variable unset when neither override nor context provides a value', () => {
    const vars = [{ name: 'x', from: { type: 'context' as const, key: 'nonexistent.key' } }];
    const ctx = buildVarContext(vars as any, {}, {}, resolved);
    expect(ctx.x).toBeUndefined();
  });

  it('is a no-op for context vars when no resolvedFields are passed (legacy callers)', () => {
    const vars = [{ name: 'x', from: { type: 'context' as const, key: 'hive.metastoreUri' } }];
    const ctx = buildVarContext(vars as any, {}, {}); // resolvedFields defaulted
    expect(ctx.x).toBeUndefined();
  });

  it('a template variable can consume a context variable', () => {
    const vars = [
      { name: 'hiveMetastoreUri', from: { type: 'context' as const, key: 'hive.metastoreUri' } },
      { name: 'hiveCatalog', template: 'hive.metastore.uri=${hiveMetastoreUri}' },
    ];
    const ctx = buildVarContext(vars as any, {}, {}, resolved);
    expect(ctx.hiveCatalog).toBe('hive.metastore.uri=thrift://platform-hms.odp:9083');
  });
});
