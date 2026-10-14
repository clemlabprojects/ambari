import * as fs from 'fs';
import * as path from 'path';
import { offeredOnPlatform } from '../catalogPlatforms';

// The catalog hides a service whose definition leaves out the cluster's platform, once the capability probe has said
// which platform that is. No shipped service needs it today, but a definition may declare it for a chart whose pods
// OpenShift's default security policy would reject.
const SERVICES = path.join(__dirname, '../../../../KDPS/services');
const definition = (key: string) => JSON.parse(fs.readFileSync(path.join(SERVICES, key, 'service.json'), 'utf8'));
const catalog: string[] = JSON.parse(fs.readFileSync(path.join(SERVICES, 'catalog.json'), 'utf8'));

describe('offeredOnPlatform', () => {
  it('offers a service that names no platform everywhere', () => {
    expect(offeredOnPlatform({}, 'openshift')).toBe(true);
    expect(offeredOnPlatform({ platforms: [] }, 'openshift')).toBe(true);
    expect(offeredOnPlatform({}, 'kubernetes')).toBe(true);
    expect(offeredOnPlatform(undefined, 'openshift')).toBe(true);
  });

  it('hides a plain-Kubernetes service on a cluster confirmed to be OpenShift', () => {
    const kubernetesOnly = { platforms: ['kubernetes'] };
    expect(offeredOnPlatform(kubernetesOnly, 'openshift')).toBe(false);
    expect(offeredOnPlatform(kubernetesOnly, 'kubernetes')).toBe(true);
  });

  it('keeps every service listed while the platform is unknown', () => {
    // The probe is loading or failed: nothing is hidden on a guess, and the server still refuses the deploy.
    expect(offeredOnPlatform({ platforms: ['kubernetes'] }, undefined)).toBe(true);
    expect(offeredOnPlatform({ platforms: ['openshift'] }, undefined)).toBe(true);
  });

  it('applies the same rule to the other platform', () => {
    expect(offeredOnPlatform({ platforms: ['openshift'] }, 'kubernetes')).toBe(false);
    expect(offeredOnPlatform({ platforms: [' OpenShift '] }, 'openshift')).toBe(true);
  });
});

describe('the shipped catalog', () => {
  it('does not offer SQL Assistant or dbt', () => {
    expect(catalog).not.toContain('SQL-ASSISTANT');
    expect(catalog).not.toContain('DBT');
    // Any service directory is listed even without a catalog entry, so the directories must be gone too.
    expect(fs.existsSync(path.join(SERVICES, 'SQL-ASSISTANT'))).toBe(false);
    expect(fs.existsSync(path.join(SERVICES, 'DBT'))).toBe(false);
  });

  it('ships TRINO, SUPERSET and OPENMETADATA only', () => {
    expect([...catalog].sort()).toEqual(['OPENMETADATA', 'SUPERSET', 'TRINO']);
    // GitLab left the release: neither its catalog entry nor its definition directory may remain.
    expect(catalog).not.toContain('GITLAB');
    expect(fs.existsSync(path.join(SERVICES, 'GITLAB'))).toBe(false);
  });

  it('offers every catalog service on both platforms', () => {
    for (const key of catalog) {
      const service = definition(key);
      expect([key, offeredOnPlatform(service, 'openshift')]).toEqual([key, true]);
      expect([key, offeredOnPlatform(service, 'kubernetes')]).toEqual([key, true]);
    }
  });
});
