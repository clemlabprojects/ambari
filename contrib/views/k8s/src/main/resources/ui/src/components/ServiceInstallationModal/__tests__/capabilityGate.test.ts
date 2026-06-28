import { fieldCapabilityAvailable } from '../capabilities';
import type { ClusterCapabilities } from '../../../api/client';

/**
 * Tests for the pure field/option capability gate used by DynamicFormField.
 *
 * `openshift` gates a field on the detected cluster platform and is fail-CLOSED:
 * an OpenShift-only field must never flash on vanilla Kubernetes (nor while the
 * capability probe is still loading). The installed-CRD capabilities stay
 * fail-OPEN while caps are loading, matching the pre-existing behaviour.
 */
const caps = (platform: 'openshift' | 'kubernetes', extra: Partial<ClusterCapabilities> = {}): ClusterCapabilities => ({
  platform,
  openshift: { routeCrd: platform === 'openshift' },
  certManager: { installed: false, clusterIssuerCrd: false, certificateCrd: false },
  externalSecrets: { installed: false, secretStoreCrd: false, clusterSecretStoreCrd: false, externalSecretCrd: false },
  ...(extra as any),
});

describe('fieldCapabilityAvailable', () => {
  it('shows a field with no capability requirement', () => {
    expect(fieldCapabilityAvailable(undefined, undefined)).toBe(true);
    expect(fieldCapabilityAvailable(undefined, caps('kubernetes'))).toBe(true);
  });

  it('openshift gate: shows only on OpenShift', () => {
    expect(fieldCapabilityAvailable('openshift', caps('openshift'))).toBe(true);
    expect(fieldCapabilityAvailable('openshift', caps('kubernetes'))).toBe(false);
  });

  it('openshift gate is fail-CLOSED while caps are still loading', () => {
    expect(fieldCapabilityAvailable('openshift', undefined)).toBe(false);
  });

  it('certManager gate reflects installed flag, fail-open while loading', () => {
    expect(fieldCapabilityAvailable('certManager', undefined)).toBe(true); // loading → fail-open
    expect(fieldCapabilityAvailable('certManager', caps('kubernetes'))).toBe(false);
    expect(
      fieldCapabilityAvailable('certManager', caps('kubernetes', { certManager: { installed: true, clusterIssuerCrd: true, certificateCrd: true } }))
    ).toBe(true);
  });

  it('externalSecrets gate reflects installed flag, fail-open while loading', () => {
    expect(fieldCapabilityAvailable('externalSecrets', undefined)).toBe(true); // loading → fail-open
    expect(fieldCapabilityAvailable('externalSecrets', caps('openshift'))).toBe(false);
    expect(
      fieldCapabilityAvailable('externalSecrets', caps('openshift', { externalSecrets: { installed: true, secretStoreCrd: true, clusterSecretStoreCrd: true, externalSecretCrd: true } }))
    ).toBe(true);
  });

  it('an unknown capability defaults to visible (does not hide the field)', () => {
    expect(fieldCapabilityAvailable('somethingNew', caps('kubernetes'))).toBe(true);
  });
});
