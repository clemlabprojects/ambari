// Pure helpers for ServiceWizardPage: what the Atlas federation of an OpenMetadata deploy does to Atlas, from the
// wizard's values and the Atlas sign-in methods of the selected platform context. Mirrors the backend decision: the
// federation user lives in Atlas's file user store (which needs an Atlas restart to be loaded), except on an Atlas
// that signs users in against LDAP when the operator named a service account Secret, where Atlas is left untouched.
// When neither applies and the operator did not allow file authentication, the backend refuses the federation
// instead of changing Atlas, and the wizard says so before the deploy.

/** The Atlas sign-in methods a resolved platform context reports. */
export interface AtlasSignIn {
  /** The first method on, file before LDAP before Kerberos: 'basic' means file authentication is on. */
  atlasAuthMode?: string;
  /** Atlas signs users in against LDAP, also when file authentication is on too. */
  atlasLdapAuth?: boolean;
  /** Atlas is an Ambari-managed service of the context: only then are its sign-in methods read from the cluster. */
  atlasManaged?: boolean;
  /** The Atlas authorizer: 'ranger', or 'simple' for the policy file Atlas ships with. */
  atlasAclMode?: string;
}

/** A warning the wizard shows before the deploy. */
export interface FederationNotice {
  title: string;
  lines: string[];
}

function federationValues(installValues: Record<string, any> | undefined): Record<string, any> {
  const v = installValues?.atlasFederation;
  return v && typeof v === 'object' ? v : {};
}

function hasServiceAccountSecret(installValues: Record<string, any> | undefined): boolean {
  return String(federationValues(installValues).existingSecret ?? '').trim() !== '';
}

function ldapSignIn(atlas: AtlasSignIn | null | undefined): boolean {
  // A context resolved by an older view only reports the first method on.
  return atlas?.atlasLdapAuth === true || atlas?.atlasAuthMode === 'ldap';
}

/** True when the operator allowed KDPS to turn Atlas file authentication on. */
export function federationAllowsFileAuth(installValues: Record<string, any> | undefined): boolean {
  const flag = federationValues(installValues).allowFileAuth;
  return flag === true || String(flag).toLowerCase() === 'true';
}

/**
 * True when the deploy will stop at the federation step because Atlas offers OpenMetadata no way to sign in that the
 * operator allowed: an Ambari-managed Atlas without file authentication, no LDAP service account Secret, and file
 * authentication not allowed. Only claimed when the context read Atlas's sign-in methods from the cluster.
 */
export function federationWillBeRefused(installValues: Record<string, any> | undefined,
                                        atlas: AtlasSignIn | null | undefined): boolean {
  if (!federationValues(installValues).enabled) return false;
  if (atlas?.atlasManaged !== true || !atlas.atlasAuthMode || atlas.atlasAuthMode === 'basic') return false;
  if (ldapSignIn(atlas) && hasServiceAccountSecret(installValues)) return false;
  return !federationAllowsFileAuth(installValues);
}

/** What the wizard tells the operator when the Atlas federation would be refused, or null when it goes ahead. */
export function federationRefusalNotice(installValues: Record<string, any> | undefined,
                                        atlas: AtlasSignIn | null | undefined): FederationNotice | null {
  if (!federationWillBeRefused(installValues, atlas)) return null;
  const ldap = ldapSignIn(atlas);
  const lines = [
    'OpenMetadata can only sign in to Atlas with a user name and password, and Atlas on this cluster '
      + (ldap ? 'signs users in against LDAP.' : 'does not offer password sign-in.')
      + ' As set, the deployment would stop at the Atlas federation step without changing Atlas.',
  ];
  if (ldap) {
    lines.push('To sign in through LDAP, set "Existing Secret (service account)" to a Secret in this namespace '
      + 'holding an LDAP service account (keys "username" and "password"). Nothing is changed in Atlas'
      + (simpleAuthorizer(atlas) ? ', so you then give that account read access in Atlas yourself.' : '.'));
  }
  lines.push('Or tick "Allow Atlas file authentication": KDPS then turns it on and creates a dedicated '
    + 'non-administrator user, but the Atlas scripts also add a local administrator account to Atlas.');
  lines.push('Or turn Atlas federation off to deploy OpenMetadata without it.');
  return { title: 'Atlas federation cannot go ahead as set', lines };
}

function simpleAuthorizer(atlas: AtlasSignIn | null | undefined): boolean {
  return atlas?.atlasAclMode === 'simple';
}

/**
 * What the wizard tells the operator when OpenMetadata will sign in with an LDAP service account to an Atlas that uses
 * its simple authorizer: KDPS grants that account nothing there, so the operator does. Null otherwise.
 */
export function federationManualGrantNotice(installValues: Record<string, any> | undefined,
                                            atlas: AtlasSignIn | null | undefined): FederationNotice | null {
  if (!federationValues(installValues).enabled || atlas?.atlasManaged !== true) return null;
  if (!ldapSignIn(atlas) || !hasServiceAccountSecret(installValues) || !simpleAuthorizer(atlas)) return null;
  const secret = String(federationValues(installValues).existingSecret).trim();
  return {
    title: 'Grant the LDAP service account read access in Atlas',
    lines: [
      'Atlas on this cluster uses its simple authorizer, and KDPS grants nothing to the LDAP service account in '
        + `Secret "${secret}".`,
      'Add that account, or a group it belongs to, with read access to Atlas\'s atlas-simple-authz-policy.json and '
        + 'restart Atlas. Until then Atlas refuses OpenMetadata\'s reads, unless the account is already in a group '
        + 'the policy grants.',
    ],
  };
}

/**
 * True when the deploy adds the federation user to Atlas's user store, so that Atlas must be restarted: not with an
 * LDAP service account, and not when the federation will be refused, since Atlas is then left untouched.
 */
export function federationWritesAtlasUserStore(installValues: Record<string, any> | undefined,
                                               atlas: AtlasSignIn | null | undefined): boolean {
  if (ldapSignIn(atlas) && hasServiceAccountSecret(installValues)) return false;
  return !federationWillBeRefused(installValues, atlas);
}

/**
 * True when the deploy may turn Atlas file authentication on, so the operator confirms it first: they allowed it,
 * and as far as the context tells, Atlas does not have it on already and is not signed in to with an LDAP account.
 */
export function federationMayTurnOnFileAuth(installValues: Record<string, any> | undefined,
                                            atlas: AtlasSignIn | null | undefined): boolean {
  if (!federationValues(installValues).enabled || !federationAllowsFileAuth(installValues)) return false;
  if (atlas?.atlasAuthMode === 'basic') return false;
  return federationWritesAtlasUserStore(installValues, atlas);
}

/** True when the federation check offers "Fix & restart Atlas": not for an LDAP service account, kept outside Atlas. */
export function atlasFixRestartOffered(report: { fixRestartAvailable?: boolean } | null | undefined): boolean {
  return report?.fixRestartAvailable !== false;
}
