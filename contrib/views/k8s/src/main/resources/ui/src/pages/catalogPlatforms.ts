// Pure helper for the two service catalogs, the Catalog page and the Releases page's install picker: whether a
// catalog service is offered on the connected cluster.
//
// A service definition may list the platforms it supports, as the capability probe names them ("kubernetes" or
// "openshift"): GitLab declares ["kubernetes"], because OpenShift's default security policy rejects the pods of its
// chart. Such a service is hidden once the probe has said which platform the cluster is and that platform is not in
// the list. While the platform is unknown (the probe is loading or failed) every service stays listed: the server
// refuses the deploy on a platform the service does not support anyway.

export function offeredOnPlatform(service: { platforms?: unknown } | null | undefined, platform: string | undefined): boolean {
  const platforms = service?.platforms;
  if (!platform || !Array.isArray(platforms) || platforms.length === 0) return true;
  return platforms.some(p => typeof p === 'string' && p.trim().toLowerCase() === platform.toLowerCase());
}
