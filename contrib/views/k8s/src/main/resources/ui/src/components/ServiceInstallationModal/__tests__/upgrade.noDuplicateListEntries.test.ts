import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues } from '../chartValues';
import { deepMerge, pushUniqueValues } from '../bindings';

/**
 * An upgrade starts from the values the release runs with (the wizard merges them into the form), and the bindings
 * then run again over them. Bindings that append to a list must not add what the list already holds: Trino got its
 * JMX exporter -javaagent option twice, and the second exporter failed the coordinator and workers at start.
 */
const load = (svc: string) => JSON.parse(fs.readFileSync(
  path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));

const build = (def: any, formValues: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues,
  platform: 'openshift', chartVersion: def.version, retiredValues: def.retiredValues,
});

/** Every list in the values that holds the same entry twice, as "path: entry". */
const duplicates = (v: any, at = ''): string[] => {
  if (Array.isArray(v)) {
    const seen = new Set<string>();
    const dups = v.map(e => JSON.stringify(e)).filter(k => seen.has(k) || !seen.add(k)).map(k => `${at}: ${k}`);
    return [...dups, ...v.flatMap((e, i) => duplicates(e, `${at}[${i}]`))];
  }
  if (v && typeof v === 'object') return Object.keys(v).flatMap(k => duplicates(v[k], at ? `${at}.${k}` : k));
  return [];
};

describe.each(['TRINO', 'SUPERSET', 'OPENMETADATA'])('%s — install then upgrade from the wizard', (svc) => {
  const def = load(svc);
  const form: any = { releaseName: 'rel', namespace: 'proj' };
  applyFormDefaults(def.form, form);
  const installed = build(def, JSON.parse(JSON.stringify(form)));

  /** The wizard's upgrade form: the defaults with the deployed values merged over them. */
  const upgradeForm = () => {
    const f = JSON.parse(JSON.stringify(form));
    deepMerge(f, JSON.parse(JSON.stringify(installed)));
    return f;
  };

  it('a first upgrade adds no list entry twice', () => {
    expect(duplicates(build(def, upgradeForm()))).toEqual([]);
  });

  it('repeated upgrades give the same values as the first one', () => {
    const once = build(def, upgradeForm());
    const f = JSON.parse(JSON.stringify(form));
    deepMerge(f, JSON.parse(JSON.stringify(once)));
    expect(build(def, f)).toEqual(once);
  });
});

describe('TRINO — the JMX exporter agent', () => {
  const def = load('TRINO');
  const agents = (v: any, role: string) =>
    (v?.[role]?.additionalJVMConfig || []).filter((s: any) => String(s).includes('-javaagent'));

  it('is configured once on the coordinator and the workers, at install and after an upgrade', () => {
    const form: any = { releaseName: 'trino', namespace: 'proj' };
    applyFormDefaults(def.form, form);
    const installed = build(def, JSON.parse(JSON.stringify(form)));
    const f = JSON.parse(JSON.stringify(form));
    deepMerge(f, JSON.parse(JSON.stringify(installed)));
    const upgraded = build(def, f);
    for (const role of ['coordinator', 'worker']) {
      expect(agents(installed, role)).toHaveLength(1);
      expect(agents(upgraded, role)).toEqual(agents(installed, role));
    }
  });
});

describe('pushUniqueValues', () => {
  it('appends new entries and skips those already present, objects compared whatever their key order', () => {
    const arr: any[] = ['-Xmx4G', { type: 'cpu', metadata: { value: '80' } }];
    pushUniqueValues(arr, '-Xmx4G');
    pushUniqueValues(arr, { metadata: { value: '80' }, type: 'cpu' });
    pushUniqueValues(arr, ['-XX:+UseG1GC', '-XX:+UseG1GC']);
    expect(arr).toEqual(['-Xmx4G', { type: 'cpu', metadata: { value: '80' } }, '-XX:+UseG1GC']);
  });
});
