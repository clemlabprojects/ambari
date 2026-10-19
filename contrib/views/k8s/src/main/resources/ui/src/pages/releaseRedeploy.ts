// Pure helper for HelmReleasesPage: the deploy request that redeploys a release from the Releases page (Resync /
// restart, Refresh security profile, worker autoscaling) with its current or edited values.
//
// It names the platform context the release runs with: without it, Kerberos, Ranger and the Hadoop configuration
// would be taken from the local Ambari instead of the release's external context.
//
// It names no chart version on purpose. The backend keeps the release's version as it stands when the request
// arrives: the one Helm reports, or for a GitOps release the one last committed to Git. The version on this page was
// read when the list loaded and is not reloaded after a rollback or an upgrade made elsewhere, so sending it could move
// the release back to a chart it no longer runs.

import type { HelmRelease } from '../types';

/**
 * The Git options a redeploy of a GitOps release sends back: its repository, the base branch Flux reconciles and the
 * commit mode it was deployed with, as recorded; not main when it is only assumed for a release recorded before base
 * branches were, which the server would then record as the branch Flux reads. Never a working branch: a direct commit goes to the base branch, and
 * the backend picks the branch of a pull request (the release's own while it is open, else a new one from the base
 * branch). Undefined for a release deployed with Helm.
 */
export function gitOptionsForRelease(release: HelmRelease) {
  if (release.deploymentMode !== 'FLUX_GITOPS') return undefined;
  return {
    repoUrl: release.gitRepoUrl,
    baseBranch: release.gitBaseBranchAssumed ? undefined : release.gitBaseBranch,
    commitMode: release.gitCommitMode,
    // The profile the release is written with: another one is refused.
    ...(release.gitProfile ? { profile: release.gitProfile } : {}),
  };
}

export function releaseRedeployPayload(release: HelmRelease, values: any, git?: any) {
  return {
    chart: release.chartRef || release.chart,
    releaseName: release.name,
    namespace: release.namespace,
    values,
    formValues: release.platformContextId ? { platformContextId: release.platformContextId } : undefined,
    serviceKey: release.serviceKey,
    securityProfile: release.securityProfile,
    repoId: release.repoId,
    deploymentMode: release.deploymentMode,
    git,
  };
}

/** Why a release deployed with Flux GitOps is not rolled back from KDPS, and what brings an earlier version back. */
export const ROLLBACK_IN_GIT = 'Flux installs what Git holds and would undo a Helm rollback at its next reconcile.'
  + ' To go back to an earlier version, revert or change the release in Git: revert its commit in the Git repository,'
  + ' or use Upgrade / Config, which commits the change to Git.';

/** Whether a release is rolled back through Git rather than with Helm: one deployed with Flux GitOps. */
export function rollbackHeldInGit(release: Pick<HelmRelease, 'deploymentMode'>): boolean {
  return release.deploymentMode === 'FLUX_GITOPS';
}

/**
 * Why a GitOps release Flux has not installed offers no action that redeploys it. Those actions start from the values
 * the cluster holds for the release, and it holds none: the change committed would replace the configuration waiting in
 * Git (in its pull request, for one not merged) with the service's defaults.
 */
export const NOT_INSTALLED_BY_FLUX = 'Flux has not installed this release, so the cluster holds none of its'
  + ' configuration to start from: this action would replace the configuration waiting in Git with defaults. Change'
  + ' the release in Git, or use this action once Flux has installed it.';

/** Whether a release is listed from its GitOps record only: Helm does not list it, so it has no values to redeploy. */
export function notInstalledByFlux(release: Pick<HelmRelease, 'installed'>): boolean {
  return release.installed === false;
}
