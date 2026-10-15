import * as fs from 'fs';
import * as path from 'path';
import { applyFormDefaults, buildChartValues } from '../chartValues';

/**
 * Without a process count Celery starts one worker process per CPU of the node, which a container sees whole: 128 on
 * a 128-thread bare-metal node, and the worker pod is killed for memory. The wizard sends a count, and a memory limit
 * that goes with it, to the chart (0.12.52 or later passes the count as --concurrency).
 */
const def = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../../../../KDPS/services/SUPERSET/service.json'), 'utf8'));

const build = (formValues: any) => buildChartValues({
  form: def.form, bindings: def.bindings, variables: def.variables, formValues,
  platform: 'openshift', chartVersion: def.version, retiredValues: def.retiredValues,
});

describe('SUPERSET service.json — Celery worker processes', () => {
  it('sends 4 worker processes and a 2Gi memory limit by default', () => {
    const form: any = { releaseName: 'superset', namespace: 'bi' };
    applyFormDefaults(def.form, form);
    const values = build(form);
    expect(values.supersetWorker.concurrency).toBe(4);
    expect(values.supersetWorker.resources.limits.memory).toBe('2Gi');
  });

  it('sends what the operator sets', () => {
    const form: any = { releaseName: 'superset', namespace: 'bi' };
    applyFormDefaults(def.form, form);
    form.supersetWorker.concurrency = 8;
    form.supersetWorker.resources.limits.memory = '4Gi';
    const values = build(form);
    expect(values.supersetWorker.concurrency).toBe(8);
    expect(values.supersetWorker.resources.limits.memory).toBe('4Gi');
  });

  it('deploys a chart that reads the process count', () => {
    expect(def.version).toBe('0.12.52');
  });
});
