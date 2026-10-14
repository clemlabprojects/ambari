import * as fs from 'fs';
import * as path from 'path';
import { applyBindingTargets, buildVarContext, deleteAtStr, getAtStr, setAtStr } from '../bindings';
import { seedFieldsFromRelease } from '../seedFromRelease';

/**
 * OpenMetadata "Ranger TagSync" group → chart values, run against the REAL service.json so the
 * wiring cannot drift from what ships. Mirrors the wizard: form defaults, then every binding, then
 * the excludeFromValues fields dropped. The switch must decide everything: off, no ranger.tagSync
 * value reaches the chart (the backend then never touches the cluster's Ranger TagSync); on, the
 * operator's interval, batch size, component type, mapping and endpoint all do.
 */
const svc = JSON.parse(
  fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/OPENMETADATA/service.json'), 'utf8'),
);

const allFields = (fields: any[], out: any[] = []): any[] => {
  for (const f of fields || []) {
    out.push(f);
    if (Array.isArray(f.fields)) allFields(f.fields, out);
  }
  return out;
};
const fields = allFields(svc.form);
const tagSyncGroup = svc.form.find((f: any) => f.name === 'rangerTagSync');

/** The wizard's initial state: every field default (what applyDefaults seeds). */
const defaults = (): any => {
  const state: any = { releaseName: 'om' };
  for (const f of fields) {
    if (f.type !== 'group' && f.defaultValue !== undefined) setAtStr(state, f.name, f.defaultValue);
  }
  return state;
};

/** buildFinalValues: bindings over the form state, then the form-only fields removed. */
const render = (form: any): any => {
  const merged = JSON.parse(JSON.stringify(form));
  const varCtx = buildVarContext(svc.variables, form, {}, {});
  applyBindingTargets(merged, svc.bindings, {}, form, form.releaseName, varCtx);
  for (const f of fields) if (f.excludeFromValues) deleteAtStr(merged, f.name);
  return merged;
};

const withIngress = (state: any, host = 'om.example.com') => {
  state.ingress = { ...(state.ingress || {}), enabled: true, host };
  return state;
};

describe('OpenMetadata Ranger TagSync wiring', () => {
  it('every TagSync variable reads the wizard field of the same name', () => {
    const tagSyncVars = svc.variables.filter((v: any) => /^rangerTagSync/.test(v.name) && v.from?.type === 'form'
      && v.name !== 'rangerTagSyncDeployedEnabled');
    expect(tagSyncVars.map((v: any) => v.name).sort()).toEqual([
      'rangerTagSyncBatchSize', 'rangerTagSyncComponentType', 'rangerTagSyncDownloadInterval',
      'rangerTagSyncEnabled', 'rangerTagSyncEndpoint', 'rangerTagSyncRangerTrinoService',
      'rangerTagSyncToken', 'rangerTagSyncTrinoServiceFqn',
    ]);
    const groupFieldNames = tagSyncGroup.fields.map((f: any) => f.name);
    for (const v of tagSyncVars) {
      expect(v.from.field).toBe(v.name);
      expect(groupFieldNames).toContain(v.name);
    }
  });

  it('switch off: no ranger.tagSync value reaches the chart, even with an ingress host', () => {
    const state = withIngress(defaults());
    state.rangerTagSyncEnabled = false;
    state.rangerTagSyncEndpoint = 'https://dmz.example.com/api';
    const values = render(state);
    expect(getAtStr(values, 'ranger.tagSync')).toBeUndefined();
  });

  it('switch on: renders the interval, batch size, component type and the endpoint from the ingress host', () => {
    const state = withIngress(defaults());
    state.rangerTagSyncEnabled = true;
    const tagSync = render(state).ranger.tagSync;
    expect(tagSync.enabled).toBe(true);
    expect(tagSync.endpoint).toBe('https://om.example.com/api');
    expect(tagSync.downloadIntervalMillis).toBe(230000);
    expect(tagSync.entitiesBatchSize).toBe(10000);
    expect(tagSync.componentTableType).toBe('trino');
    // No made-up Ranger service: the old 'trino-trino-ns' default named a repository that rarely exists.
    expect(tagSync.rangerTrinoServiceName).toBeUndefined();
    expect(tagSync.trinoIngestionServiceFqn).toBeUndefined();
  });

  it('switch on: the operator values reach the chart, mapping included', () => {
    const state = withIngress(defaults());
    Object.assign(state, {
      rangerTagSyncEnabled: true,
      rangerTagSyncTrinoServiceFqn: 'trino-sec',
      rangerTagSyncRangerTrinoService: 'trino-sec-trino-ns',
      rangerTagSyncComponentType: 'hive',
      rangerTagSyncDownloadInterval: 60000,
      rangerTagSyncBatchSize: 500,
    });
    const tagSync = render(state).ranger.tagSync;
    expect(tagSync).toMatchObject({
      enabled: true,
      trinoIngestionServiceFqn: 'trino-sec',
      rangerTrinoServiceName: 'trino-sec-trino-ns',
      componentTableType: 'hive',
      downloadIntervalMillis: 60000,
      entitiesBatchSize: 500,
    });
  });

  it('the endpoint override wins over the address derived from the ingress host', () => {
    const state = withIngress(defaults());
    state.rangerTagSyncEnabled = true;
    state.rangerTagSyncEndpoint = 'http://om.om-ns.svc.cluster.local:8585/api';
    expect(render(state).ranger.tagSync.endpoint).toBe('http://om.om-ns.svc.cluster.local:8585/api');
  });

  it('the endpoint override works without an ingress', () => {
    const state = defaults();
    state.rangerTagSyncEnabled = true;
    state.rangerTagSyncEndpoint = 'https://dmz.example.com/api';
    expect(render(state).ranger.tagSync.endpoint).toBe('https://dmz.example.com/api');
  });

  it('blank fields never overwrite a value already in the release', () => {
    const state = withIngress(defaults());
    state.rangerTagSyncEnabled = true;
    state.rangerTagSyncTrinoServiceFqn = '';
    state.rangerTagSyncRangerTrinoService = '';
    state.ranger = { tagSync: { trinoIngestionServiceFqn: 'trino-sec', rangerTrinoServiceName: 'trino-sec-trino-ns' } };
    const tagSync = render(state).ranger.tagSync;
    expect(tagSync.trinoIngestionServiceFqn).toBe('trino-sec');
    expect(tagSync.rangerTrinoServiceName).toBe('trino-sec-trino-ns');
  });

  it('upgrade with the switch turned off writes enabled=false over the deployed true', () => {
    const state = withIngress(defaults());
    state.ranger = { tagSync: { enabled: true, endpoint: 'https://om.example.com/api' } };
    state.rangerTagSyncEnabled = false;
    expect(render(state).ranger.tagSync.enabled).toBe(false);
  });
});

describe('OpenMetadata Ranger TagSync upgrade seeding', () => {
  const deployedState = (endpoint: string) => {
    const state = withIngress(defaults());
    state.ranger = {
      tagSync: {
        enabled: true,
        endpoint,
        downloadIntervalMillis: 60000,
        entitiesBatchSize: 500,
        componentTableType: 'trino',
        trinoIngestionServiceFqn: 'trino-sec',
        rangerTrinoServiceName: 'trino-sec-trino-ns',
      },
    };
    return state;
  };

  it('fills the switch and the fields from the deployed release, so an unchanged upgrade changes nothing', () => {
    const state = deployedState('https://om.example.com/api');
    const seeded = seedFieldsFromRelease(svc.form, state);
    expect(seeded).toContain('rangerTagSyncEnabled');
    expect(state.rangerTagSyncEnabled).toBe(true);
    expect(state.rangerTagSyncDownloadInterval).toBe(60000);
    expect(state.rangerTagSyncBatchSize).toBe(500);
    expect(state.rangerTagSyncRangerTrinoService).toBe('trino-sec-trino-ns');
    // The derived address stays derived: it keeps following the ingress host.
    expect(state.rangerTagSyncEndpoint).toBeUndefined();
    expect(render(state).ranger.tagSync).toMatchObject(deployedState('https://om.example.com/api').ranger.tagSync);
  });

  it('keeps a deployed endpoint that differs from the ingress-derived one as the override', () => {
    const state = deployedState('https://dmz.example.com/api');
    seedFieldsFromRelease(svc.form, state);
    expect(state.rangerTagSyncEndpoint).toBe('https://dmz.example.com/api');
    expect(render(state).ranger.tagSync.endpoint).toBe('https://dmz.example.com/api');
  });

  it('keeps the switch off, and out of the auto-enable default, for a release with no TagSync value', () => {
    // A first install with the switch off sends no ranger.tagSync value, and neither did any release
    // deployed before the switch was wired: the chart default (off) is what runs. The wizard skips
    // every returned field when it auto-enables a switch for a context exposing Ranger and Atlas.
    const state = withIngress(defaults());
    state.dependencies = { enabled: true };
    const seeded = seedFieldsFromRelease(svc.form, state);
    expect(seeded).toContain('rangerTagSyncEnabled');
    expect(state.rangerTagSyncEnabled).toBe(false);
    expect(getAtStr(render(state), 'ranger.tagSync')).toBeUndefined();
  });

  it('shows the switch off for a release deployed with TagSync off', () => {
    const state = withIngress(defaults());
    state.ranger = { tagSync: { enabled: false } };
    state.rangerTagSyncEnabled = true; // e.g. an auto-enabled default
    seedFieldsFromRelease(svc.form, state);
    expect(state.rangerTagSyncEnabled).toBe(false);
  });
});

describe('Re-register Ranger TagSync source action', () => {
  it('asks for confirmation when the release has TagSync off', () => {
    const action = svc.replayableActions.find((a: any) => a.key === 'om-ranger-tagsync');
    expect(action.warnUnlessValueTrue.path).toBe('ranger.tagSync.enabled');
    expect(action.warnUnlessValueTrue.message).toMatch(/turned off/);
  });
});
