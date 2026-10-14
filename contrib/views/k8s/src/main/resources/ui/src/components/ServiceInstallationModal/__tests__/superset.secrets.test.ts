import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues, seedFieldsFromValues } from '../chartValues';
import { deepMerge } from '../bindings';

/**
 * The Superset SECRET_KEY, run against the REAL SUPERSET/service.json. It goes to extraSecretEnv, which the chart keeps
 * in the <fullname>-env Secret, never to extraEnv, which the chart writes into every pod spec where anyone allowed to
 * read Deployments could read it (and sign session cookies or decrypt the stored database passwords with it). The
 * field itself stays out of the values, and an upgrade must give the release back the key it runs with.
 */
const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/SUPERSET/service.json'), 'utf8'));
const group = (name: string) => def.form.find((g: any) => g.name === name);
const field = (g: string, name: string) => group(g).fields.find((f: any) => f.name === name);

const build = (formValues: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues,
  retiredValues: def.retiredValues, chartVersion: def.version,
});

/** The form a new install starts from, with the key typed in. */
const install = (secretKey: string): any => {
  const values: any = { releaseName: 'superset', namespace: 'analytics' };
  applyFormDefaults(def.form, values);
  values.superset = { ...(values.superset || {}), secretKey };
  return values;
};

describe('Superset SECRET_KEY', () => {
  it('is sent in extraSecretEnv only', () => {
    const values = build(install('Key-From-The-Form-1'));
    expect(values.extraSecretEnv).toEqual({ SUPERSET_SECRET_KEY: 'Key-From-The-Form-1' });
    expect(values.extraEnv?.SUPERSET_SECRET_KEY).toBeUndefined();
    expect(values.superset?.secretKey).toBeUndefined();
    expect(JSON.stringify(values.extraEnv || {})).not.toContain('Key-From-The-Form-1');
  });

  it('the field is a required password kept out of the values', () => {
    const f = field('superset', 'superset.secretKey');
    expect(f.type).toBe('password');
    expect(f.required).toBe(true);
    expect(f.excludeFromValues).toBe(true);
  });

  it('an upgrade of a release deployed by this version keeps the key it runs with', () => {
    const deployed = build(install('Key-In-Use-2'));
    // ServiceWizardPage: defaults, then the release's values, then the fields filled back from them.
    const form: any = { releaseName: 'superset', namespace: 'analytics' };
    applyFormDefaults(def.form, form);
    deepMerge(form, deployed);
    seedFieldsFromValues(def.form, form);
    expect(form.superset.secretKey).toBe('Key-In-Use-2');
    expect(build(form).extraSecretEnv.SUPERSET_SECRET_KEY).toBe('Key-In-Use-2');
  });

  it('an upgrade of a release deployed before moves the key out of extraEnv, unchanged', () => {
    // What an earlier version of the definition deployed: the key in extraEnv and in superset.secretKey.
    const deployed = {
      superset: { secretKey: 'Old-Key-3', openstreetmapServerUrl: 'https://tile.example.com' },
      extraEnv: { SUPERSET_SECRET_KEY: 'Old-Key-3', OPENSTREETMAP_SERVER_URL: 'https://tile.example.com' },
    };
    const form: any = { releaseName: 'superset', namespace: 'analytics' };
    applyFormDefaults(def.form, form);
    deepMerge(form, deployed);
    seedFieldsFromValues(def.form, form);

    const values = build(form);
    expect(values.extraSecretEnv.SUPERSET_SECRET_KEY).toBe('Old-Key-3');
    expect(values.extraEnv).toEqual({ OPENSTREETMAP_SERVER_URL: 'https://tile.example.com' });
    expect(Object.keys(def.retiredValues).filter(k => !k.startsWith('_')))
      .toEqual(['extraEnv.SUPERSET_SECRET_KEY', 'superset-env/SUPERSET_ADMIN_PASSWORD']);
  });

  it('a key that only extraEnv still holds is found too, and a key typed on the upgrade wins', () => {
    const form: any = { extraEnv: { SUPERSET_SECRET_KEY: 'Legacy-Key-4' } };
    seedFieldsFromValues(def.form, form);
    expect(form.superset.secretKey).toBe('Legacy-Key-4');

    const typed: any = { superset: { secretKey: 'Typed-Key-5' }, extraSecretEnv: { SUPERSET_SECRET_KEY: 'Old-Key-6' } };
    seedFieldsFromValues(def.form, typed);
    expect(typed.superset.secretKey).toBe('Typed-Key-5');
  });
});

describe('Superset datasource credentials', () => {
  // Only the chart that keeps the import files in a Secret does so: the help says which, and the server refuses an
  // older chart for these fields (secretChartVersions).
  it('the help texts say where the passwords are kept, and from which chart', () => {
    for (const help of [field('hiveDatabase', 'hiveDb.ldapPassword').help,
      def.form.find((f: any) => f.name === 'ui_trino_svc_password').help]) {
      expect(help).toContain('chart 0.12.49 and later keep in a Kubernetes Secret');
    }
    for (const path of ['hiveDb.ldapPassword', 'ui_trino_svc_password']) {
      expect(def.secretChartVersions[path].range).toBe('>=0.12.49');
    }
  });
});
