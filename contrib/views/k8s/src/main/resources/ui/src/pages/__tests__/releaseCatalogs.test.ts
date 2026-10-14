import { offersCatalogs } from '../releaseCatalogs';
import type { HelmRelease } from '../../types';

// The Catalogs action runs on the Trino coordinator as the KDPS service user, which needs 'Let KDPS manage catalogs'
// and 'Enable internal HTTPS' on the release. A release deployed with internal HTTPS off never has the first, so the
// Releases page leaves the action out there; when the backend could not read the release, the action stays and says
// what is missing.
const trino = (catalogManagement?: boolean | null) => ({
  id: 'analytics/trino',
  name: 'trino',
  namespace: 'analytics',
  chart: 'trino-1.43.16',
  version: '1.43.16',
  status: 'deployed',
  serviceKey: 'TRINO',
  catalogManagement,
} as HelmRelease);

describe('offersCatalogs', () => {
  it('offers the action on a Trino release where catalog management can run', () => {
    expect(offersCatalogs(trino(true))).toBe(true);
  });

  it('leaves it out on a Trino release without the KDPS service user or internal HTTPS', () => {
    expect(offersCatalogs(trino(false))).toBe(false);
  });

  it('offers it when the backend could not tell', () => {
    expect(offersCatalogs(trino(undefined))).toBe(true);
    expect(offersCatalogs(trino(null))).toBe(true);
  });

  it('never offers it on another service', () => {
    expect(offersCatalogs({ ...trino(true), serviceKey: 'SUPERSET' })).toBe(false);
    expect(offersCatalogs({ ...trino(true), serviceKey: undefined })).toBe(false);
  });
});
