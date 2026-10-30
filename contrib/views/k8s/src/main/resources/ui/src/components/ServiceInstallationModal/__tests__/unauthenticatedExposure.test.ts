import * as fs from 'fs';
import * as path from 'path';
import { oidcLoginWithoutProfile, passwordLoginWithoutProfile, unauthenticatedExposure } from '../unauthenticatedExposure';
import { applyFormDefaults } from '../chartValues';

/**
 * Security profiles are opt-in, so a deploy without one publishes Trino with no authentication at all (any client
 * names the user it runs as), and Superset or OpenMetadata with their local accounts only. Once the Ingress is on,
 * the wizard says so and deploys only once the operator confirmed it. Asserted against the real service.json files.
 */
const load = (svc: string) => JSON.parse(fs.readFileSync(
  path.join(__dirname, `../../../../../KDPS/services/${svc}/service.json`), 'utf8'));
const TRINO = load('TRINO');
const SUPERSET = load('SUPERSET');
const OPENMETADATA = load('OPENMETADATA');

const PROFILES = {
  corp: { mode: 'ldap', displayName: 'Corporate LDAP' },
  sso: { mode: 'oidc' },
  ad: { mode: 'ad' },
  open: { mode: 'none', displayName: 'Open lab' },
};

const exposed = (over: Record<string, any> = {}) => ({
  releaseName: 'trino', namespace: 'analytics', ingress: { enabled: true, host: 'trino.example.com' }, ...over,
});

const ISSUER = 'https://keycloak.example.com/realms/odp';

/** A context with no identity provider: nothing signs users in unless a profile does. */
const NO_IDP = { id: 'default', name: 'Local', kind: 'MANAGED', resolvedFields: {} };

describe('unauthenticatedExposure', () => {
  it.each([['TRINO', TRINO], ['SUPERSET', SUPERSET], ['OPENMETADATA', OPENMETADATA]])(
    '%s declares what an open Ingress exposes', (_name, def) => {
      expect(String(def.securityCoupling.unauthenticatedExposure)).not.toBe('');
    });

  it('warns for Trino exposed without a security profile, naming the host and what anyone can do', () => {
    const w = unauthenticatedExposure(TRINO, exposed(), PROFILES, NO_IDP as any);
    expect(w).not.toBeNull();
    expect(w!.title).toBe('Trino will be reachable from outside the cluster without sign-in');
    expect(w!.lines[0]).toContain('at trino.example.com');
    expect(w!.lines[0]).toContain('No security profile is selected.');
    expect(w!.lines[1]).toBe(TRINO.securityCoupling.unauthenticatedExposure);
    expect(w!.lines[1]).toContain('run queries as any user');
    expect(w!.lines[2]).toContain('Security Profile');
    expect(w!.lines[2]).toContain('turn the Ingress off');
  });

  it('warns for a profile whose mode is none, naming it', () => {
    const w = unauthenticatedExposure(SUPERSET, exposed({ securityProfile: 'open' }), PROFILES, NO_IDP as any);
    expect(w!.title).toContain('Superset');
    expect(w!.lines[0]).toContain('The security profile "Open lab" has no authentication mode.');
  });

  it('says where the service is published when no hostname is set', () => {
    const w = unauthenticatedExposure(OPENMETADATA, exposed({ ingress: { enabled: true } }), PROFILES, null);
    expect(w!.lines[0]).toContain('at its Ingress address');
  });

  it.each(['corp', 'sso', 'ad'])('does not warn with the %s profile, which requires sign-in', (profile) => {
    expect(unauthenticatedExposure(TRINO, exposed({ securityProfile: profile }), PROFILES, NO_IDP as any))
      .toBeNull();
  });

  it('does not warn when the Ingress is off: the service stays inside the cluster', () => {
    expect(unauthenticatedExposure(TRINO, exposed({ ingress: { enabled: false, host: 'trino.example.com' } }),
      PROFILES, NO_IDP as any)).toBeNull();
    expect(unauthenticatedExposure(TRINO, { releaseName: 'trino' }, PROFILES, NO_IDP as any)).toBeNull();
  });

  it('does not warn for a service that declares no exposure risk', () => {
    const def = { ...TRINO, securityCoupling: { ...TRINO.securityCoupling, unauthenticatedExposure: undefined } };
    expect(unauthenticatedExposure(def, exposed(), PROFILES, NO_IDP as any)).toBeNull();
  });

  it('does not warn for Trino with its KDPS login, the default: Trino then asks every client for a password', () => {
    const defaults: any = {};
    applyFormDefaults(TRINO.form, defaults);
    expect(defaults.kdps.serviceUser.enabled).toBe(true);
    expect(passwordLoginWithoutProfile(TRINO, exposed(defaults))).toBe(true);
    expect(unauthenticatedExposure(TRINO, exposed(defaults), PROFILES, NO_IDP as any)).toBeNull();
  });

  it('warns for Trino with its KDPS login turned off, or hidden with internal HTTPS off', () => {
    const off = exposed({ tls: { https: { enabled: true } }, kdps: { serviceUser: { enabled: false } } });
    expect(unauthenticatedExposure(TRINO, off, PROFILES, NO_IDP as any)).not.toBeNull();
    const noHttps = exposed({ tls: { https: { enabled: false } }, kdps: { serviceUser: { enabled: true } } });
    expect(passwordLoginWithoutProfile(TRINO, noHttps)).toBe(false);
    expect(unauthenticatedExposure(TRINO, noHttps, PROFILES, NO_IDP as any)).not.toBeNull();
  });

  it('tells the operator the KDPS login and where its password is', () => {
    const find = (fields: any[]): any => fields.reduce((hit: any, f: any) =>
      hit || (f.name === 'kdps.serviceUser.enabled' ? f : f.type === 'group' ? find(f.fields || []) : undefined), undefined);
    const help = find(TRINO.form).help;
    expect(help).toContain('kdps is its only login');
    expect(help).toContain('-kdps-service-user');
    expect(help).toContain('(key password)');
  });

  it('does not guess the mode of a profile the wizard could not load', () => {
    expect(unauthenticatedExposure(TRINO, exposed({ securityProfile: 'gone' }), PROFILES, NO_IDP as any))
      .toBeNull();
  });

  it('does not warn when the context signs users in through its identity provider anyway', () => {
    const local = { ...NO_IDP, resolvedFields: { 'oidc.issuerUrl': ISSUER, 'oidc.clientRegistration': 'true' } };
    expect(unauthenticatedExposure(TRINO, exposed(), PROFILES, local as any)).toBeNull();
  });

  it('still warns when the context only names an issuer: no OIDC client is registered for the service', () => {
    // Ambari single sign-on, or an oidc-env with an issuer and no Keycloak administration URL.
    const sso = { ...NO_IDP, resolvedFields: { 'oidc.issuerUrl': ISSUER, 'oidc.realm': 'odp' } };
    expect(unauthenticatedExposure(TRINO, exposed(), PROFILES, sso as any)).not.toBeNull();
  });
});

describe('oidcLoginWithoutProfile', () => {
  const registers = { 'oidc.issuerUrl': ISSUER, 'oidc.clientRegistration': 'true' };

  it('is true when the server registers the service with the context identity provider', () => {
    expect(oidcLoginWithoutProfile(TRINO, { id: 'default', name: 'Local', kind: 'MANAGED', resolvedFields: registers } as any))
      .toBe(true);
    expect(oidcLoginWithoutProfile(TRINO, { id: 'cdp', name: 'CDP', kind: 'EXTERNAL', resolvedFields: registers } as any))
      .toBe(true);
  });

  it('is false for an issuer the server registers nothing with, even with an administrator client id', () => {
    const issuerOnly = { 'oidc.issuerUrl': ISSUER, 'oidc.adminClientId': 'kdps' };
    expect(oidcLoginWithoutProfile(TRINO, { ...NO_IDP, resolvedFields: issuerOnly } as any)).toBe(false);
  });

  it('is false without a resolved context, or for a service with no OIDC client', () => {
    expect(oidcLoginWithoutProfile(TRINO, NO_IDP as any)).toBe(false);
    expect(oidcLoginWithoutProfile(TRINO, null)).toBe(false);
    const local = { ...NO_IDP, resolvedFields: registers };
    expect(oidcLoginWithoutProfile({ ...TRINO, oidc: [] }, local as any)).toBe(false);
    expect(oidcLoginWithoutProfile({ ...TRINO, oidc: [{ ...TRINO.oidc[0], enabled: false }] }, local as any))
      .toBe(false);
  });
});
