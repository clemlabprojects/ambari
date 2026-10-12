import * as fs from 'fs';
import * as path from 'path';
import yaml from 'yaml';
import { applyBindingTargets, setAtStr } from '../bindings';
import { applyBlankValues } from '../blankValues';
import { buildChartValues } from '../chartValues';
import { resourceCheckErrors } from '../resourceChecks';

/**
 * Trino memory sizing in the wizard, run against the REAL service.json: the pods request what they are limited to
 * (Guaranteed QoS), the Java heap stays below the memory limit, and a blank heap is left to the chart, which sizes
 * it from the limit, or filled with 75% of the limit when the chart deployed is too old to do so.
 */
const svc = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'), 'utf8')
);
const group = (name: string) => svc.form.find((g: any) => g.name === name);
const field = (g: string, name: string) => group(g).fields.find((f: any) => f.name === name);

/** The values the wizard starts from: every field's default, nested the way the form keeps them. */
const defaults = (): any => {
  const values: any = {};
  const walk = (fields: any[]) => fields.forEach((f: any) => {
    if (Array.isArray(f.fields)) walk(f.fields);
    else if (f.defaultValue !== undefined) setAtStr(values, f.name, JSON.parse(JSON.stringify(f.defaultValue)));
  });
  walk(svc.form);
  return values;
};

describe.each([
  ['coordinator', 'coordinatorResources'],
  ['worker', 'workerResources'],
])('Trino %s resources', (role, groupName) => {
  it('requests equal limits by default, so the pods are in the Guaranteed QoS class', () => {
    for (const resource of ['cpu', 'memory']) {
      const request = field(groupName, `${role}.resources.requests.${resource}`);
      const limit = field(groupName, `${role}.resources.limits.${resource}`);
      expect(request.defaultValue).toBe(limit.defaultValue);
      expect(request.resourceCheck).toEqual({
        kind: `${resource}Request`, limitField: `${role}.resources.limits.${resource}`,
      });
    }
    expect(field(groupName, `${role}.resources.limits.memory`).defaultValue).toBe('8Gi');
  });

  it('offers an optional Java heap right after the memory limit, blank by default', () => {
    const names = group(groupName).fields.map((f: any) => f.name);
    expect(names.indexOf(`${role}.jvm.maxHeapSize`)).toBe(names.indexOf(`${role}.resources.limits.memory`) + 1);
    const heap = field(groupName, `${role}.jvm.maxHeapSize`);
    expect(heap.type).toBe('string');
    expect(heap.defaultValue).toBe('');
    expect(heap.omitWhenBlank).toBe(true);
    expect(heap.resourceCheck).toEqual({
      kind: 'javaHeap', limitField: `${role}.resources.limits.memory`, blankSizedByChartFrom: '1.43.15',
    });
    expect(heap.help).toContain('70-80% of the memory limit');
    expect(heap.label).toMatch(/\(optional\)$/);
  });
});

describe('Trino memory values sent to the chart', () => {
  it('pins the chart that sizes a blank heap from the memory limit', () => {
    expect(svc.version).toBe('1.43.15');
  });

  it('the defaults pass every check and leave the heap to the chart', () => {
    const values = defaults();
    expect(resourceCheckErrors(svc.form, values)).toEqual([]);
    applyBlankValues(svc.form, values, svc.version);
    expect(values.coordinator.jvm?.maxHeapSize).toBeUndefined();
    expect(values.worker.jvm?.maxHeapSize).toBeUndefined();
    expect(values.coordinator.resources).toEqual({
      requests: { cpu: '2', memory: '8Gi' }, limits: { cpu: '2', memory: '8Gi' },
    });
  });

  it('an explicit heap is sent, and refused when it is too close to the memory limit', () => {
    const values = defaults();
    values.worker.jvm.maxHeapSize = '6G';
    expect(resourceCheckErrors(svc.form, values)).toEqual([]);
    applyBlankValues(svc.form, values, svc.version);
    expect(values.worker.jvm.maxHeapSize).toBe('6G');

    values.coordinator.jvm.maxHeapSize = '8G';
    const errors = resourceCheckErrors(svc.form, values);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatch(/^Coordinator resources - Java heap size \(optional\): A 8G heap leaves too little/);
  });

  it('lowering only the memory limit is caught before Kubernetes refuses the pod', () => {
    const values = defaults();
    values.worker.resources.limits.memory = '4Gi';
    values.worker.jvm.maxHeapSize = '6G';
    const errors = resourceCheckErrors(svc.form, values);
    expect(errors).toHaveLength(2);
    expect(errors[0]).toContain('Memory request per worker: The memory request (8Gi) cannot be higher than the memory limit (4Gi)');
    expect(errors[1]).toContain('Java heap size per worker (optional): A 6G heap');
  });

  // Upgrade / Config keeps the chart version the release runs, and releases deployed before this fix run 1.43.14,
  // whose own default is -Xmx8G whatever the limit: a blank heap must not be left to it.
  it('an upgrade that keeps an older chart gets 75% of the memory limit for a blank heap', () => {
    const values = defaults();
    applyBlankValues(svc.form, values, '1.43.14');
    expect(values.coordinator.jvm.maxHeapSize).toBe('6G');
    expect(values.worker.jvm.maxHeapSize).toBe('6G');
    expect(resourceCheckErrors(svc.form, values)).toEqual([]);
  });

  it('lowering the memory limit on an older chart lowers the heap with it', () => {
    const values = defaults();
    values.worker.resources.limits.memory = '4Gi';
    values.worker.resources.requests.memory = '4Gi';
    applyBlankValues(svc.form, values, '1.43.14');
    expect(values.worker.jvm.maxHeapSize).toBe('3G');
    expect(values.coordinator.jvm.maxHeapSize).toBe('6G');
    expect(resourceCheckErrors(svc.form, values)).toEqual([]);
  });

  it('an upgrade to the pinned chart leaves the heap to the chart again', () => {
    const values = defaults();
    applyBlankValues(svc.form, values, '1.43.15');
    expect(values.coordinator.jvm?.maxHeapSize).toBeUndefined();
    expect(values.worker.jvm?.maxHeapSize).toBeUndefined();
  });

  // The Upgrade / Config wizard on a release still on 1.43.14, through the whole pipeline the wizard runs: the YAML
  // editor shows the heap computed for the older chart, and an operator who clears it there (as the field's help
  // and the check's own message invite) must not send the empty heap 1.43.14 would render as a bare -Xmx.
  describe('the wizard pipeline on an upgrade that keeps chart 1.43.14', () => {
    const upgrade = (): any => ({ ...defaults(), releaseName: 'trino', namespace: 'analytics', version: '1.43.14' });
    const build = (formValues: any, editorValues: any = null) => buildChartValues({
      form: svc.form, bindings: svc.bindings, variables: svc.variables, formValues, editorValues,
      platform: 'openshift', chartVersion: formValues.version,
    });
    const editor = (formValues: any) => yaml.parse(yaml.stringify(build(formValues)));

    it('shows the heap the chart would otherwise get wrong in the YAML editor', () => {
      const doc = editor(upgrade());
      expect(doc.coordinator.jvm.maxHeapSize).toBe('6G');
      expect(doc.worker.jvm.maxHeapSize).toBe('6G');
    });

    it('sizes a heap cleared in the YAML editor from the limit lowered there', () => {
      const doc = editor(upgrade());
      doc.coordinator.jvm.maxHeapSize = '';          // maxHeapSize: ""
      doc.worker.jvm.maxHeapSize = null;             // maxHeapSize:
      doc.worker.resources.limits.memory = '4Gi';
      doc.worker.resources.requests.memory = '4Gi';
      const values = build(upgrade(), doc);
      expect(values.coordinator.jvm.maxHeapSize).toBe('6G');
      expect(values.worker.jvm.maxHeapSize).toBe('3G');
      expect(resourceCheckErrors(svc.form, values, '1.43.14')).toEqual([]);
    });

    it('sizes the heap from the limit lowered in the YAML editor when the key was deleted there', () => {
      const doc = editor(upgrade());
      delete doc.worker.jvm.maxHeapSize;
      doc.worker.resources.limits.memory = '4Gi';
      doc.worker.resources.requests.memory = '4Gi';
      const values = build(upgrade(), doc);
      expect(values.worker.jvm.maxHeapSize).toBe('3G');
      expect(resourceCheckErrors(svc.form, values, '1.43.14')).toEqual([]);
    });

    it('still refuses a heap kept in the YAML editor above a limit lowered there', () => {
      const doc = editor(upgrade());
      doc.worker.resources.limits.memory = '4Gi';
      doc.worker.resources.requests.memory = '4Gi';
      const errors = resourceCheckErrors(svc.form, build(upgrade(), doc), '1.43.14');
      expect(errors).toHaveLength(1);
      expect(errors[0]).toContain('Java heap size per worker (optional): A 6G heap');
    });

    it('leaves a heap cleared in the YAML editor to the chart once the upgrade moves to the pinned chart', () => {
      const formValues = { ...upgrade(), version: svc.version };
      const doc = editor(formValues);
      doc.coordinator.jvm = { maxHeapSize: '' };
      const values = build(formValues, doc);
      expect(values.coordinator.jvm?.maxHeapSize).toBeUndefined();
      expect(resourceCheckErrors(svc.form, values, svc.version)).toEqual([]);
    });
  });

  it("the chart's own init containers request what they are limited to", () => {
    const binding = svc.bindings.find((b: any) => b.name === 'init-container-resources');
    const merged: any = {};
    applyBindingTargets(merged, [binding], {}, defaults(), 'trino', {});
    expect(merged.initContainerResources.requests).toEqual(merged.initContainerResources.limits);
    expect(merged.initContainerResources.limits).toEqual({ cpu: '100m', memory: '128Mi' });
  });
});
