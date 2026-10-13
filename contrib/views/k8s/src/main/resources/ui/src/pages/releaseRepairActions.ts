// Pure helper for HelmReleasesPage: whether "Regenerate keytabs", "Re-register OIDC client" and "Reapply Ranger
// repository" can run for a release.
//
// They run against the platform context the release was deployed on. A context other than the managed cluster gives
// no keytabs to regenerate (the operator provides the keytab Secret), and may lack the Keycloak or Ranger credentials
// the other two need; a release deployed without OIDC login has no client to re-register. The backend says which and
// why; the page disables those items with the reason. Until it has answered, or when it could not tell, the items stay
// enabled, and running one reports what is missing.

/** The repair actions, named as the backend's `/actions/<name>` endpoints. */
export type RepairAction = 'keytabs' | 'oidc' | 'ranger';

export interface RepairActionState {
  disabled: boolean;
  /** Why the action cannot run, shown on the disabled item. */
  reason?: string;
}

export function repairActionState(refusals: Record<string, string> | undefined, action: RepairAction): RepairActionState {
  const reason = refusals?.[action];
  return reason ? { disabled: true, reason } : { disabled: false };
}
