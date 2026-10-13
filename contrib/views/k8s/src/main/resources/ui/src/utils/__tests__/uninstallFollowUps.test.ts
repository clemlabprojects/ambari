import { uninstallFollowUps, uninstallSignInRemovals } from '../uninstallFollowUps';

/** The Releases page shows what an uninstall took back and what it leaves to an administrator, and nothing when there is nothing to say. */
describe('uninstallFollowUps', () => {
  it('lists each note that asks an administrator to act', () => {
    const followUps = uninstallFollowUps({
      sccCleanup: 'oc adm policy remove-scc-from-user anyuid -z om-airflow-worker -n data',
      tagSyncCleanup: "Disable 'kdps-tagsync-trino-data' in Ranger.",
    });

    expect(followUps.map((f) => f.title)).toEqual([
      'OpenShift security context constraints left in place',
      'Ranger account of Atlas tag sync',
    ]);
    expect(followUps[1].text).toBe("Disable 'kdps-tagsync-trino-data' in Ranger.");
  });

  it('is empty when there is nothing to say', () => {
    expect(uninstallFollowUps(undefined)).toEqual([]);
    expect(uninstallFollowUps({})).toEqual([]);
  });

  it('shows what became of the Polaris access and of the single sign-on client, first', () => {
    const oidc = "Removed the single sign-on client 'trino-data' in Keycloak realm 'odp'.\n"
      + 'Removed the Secret trino-oidc-client holding the client secret.';
    const followUps = uninstallFollowUps({
      tagSyncCleanup: "Disable 'kdps-tagsync-trino-data' in Ranger.",
      oidcCleanup: oidc,
      polarisCleanup: "Removed the Polaris principal 'trino'.",
    });

    expect(followUps.map((f) => f.title)).toEqual([
      'Polaris catalog access',
      'Single sign-on client',
      'Ranger account of Atlas tag sync',
    ]);
    expect(followUps[0].text).toBe("Removed the Polaris principal 'trino'.");
    expect(followUps[1].text).toBe(oidc);
  });
});

/** The uninstall confirmation names the single sign-on client and the Secrets that go with the release. */
describe('uninstallSignInRemovals', () => {
  const release = { name: 'trino', namespace: 'data' };

  it('names the client and the Secret the service definition renders', () => {
    expect(uninstallSignInRemovals(release, [{
      clientIdTemplate: '{{releaseName}}-{{namespace}}',
      secretNameTemplate: '{{releaseName}}-oidc-client',
      enabled: true,
    }])).toBe('If it signs in through single sign-on, KDPS also removes the Keycloak client trino-data it registered'
      + ' for it (an identity provider of a security profile is left as it is) and the Secret trino-oidc-client'
      + ' holding its client secret.');
  });

  it("names GitLab's OmniAuth provider Secret too", () => {
    const text = uninstallSignInRemovals({ name: 'gitlab', namespace: 'dev' }, [{ gitlabOmniauth: true }]);

    expect(text).toContain('Keycloak client gitlab-dev ');
    expect(text).toContain('Secrets gitlab-oidc-client, gitlab-omniauth-provider holding');
  });

  it('says nothing for a service without OIDC sign-in', () => {
    expect(uninstallSignInRemovals(release, undefined)).toBeNull();
    expect(uninstallSignInRemovals(release, [])).toBeNull();
    expect(uninstallSignInRemovals(release, [{ enabled: 'false' }])).toBeNull();
  });
});
