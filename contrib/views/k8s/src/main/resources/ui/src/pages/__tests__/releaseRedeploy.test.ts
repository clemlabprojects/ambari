import { releaseRedeployPayload } from '../releaseRedeploy';
import type { HelmRelease } from '../../types';

// Resync, Refresh security profile and worker autoscaling all redeploy a release from the Releases page. Each must
// name the platform context the release runs with, or the redeploy loses its external context (and with it the
// external Kerberos realm). None names a chart version: the backend keeps the one the release runs, which the page
// may show out of date.
const release = {
  id: 'analytics/trino',
  name: 'trino',
  namespace: 'analytics',
  chart: 'trino',
  chartRef: 'trinodb/trino',
  version: '1.43.12',
  status: 'deployed',
  serviceKey: 'TRINO',
  platformContextId: 'cdp-prod',
  securityProfile: 'corp-ldap',
  repoId: 'charts',
  deploymentMode: 'FLUX_GITOPS',
} as HelmRelease;

describe('releaseRedeployPayload', () => {
  it('keeps the platform context and chart source of the release', () => {
    const git = { repoUrl: 'https://git.example.com/platform.git', branch: 'main', commitMode: 'DIRECT_COMMIT' };
    const values = { server: { workers: 3 } };

    expect(releaseRedeployPayload(release, values, git)).toEqual({
      chart: 'trinodb/trino',
      releaseName: 'trino',
      namespace: 'analytics',
      values,
      formValues: { platformContextId: 'cdp-prod' },
      serviceKey: 'TRINO',
      securityProfile: 'corp-ldap',
      repoId: 'charts',
      deploymentMode: 'FLUX_GITOPS',
      git,
    });
  });

  it('never sends the chart version shown on the page, which may be out of date after a rollback', () => {
    const payload = releaseRedeployPayload(release, {});

    expect(payload).not.toHaveProperty('version');
  });

  it('leaves out what the release does not know, so the backend uses the recorded release', () => {
    const payload = releaseRedeployPayload({ ...release, chartRef: undefined, platformContextId: undefined }, {});

    expect(payload.chart).toBe('trino');
    expect(payload.formValues).toBeUndefined();
    expect(payload.git).toBeUndefined();
  });
});
