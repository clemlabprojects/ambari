import * as fs from 'fs';
import * as path from 'path';
import { scalingFromValues, usableReleaseValues, valuesWithScaling } from '../TrinoAutoscalingModal';

/** AMBARI-728 — worker autoscaling can be turned on or off, at install and on a deployed Trino release. */
describe('worker scaling of a deployed release', () => {
  it('refuses to redeploy from values that could not be read', () => {
    for (const v of [null, undefined, {}, [], 'x']) expect(usableReleaseValues(v)).toBe(false);
    expect(usableReleaseValues({ server: {} })).toBe(true);
  });

  it('reads the deployed scaling, chart defaults where unset', () => {
    expect(scalingFromValues({ server: { keda: { enabled: true, minReplicaCount: 2, maxReplicaCount: 8 }, workers: 4 } }))
      .toEqual({ enabled: true, workers: 4, minWorkers: 2, maxWorkers: 8 });
    expect(scalingFromValues({})).toEqual({ enabled: false, workers: 2, minWorkers: 1, maxWorkers: 10 });
  });

  it('turning it off sets the fixed worker count and leaves everything else as deployed', () => {
    const deployed = { server: { keda: { enabled: true, triggers: [{ type: 'prometheus' }] }, workers: 2 }, coordinator: { x: 1 } };
    const next = valuesWithScaling(deployed, { enabled: false, workers: 5, minWorkers: 1, maxWorkers: 10 });
    expect(next.server.keda.enabled).toBe(false);
    expect(next.server.workers).toBe(5);
    expect(next.server.keda.triggers).toEqual([{ type: 'prometheus' }]);
    expect(next.coordinator).toEqual({ x: 1 });
    expect(deployed.server.keda.enabled).toBe(true);
  });

  it('turning it on turns metrics collection back on, which the autoscaler reads', () => {
    const next = valuesWithScaling({ server: {}, serviceMonitor: { enabled: false, interval: '30s', coordinator: { enabled: false } } },
      { enabled: true, workers: 2, minWorkers: 1, maxWorkers: 4 });
    expect(next.serviceMonitor).toEqual({ enabled: true, interval: '30s', coordinator: { enabled: true } });
  });

  it('turning it on sets the bounds', () => {
    const next = valuesWithScaling({ server: { workers: 3 } }, { enabled: true, workers: 3, minWorkers: 2, maxWorkers: 6 });
    expect(next.server.keda).toEqual({ enabled: true, minReplicaCount: 2, maxReplicaCount: 6 });
  });
});

describe('Trino wizard autoscaling fields', () => {
  const svc = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../../KDPS/services/TRINO/service.json'), 'utf8'));
  const fields = svc.form.flatMap((g: any) => g.fields || []);
  const field = (name: string) => fields.find((f: any) => f.name === name);

  it('a switch, on by default, writes server.keda.enabled; bounds and thresholds only when on', () => {
    expect(field('server.keda.enabled')).toMatchObject({ type: 'boolean', defaultValue: true });
    for (const name of ['server.keda.minReplicaCount', 'server.keda.maxReplicaCount', 'heap.threshold']) {
      expect(field(name).condition).toEqual({ field: 'server.keda.enabled', value: true });
    }
    expect(field('server.workers').condition).toEqual({ field: 'server.keda.enabled', value: false });
    expect(field('worker.replicas')).toBeUndefined();
  });

  it('metrics collection is a switch on both platforms, and no binding forces it on', () => {
    const monitorFields = svc.form.filter((g: any) => (g.fields || []).some((f: any) => f.name === 'serviceMonitor.enabled'));
    expect(monitorFields.map((g: any) => g.capability).sort()).toEqual(['kubernetes', 'openshift']);
    const smEnable = svc.bindings.find((b: any) => b.name === 'service-monitor-enable');
    expect(smEnable.targets.map((t: any) => t.path)).not.toContain('serviceMonitor.enabled');
    // the chart lets the role settings override the switch: they must follow it
    for (const role of ['coordinator', 'worker']) {
      const target = smEnable.targets.find((t: any) => t.path === `serviceMonitor.${role}.enabled`);
      expect(target.from).toEqual({ type: 'form', field: 'serviceMonitor.enabled' });
    }
  });

  it('no binding forces autoscaling on anymore, and KEDA is installed only when it is on', () => {
    const kedaEnable = svc.bindings.find((b: any) => b.name === 'keda-enable');
    expect(kedaEnable.targets.map((t: any) => t.path)).not.toContain('server.keda.enabled');
    expect(svc.dependencies.keda.onlyWhenValueTrue).toBe('server.keda.enabled');
  });
});
