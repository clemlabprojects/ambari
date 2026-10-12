import yaml from 'yaml';
import { applyFormDefaults, buildChartValues, excludedPaths, hiddenPaths, removeRetiredValues, seedFieldsFromValues } from '../chartValues';

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

/** A form with settings for one platform only, as the services declare them. */
const platformForm: any[] = [
  { name: 'replicas', label: 'Replicas', type: 'number', defaultValue: 1 },
  {
    name: 'monitoringGeneric', label: 'Monitoring', type: 'group', capability: 'kubernetes', fields: [
      { name: 'monitoring.namespace', label: 'Monitoring namespace', type: 'string', defaultValue: 'monitoring' },
      { name: 'metrics.enabled', label: 'Collect metrics', type: 'boolean', defaultValue: true },
      { name: 'metrics.interval', label: 'Scrape interval', type: 'string', defaultValue: '30s' },
    ],
  },
  {
    name: 'monitoringOpenShift', label: 'Monitoring', type: 'group', capability: 'openshift', fields: [
      { name: 'metrics.enabled', label: 'Collect metrics', type: 'boolean', defaultValue: true },
    ],
  },
  {
    name: 'openshiftOptions', label: 'OpenShift', type: 'group', capability: 'openshift', fields: [
      { name: 'runAsUser', label: 'UID', type: 'number' },
      { name: 'search.port', label: 'Search port', type: 'number', defaultValue: 9200 },
    ],
  },
  {
    name: 'ingressConfig', label: 'Ingress', type: 'group', fields: [
      { name: 'ingress.host', label: 'Host', type: 'string', defaultValue: '' },
      { name: 'ingress.tuning', label: 'Proxy buffer', type: 'string', capability: 'kubernetes', defaultValue: '16k' },
      {
        name: 'ingress.ingressClassName', label: 'Ingress class', type: 'string', capability: 'kubernetes',
        keepWhenHidden: true, defaultValue: 'nginx',
      },
    ],
  },
];

describe('applyFormDefaults', () => {
  it('seeds the default of every field, hidden ones and escaped dots included', () => {
    const values: any = { releaseName: 'svc' };
    const escaped = { name: 'node\\.data-dir', label: 'Data', type: 'string', defaultValue: '/d' };
    applyFormDefaults([...platformForm, escaped], values);
    expect(values).toEqual({
      releaseName: 'svc', replicas: 1,
      monitoring: { namespace: 'monitoring' },
      metrics: { enabled: true, interval: '30s' },
      search: { port: 9200 },
      ingress: { host: '', tuning: '16k', ingressClassName: 'nginx' },
      'node.data-dir': '/d',
    });
  });
});

describe('fields hidden on the platform', () => {
  const start = () => {
    const values: any = { releaseName: 'svc' };
    applyFormDefaults(platformForm, values);
    return values;
  };

  it('lists the fields of the other platform, except those still shown elsewhere or kept', () => {
    expect(hiddenPaths(platformForm, 'openshift'))
      .toEqual(['monitoring.namespace', 'metrics.interval', 'ingress.tuning']);
    expect(hiddenPaths(platformForm, 'kubernetes')).toEqual(['runAsUser', 'search.port']);
  });

  it('platform not detected yet: OpenShift fields are hidden, Kubernetes fields shown, as on screen', () => {
    expect(hiddenPaths(platformForm, undefined)).toEqual(['runAsUser', 'search.port']);
  });

  it('a form without platform settings hides nothing', () => {
    expect(hiddenPaths(form, 'openshift')).toEqual([]);
    expect(hiddenPaths(undefined, 'openshift')).toEqual([]);
  });

  it('leaves the values of hidden fields out of the chart values', () => {
    const openshift = buildChartValues({ form: platformForm, formValues: start(), platform: 'openshift' });
    expect(openshift).toEqual({
      replicas: 1, monitoring: {}, metrics: { enabled: true }, search: { port: 9200 },
      ingress: { host: '', ingressClassName: 'nginx' },
    });
    const kubernetes = buildChartValues({ form: platformForm, formValues: start(), platform: 'kubernetes' });
    expect(kubernetes).toEqual({
      replicas: 1, monitoring: { namespace: 'monitoring' }, metrics: { enabled: true, interval: '30s' }, search: {},
      ingress: { host: '', tuning: '16k', ingressClassName: 'nginx' },
    });
  });

  it('a binding still sets a hidden path, and still reads the hidden field', () => {
    const platformBindings: any[] = [
      {
        skipIfVarEmpty: 'isOpenShift',
        targets: [{ path: 'monitoring.namespace', op: 'set', value: 'openshift-monitoring' }],
      },
      { targets: [{ path: 'scrape.interval', op: 'set', from: { type: 'form', field: 'metrics.interval' } }] },
    ];
    const values = buildChartValues({
      form: platformForm, bindings: platformBindings, formValues: start(), platform: 'openshift',
    });
    expect(values.monitoring).toEqual({ namespace: 'openshift-monitoring' });
    expect(values.scrape).toEqual({ interval: '30s' });
    expect(values.metrics).toEqual({ enabled: true });
  });

  it('a value typed in the YAML editor is sent even for a hidden field', () => {
    const values = buildChartValues({
      form: platformForm, formValues: start(), platform: 'openshift', editorValues: { metrics: { interval: '1m' } },
    });
    expect(values.metrics).toEqual({ enabled: true, interval: '1m' });
  });
});

describe('values an earlier service definition wrote', () => {
  it('retired values are left out of what the wizard sends; comments are not paths', () => {
    const values = buildChartValues({
      form, bindings, formValues: { ...formValues(), extraEnv: { KEY: 'old', KEEP: 'yes' }, top: 'x', _comment: 'kept' },
      retiredValues: { _comment: 'why', 'extraEnv.KEY': 'moved', top: 'gone' },
    });
    expect(values.extraEnv).toEqual({ KEEP: 'yes' });
    expect(values.top).toBeUndefined();
    expect(values._comment).toBe('kept');
  });

  it('a retired line is removed from its list, the other lines stay', () => {
    const values: any = {
      props: ['secret.key=literal', ' secret.key=indented', 'other=1', 42],
      nested: { props: ['a=1'] },
    };
    removeRetiredValues(values, {
      props: { reason: 'moved to a Secret', linesStartingWith: ['secret.key='] },
      'nested.props': { linesStartingWith: ['b='] },
      'absent.list': { linesStartingWith: ['x='] },
    });
    expect(values.props).toEqual(['other=1', 42]);
    expect(values.nested.props).toEqual(['a=1']);
    expect(values.absent).toBeUndefined();
    removeRetiredValues(values, undefined);
    expect(values.props).toEqual(['other=1', 42]);
  });

  it('a blank field is filled from the first value path that holds something, groups included', () => {
    const seeded = [{
      name: 'g', type: 'group', fields: [
        { name: 'app.key', type: 'password', excludeFromValues: true, seedFromValues: ['secretEnv.KEY', 'env.KEY'] },
      ],
    }];
    const fromSecond: any = { secretEnv: { KEY: '  ' }, env: { KEY: 'from-env' } };
    seedFieldsFromValues(seeded, fromSecond);
    expect(fromSecond.app.key).toBe('from-env');

    const typed: any = { app: { key: 'typed' }, secretEnv: { KEY: 'stored' } };
    seedFieldsFromValues(seeded, typed);
    expect(typed.app.key).toBe('typed');

    const nothing: any = {};
    seedFieldsFromValues(seeded, nothing);
    expect(nothing).toEqual({});
  });
});
