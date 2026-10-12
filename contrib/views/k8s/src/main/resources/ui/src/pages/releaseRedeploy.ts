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
