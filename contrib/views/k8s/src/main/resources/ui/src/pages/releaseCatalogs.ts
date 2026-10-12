// Pure helper for HelmReleasesPage: whether a release offers the Catalogs action.
//
// The action runs on the Trino coordinator as the KDPS service user, a password login. It needs a release deployed
// with 'Let KDPS manage catalogs', which in turn needs 'Enable internal HTTPS' (Trino only accepts passwords over
// HTTPS), so it is left out where the backend found either one off. When the backend could not tell, the action is
// offered and says what is missing.

import type { HelmRelease } from '../types';

export function offersCatalogs(release: HelmRelease): boolean {
  return (release.serviceKey || '').toUpperCase() === 'TRINO' && release.catalogManagement !== false;
}
