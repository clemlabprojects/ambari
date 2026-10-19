import { gitOptionsForRelease, releaseRedeployPayload } from '../releaseRedeploy';
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

// Resync, Refresh security profile, worker autoscaling and Upgrade/Config send the release's Git options back. They
// name the base branch Flux reconciles and the commit mode as recorded, never the working branch: a direct commit goes
// to the base branch, and the server picks the branch of a pull request.
describe('gitOptionsForRelease', () => {
  const gitOps = {
    ...release,
    gitRepoUrl: 'https://git.example.com/platform.git',
    gitBranch: 'flux-3f2a1c4e-0b5d-4c6e-9f7a-8b9c0d1e2f3a',
    gitBaseBranch: 'production',
    gitCommitMode: 'PR_MODE',
  } as HelmRelease;

  it('sends the GitOps profile the release is written with', () => {
    expect(gitOptionsForRelease({ ...gitOps, gitProfile: 'pipeline' } as HelmRelease)).toEqual({
      repoUrl: 'https://git.example.com/platform.git',
      baseBranch: 'production',
      commitMode: 'PR_MODE',
      profile: 'pipeline',
    });
    expect(gitOptionsForRelease(gitOps)).not.toHaveProperty('profile');
  });

  it('sends the recorded base branch and commit mode, and no working branch', () => {
    expect(gitOptionsForRelease(gitOps)).toEqual({
      repoUrl: 'https://git.example.com/platform.git',
      baseBranch: 'production',
      commitMode: 'PR_MODE',
    });
  });

  it('does not send main back as the base branch when it is only assumed for an old release', () => {
    const assumed = { ...gitOps, gitBaseBranch: 'main', gitBaseBranchAssumed: true } as HelmRelease;
    expect(gitOptionsForRelease(assumed)?.baseBranch).toBeUndefined();
    expect(gitOptionsForRelease(assumed)?.commitMode).toBe('PR_MODE');
  });

  it('keeps pull request mode for a release whose pull request was never opened', () => {
    expect(gitOptionsForRelease({ ...gitOps, gitPrNumber: undefined })?.commitMode).toBe('PR_MODE');
  });

  it('sends nothing for a release deployed with Helm', () => {
    expect(gitOptionsForRelease({ ...gitOps, deploymentMode: 'DIRECT_HELM' })).toBeUndefined();
  });
});
