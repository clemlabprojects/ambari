import yaml from 'yaml';
import { buildChartValues, excludedPaths } from '../chartValues';

/**
 * The values the wizard previews and deploys: form, bindings, exclusions and wizard keys removed, the YAML editor
 * merged over the result, and blank fields resolved last, for the chart version of the deploy.
 */
const form = [
  { name: 'releaseName', label: 'Release name', type: 'string' },
  { name: 'ui_choice', label: 'Wizard-only choice', type: 'string', excludeFromValues: true },
  {
    name: 'coordinatorResources', label: 'Coordinator resources', type: 'group', fields: [
      { name: 'coordinator.resources.limits.memory', label: 'Memory limit', type: 'string' },
      {
        name: 'coordinator.jvm.maxHeapSize', label: 'Java heap size (optional)', type: 'string', omitWhenBlank: true,
        resourceCheck: {
          kind: 'javaHeap' as const, limitField: 'coordinator.resources.limits.memory', blankSizedByChartFrom: '1.43.15',
        },
      },
      { name: 'coordinator.ui_note', label: 'Note', type: 'string', excludeFromValues: true },
    ],
  },
];
const bindings = [{ targets: [{ path: 'nameOverride', op: 'set' as const, from: { type: 'form' as const, field: 'releaseName' } }] }];

const formValues = (heap = '', memory = '8Gi', version = '1.43.14'): any => ({
  releaseName: 'trino', namespace: 'analytics', version, repoId: 'clemlab', securityProfile: 'default',
  kerberos: { enabled: false }, tls: {}, oidc: {}, ui_choice: 'x',
  coordinator: { resources: { limits: { memory } }, jvm: { maxHeapSize: heap }, ui_note: 'n' },
});

/** What the wizard sends: the preview when the editor is untouched, otherwise the editor merged over the form. */
const build = (values: any, editorValues: any = null) => buildChartValues({
  form, bindings, formValues: values, editorValues, chartVersion: values.version,
});

/** The operator opens the editor on the preview and types over it: the wizard parses the text typed. */
const editPreview = (values: any, ...edits: [string | RegExp, string][]): any => {
  let text = yaml.stringify(build(values));
  for (const [from, to] of edits) {
    expect(text).toMatch(from);
    text = text.replace(from, to);
  }
  return yaml.parse(text);
};

describe('buildChartValues', () => {
  it('lists the excluded fields of every group', () => {
    expect(excludedPaths(form)).toEqual(['ui_choice', 'coordinator.ui_note']);
    expect(excludedPaths(undefined)).toEqual([]);
  });

  it('keeps only chart values: excluded fields and the wizard keys are left out', () => {
    const values = build(formValues('6G', '8Gi', '1.43.15'));
    expect(values).toEqual({
      nameOverride: 'trino',
      coordinator: { resources: { limits: { memory: '8Gi' } }, jvm: { maxHeapSize: '6G' } },
    });
  });

  it('does not change the form values it is given', () => {
    const values = formValues();
    build(values, { coordinator: { jvm: { maxHeapSize: '5G' } } });
    expect(values).toEqual(formValues());
  });

  it('fills a blank heap from the memory limit for an older chart, and leaves it to a newer one', () => {
    expect(build(formValues('', '8Gi', '1.43.14')).coordinator.jvm).toEqual({ maxHeapSize: '6G' });
    expect(build(formValues('', '8Gi', '1.43.15')).coordinator.jvm).toEqual({});
  });

  describe('the YAML editor on an upgrade that keeps an older chart', () => {
    it('shows the heap the wizard computed', () => {
      expect(yaml.stringify(build(formValues()))).toContain('maxHeapSize: 6G');
    });

    it('treats a heap cleared in the editor like a blank field, sized from the limit sent', () => {
      for (const cleared of ['maxHeapSize: ""', 'maxHeapSize:', "maxHeapSize: ''"]) {
        const doc = editPreview(formValues(), ['maxHeapSize: 6G', cleared], ['memory: 8Gi', 'memory: 4Gi']);
        expect(build(formValues(), doc).coordinator).toEqual({
          resources: { limits: { memory: '4Gi' } }, jvm: { maxHeapSize: '3G' },
        });
      }
    });

    it('sizes the heap from the limit lowered in the editor when the key was deleted there', () => {
      const doc = editPreview(formValues(), [/ *maxHeapSize: 6G\n/, ''], ['memory: 8Gi', 'memory: 4Gi']);
      expect(doc.coordinator.jvm).toBeNull();
      expect(build(formValues(), doc).coordinator.jvm).toEqual({ maxHeapSize: '3G' });
    });

    it('keeps a heap written in the editor', () => {
      const doc = editPreview(formValues(), ['maxHeapSize: 6G', 'maxHeapSize: 5G']);
      expect(build(formValues(), doc).coordinator.jvm).toEqual({ maxHeapSize: '5G' });
    });

    it('leaves a heap cleared in the editor to a chart that sizes it from the limit', () => {
      const values = formValues('', '8Gi', '1.43.15');
      const doc = editPreview(values, ['jvm: {}', 'jvm:\n    maxHeapSize: ""']);
      expect(doc.coordinator.jvm).toEqual({ maxHeapSize: '' });
      expect(build(values, doc).coordinator.jvm).toEqual({});
    });
  });
});
