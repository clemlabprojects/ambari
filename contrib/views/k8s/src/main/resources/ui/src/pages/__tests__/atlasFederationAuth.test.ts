import { atlasFixRestartOffered, federationAllowsFileAuth, federationManualGrantNotice, federationMayTurnOnFileAuth, federationRefusalNotice, federationWillBeRefused, federationWritesAtlasUserStore } from '../atlasFederationAuth';

// What the Atlas federation of an OpenMetadata deploy does to Atlas decides whether the wizard warns about an Atlas
// restart: only a federation user added to Atlas's file user store needs one.
describe('federationWritesAtlasUserStore', () => {
  it('writes the user store on an Atlas with file authentication', () => {
    expect(federationWritesAtlasUserStore({ atlasFederation: { enabled: true } }, { atlasAuthMode: 'basic' })).toBe(true);
  });

  it('writes the user store when the operator allowed file authentication on a Kerberos Atlas', () => {
    expect(federationWritesAtlasUserStore(
      { atlasFederation: { enabled: true, allowFileAuth: true } }, { atlasAuthMode: 'kerberos' })).toBe(true);
  });

  it('leaves Atlas alone on an LDAP Atlas with a service account Secret', () => {
    expect(federationWritesAtlasUserStore(
      { atlasFederation: { enabled: true, existingSecret: 'om-ldap-account' } },
      { atlasAuthMode: 'ldap', atlasLdapAuth: true })).toBe(false);
  });

  it('leaves Atlas alone on an LDAP Atlas that also has file authentication on', () => {
    // The context reports file authentication first; the LDAP flag tells the service account is used.
    expect(federationWritesAtlasUserStore(
      { atlasFederation: { enabled: true, existingSecret: 'om-ldap-account' } },
      { atlasAuthMode: 'basic', atlasLdapAuth: true })).toBe(false);
  });

  it('still writes the user store on an LDAP Atlas without a service account Secret', () => {
    expect(federationWritesAtlasUserStore(
      { atlasFederation: { enabled: true, existingSecret: '  ' } }, { atlasAuthMode: 'ldap', atlasLdapAuth: true })).toBe(true);
    expect(federationWritesAtlasUserStore(undefined, { atlasAuthMode: 'ldap' })).toBe(true);
  });

  it('writes the user store with a Secret on an Atlas without LDAP, or when the context is unknown', () => {
    const values = { atlasFederation: { enabled: true, existingSecret: 'om-file-account' } };
    expect(federationWritesAtlasUserStore(values, { atlasAuthMode: 'basic', atlasLdapAuth: false })).toBe(true);
    expect(federationWritesAtlasUserStore(values, null)).toBe(true);
  });
});

// Federation is switched on by default on a context with Atlas: the wizard says before the deploy when the backend
// will refuse it, and does not ask to approve an Atlas restart that will not happen.
describe('federationWillBeRefused', () => {
  const kerberosOnly = { atlasManaged: true, atlasAuthMode: 'kerberos', atlasLdapAuth: false };
  const ldapOnly = { atlasManaged: true, atlasAuthMode: 'ldap', atlasLdapAuth: true };

  it('is refused on a Kerberos-only Atlas when file authentication is not allowed', () => {
    const values = { atlasFederation: { enabled: true } };
    expect(federationWillBeRefused(values, kerberosOnly)).toBe(true);
    expect(federationWritesAtlasUserStore(values, kerberosOnly)).toBe(false);
    expect(federationMayTurnOnFileAuth(values, kerberosOnly)).toBe(false);
    const notice = federationRefusalNotice(values, kerberosOnly);
    expect(notice).not.toBeNull();
    expect(notice!.lines.join(' ')).toContain('Allow Atlas file authentication');
    expect(notice!.lines.join(' ')).not.toContain('Existing Secret');
  });

  it('is refused on an LDAP Atlas without a service account Secret, and names both remedies', () => {
    const values = { atlasFederation: { enabled: true, existingSecret: ' ' } };
    expect(federationWillBeRefused(values, ldapOnly)).toBe(true);
    expect(federationWritesAtlasUserStore(values, ldapOnly)).toBe(false);
    const text = federationRefusalNotice(values, ldapOnly)!.lines.join(' ');
    expect(text).toContain('Existing Secret (service account)');
    expect(text).toContain('Allow Atlas file authentication');
  });

  it('goes ahead once the operator gives an LDAP service account Secret', () => {
    const values = { atlasFederation: { enabled: true, existingSecret: 'om-ldap-account' } };
    expect(federationWillBeRefused(values, ldapOnly)).toBe(false);
    expect(federationRefusalNotice(values, ldapOnly)).toBeNull();
  });

  it('goes ahead once the operator allows file authentication, which then restarts Atlas', () => {
    const values = { atlasFederation: { enabled: true, allowFileAuth: true } };
    expect(federationWillBeRefused(values, kerberosOnly)).toBe(false);
    expect(federationWritesAtlasUserStore(values, kerberosOnly)).toBe(true);
  });

  it('goes ahead on an Atlas with file authentication on', () => {
    const atlas = { atlasManaged: true, atlasAuthMode: 'basic', atlasLdapAuth: true };
    expect(federationWillBeRefused({ atlasFederation: { enabled: true } }, atlas)).toBe(false);
  });

  it('is not claimed when federation is off, Atlas is not managed by the cluster, or the context is unknown', () => {
    expect(federationWillBeRefused({ atlasFederation: { enabled: false } }, kerberosOnly)).toBe(false);
    expect(federationWillBeRefused({ atlasFederation: { enabled: true } },
      { atlasManaged: false, atlasAuthMode: 'kerberos' })).toBe(false);
    expect(federationWillBeRefused({ atlasFederation: { enabled: true } }, null)).toBe(false);
  });
});

describe('federationAllowsFileAuth', () => {
  it('is off unless the operator ticked it', () => {
    expect(federationAllowsFileAuth({ atlasFederation: { enabled: true } })).toBe(false);
    expect(federationAllowsFileAuth(undefined)).toBe(false);
    expect(federationAllowsFileAuth({ atlasFederation: { allowFileAuth: false } })).toBe(false);
  });

  it('is on when ticked, as a boolean or as the string the form may carry', () => {
    expect(federationAllowsFileAuth({ atlasFederation: { allowFileAuth: true } })).toBe(true);
    expect(federationAllowsFileAuth({ atlasFederation: { allowFileAuth: 'true' } })).toBe(true);
  });
});

// The operator confirms turning Atlas file authentication on, on installs and upgrades alike.
describe('federationMayTurnOnFileAuth', () => {
  const allowed = { atlasFederation: { enabled: true, allowFileAuth: true } };

  it('asks on a Kerberos Atlas when the operator allowed it', () => {
    expect(federationMayTurnOnFileAuth(allowed, { atlasAuthMode: 'kerberos' })).toBe(true);
  });

  it('asks when the context is unknown', () => {
    expect(federationMayTurnOnFileAuth(allowed, null)).toBe(true);
  });

  it('does not ask when it was not allowed, or federation is off', () => {
    expect(federationMayTurnOnFileAuth({ atlasFederation: { enabled: true } }, { atlasAuthMode: 'kerberos' })).toBe(false);
    expect(federationMayTurnOnFileAuth({ atlasFederation: { enabled: false, allowFileAuth: true } },
      { atlasAuthMode: 'kerberos' })).toBe(false);
  });

  it('does not ask when file authentication is already on', () => {
    expect(federationMayTurnOnFileAuth(allowed, { atlasAuthMode: 'basic' })).toBe(false);
  });

  it('does not ask when OpenMetadata signs in with an LDAP service account', () => {
    expect(federationMayTurnOnFileAuth(
      { atlasFederation: { enabled: true, allowFileAuth: true, existingSecret: 'om-ldap-account' } },
      { atlasAuthMode: 'ldap', atlasLdapAuth: true })).toBe(false);
  });
});

describe('atlasFixRestartOffered', () => {
  it('is offered unless the check says nothing is kept in Atlas', () => {
    expect(atlasFixRestartOffered({ fixRestartAvailable: true })).toBe(true);
    expect(atlasFixRestartOffered({})).toBe(true);
    expect(atlasFixRestartOffered({ fixRestartAvailable: false })).toBe(false);
  });
});

// An LDAP service account is granted read access by KDPS only through Ranger: on Atlas's simple authorizer the wizard
// tells the operator to grant it, since Atlas would otherwise refuse OpenMetadata's reads.
describe('federationManualGrantNotice', () => {
  const ldapSimple = { atlasManaged: true, atlasAuthMode: 'ldap', atlasLdapAuth: true, atlasAclMode: 'simple' };
  const withSecret = { atlasFederation: { enabled: true, existingSecret: ' om-ldap-account ' } };

  it('asks for the grant on an LDAP Atlas with the simple authorizer, naming the Secret', () => {
    const notice = federationManualGrantNotice(withSecret, ldapSimple);
    expect(notice).not.toBeNull();
    const text = notice!.lines.join(' ');
    expect(text).toContain('"om-ldap-account"');
    expect(text).toContain('atlas-simple-authz-policy.json');
  });

  it('says nothing with Ranger, without a Secret, or with federation off', () => {
    expect(federationManualGrantNotice(withSecret, { ...ldapSimple, atlasAclMode: 'ranger' })).toBeNull();
    expect(federationManualGrantNotice({ atlasFederation: { enabled: true } }, ldapSimple)).toBeNull();
    expect(federationManualGrantNotice({ atlasFederation: { enabled: false, existingSecret: 'x' } }, ldapSimple)).toBeNull();
    expect(federationManualGrantNotice(withSecret, { ...ldapSimple, atlasManaged: false })).toBeNull();
  });

  it('says in the refusal notice that the LDAP account is then granted by hand', () => {
    const text = federationRefusalNotice({ atlasFederation: { enabled: true } }, ldapSimple)!.lines.join(' ');
    expect(text).not.toContain('Nothing is changed in Atlas.');
    expect(text).toContain('give that account read access in Atlas yourself');
  });
});
