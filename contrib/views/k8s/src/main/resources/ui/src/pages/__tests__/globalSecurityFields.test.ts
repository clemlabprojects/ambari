import { buildProfileFields, isSecretSet, MASKED_SECRET, SchemaProp, withSecretFlags } from '../globalSecurityFields';

// Mirrors KDPS/globals/security.json: ldap.* fields (modes ldap) and ldap.ad* fields (modes ad).
const SCHEMA: SchemaProp[] = [
  { name: 'mode', type: 'select' },
  { name: 'ldap.url', type: 'string' },
  { name: 'ldap.bindDn', type: 'string' },
  { name: 'ldap.bindPassword', type: 'password' },
  { name: 'ldap.startTls', type: 'boolean' },
  { name: 'ldap.adUrl', type: 'string' },
  { name: 'ldap.adBaseDn', type: 'string' },
  { name: 'ldap.adBindDn', type: 'string' },
  { name: 'ldap.adBindPassword', type: 'password' },
  { name: 'ldap.adDomain', type: 'string' },
  { name: 'oidc.source', type: 'select' },
  { name: 'tls.truststoreSecret', type: 'string' },
];

const AD_PROFILE = {
  mode: 'ad',
  ldap: { adUrl: 'ldaps://ad.corp:636', adBaseDn: 'dc=corp', adBindDn: 'cn=svc', adBindPassword: 's3cr3t', adDomain: 'corp' },
};
const LDAP_PROFILE = {
  mode: 'ldap',
  ldap: { url: 'ldaps://ipa:636', bindDn: 'uid=svc', bindPassword: 'pw' },
};

describe('buildProfileFields — no cross-profile inheritance', () => {
  it('+ New profile (cfg undefined) blanks ALL AD/LDAP fields (no leak)', () => {
    const f = buildProfileFields(SCHEMA, undefined);
    // every ad field blanked
    expect(f.ldap.adUrl).toBe('');
    expect(f.ldap.adBaseDn).toBe('');
    expect(f.ldap.adBindDn).toBe('');
    expect(f.ldap.adBindPassword).toBe('');   // <- the dangerous one: no stale password
    expect(f.ldap.adDomain).toBe('');
    expect(f.ldap.url).toBe('');
    expect(f.ldap.startTls).toBe(false);
    expect(f.oidc.source).toBe('');
  });

  it('switching to an LDAP profile blanks the AD fields the prior AD profile set', () => {
    const f = buildProfileFields(SCHEMA, LDAP_PROFILE);
    expect(f.ldap.url).toBe('ldaps://ipa:636');
    expect(f.ldap.bindDn).toBe('uid=svc');
    // AD fields must be blank, not inherited
    expect(f.ldap.adUrl).toBe('');
    expect(f.ldap.adBindPassword).toBe('');
  });

  it('switching to an AD profile loads its values and blanks pure-LDAP fields', () => {
    const f = buildProfileFields(SCHEMA, AD_PROFILE);
    expect(f.ldap.adUrl).toBe('ldaps://ad.corp:636');
    expect(f.ldap.adBindPassword).toBe('s3cr3t');
    expect(f.ldap.url).toBe('');       // pure-LDAP field blank under AD
    expect(f.ldap.bindPassword).toBe('');
  });

  it('does not set mode or extraProperties (caller handles those)', () => {
    const f = buildProfileFields(SCHEMA, AD_PROFILE);
    expect(f.mode).toBeUndefined();
    expect(f.extraProperties).toBeUndefined();
  });

  it('initial load with empty schema still loads the profile values (overlay is schema-independent)', () => {
    const f = buildProfileFields([], AD_PROFILE);
    expect(f.ldap.adUrl).toBe('ldaps://ad.corp:636');
    expect(f.ldap.adBindPassword).toBe('s3cr3t');
  });
});

// The server never sends a stored secret back: it sends the mask and a `<field>Set` flag.
const STORED_AD_PROFILE = {
  mode: 'ad',
  ldap: { adUrl: 'ldaps://ad.corp:636', adBindPassword: MASKED_SECRET, adBindPasswordSet: true, bindPasswordSet: false },
};

describe('stored secrets', () => {
  it('a stored secret loads as a blank field, never as the mask', () => {
    const f = buildProfileFields(SCHEMA, STORED_AD_PROFILE);
    expect(f.ldap.adBindPassword).toBe('');
    expect(f.ldap.adUrl).toBe('ldaps://ad.corp:636');
  });

  it('tells which password fields have a stored secret', () => {
    expect(isSecretSet(STORED_AD_PROFILE, 'ldap.adBindPassword')).toBe(true);
    expect(isSecretSet(STORED_AD_PROFILE, 'ldap.bindPassword')).toBe(false);
    expect(isSecretSet(undefined, 'ldap.adBindPassword')).toBe(false);
  });

  it('after a save, a typed secret is kept masked and a blank one keeps its flag', () => {
    const fields = ['ldap.bindPassword', 'ldap.adBindPassword', 'oidc.clientSecret'];
    const saved = { mode: 'ad', ldap: { adUrl: 'ldaps://ad.corp:636', adBindPassword: '', bindPassword: 'typed-in' } };

    const kept = withSecretFlags(saved, STORED_AD_PROFILE, fields);

    expect(kept.ldap.adBindPassword).toBe(MASKED_SECRET);
    expect(kept.ldap.adBindPasswordSet).toBe(true);
    expect(kept.ldap.bindPassword).toBe(MASKED_SECRET);
    expect(kept.ldap.bindPasswordSet).toBe(true);
    expect(JSON.stringify(kept)).not.toContain('typed-in');
    expect(kept.oidc).toBeUndefined();
    expect(saved.ldap.bindPassword).toBe('typed-in');
  });
});
