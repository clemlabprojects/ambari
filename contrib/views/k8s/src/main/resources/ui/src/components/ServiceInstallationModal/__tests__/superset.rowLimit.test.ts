import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues } from '../chartValues';

const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/SUPERSET/service.json'), 'utf8'));

const build = (formValues: any, deployed?: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues,
  platform: 'openshift', chartVersion: def.version, retiredValues: def.retiredValues,
  ...(deployed ? { baseValues: deployed } : {}),
});

const rowLimitEntries = (values: any) => (values.extraEnvRaw || []).filter((e: any) => e?.name === 'ROW_LIMIT');

/** The Row Limit of the Superset configuration reaches the pods: KDPS writes it to <release>-superset-settings. */
describe('SUPERSET service.json — Row Limit', () => {
  it('reads ROW_LIMIT from the release settings Secret, optionally', () => {
    const form: any = { releaseName: 'bi', namespace: 'bi' };
    applyFormDefaults(def.form, form);

    expect(rowLimitEntries(build(form))).toEqual([{
      name: 'ROW_LIMIT',
      valueFrom: { secretKeyRef: { name: 'bi-superset-settings', key: 'ROW_LIMIT', optional: true } },
    }]);
  });

  it('deploys a chart that applies it', () => {
    expect(def.version).toBe('0.12.57');
  });
});
