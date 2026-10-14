import { uninstallFollowUps, uninstallKdpsObjectRemovals, uninstallSignInRemovals } from '../uninstallFollowUps';

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

  it('shows what became of the objects KDPS created for the release, last', () => {
    const note = 'Removed what KDPS created for the release outside its chart: Secret/trino-keytab.\n'
      + 'Could not remove this object KDPS created for the release; delete it by hand:\n'
      + '  ServiceAccount/trino-thanos-token (this account may not delete it): oc delete serviceaccount'
      + ' trino-thanos-token -n data';
    const followUps = uninstallFollowUps({
      kdpsObjectsCleanup: {
        removed: ['Secret/trino-keytab'],
        left: [{ object: 'ServiceAccount/trino-thanos-token', reason: 'this account may not delete it',
          command: 'oc delete serviceaccount trino-thanos-token -n data' }],
        note,
      },
      polarisCleanup: "Removed the Polaris principal 'trino'.",
    });

    expect(followUps.map((f) => f.title)).toEqual(['Polaris catalog access', 'Objects KDPS created for the release']);
    expect(followUps[1].text).toBe(note);
  });

  it('builds the text of the objects report when the server sent no note', () => {
    const [followUp] = uninstallFollowUps({ kdpsObjectsCleanup: {
      removed: ['ConfigMap/trino-krb5-conf'],
      left: [{ object: 'ClusterRoleBinding/kdps-monview-data-x', reason: 'this account may not delete it',
        command: 'oc delete clusterrolebinding kdps-monview-data-x' }],
    } });

    expect(followUp.text).toBe('Removed what KDPS created for the release outside its chart: ConfigMap/trino-krb5-conf.\n'
      + 'Left ClusterRoleBinding/kdps-monview-data-x (this account may not delete it): oc delete clusterrolebinding'
      + ' kdps-monview-data-x');
    expect(uninstallFollowUps({ kdpsObjectsCleanup: { removed: [], left: [] } })).toEqual([]);
  });

  it('reports what the uninstall kept on purpose, even when it removed nothing', () => {
    const [followUp] = uninstallFollowUps({ kdpsObjectsCleanup: {
      removed: [],
      left: [],
      kept: [{ object: 'Secret/wildcard-tls', reason: 'still used by the Ingress superset of release superset' }],
    } });

    expect(followUp.title).toBe('Objects KDPS created for the release');
    expect(followUp.text).toBe('Kept Secret/wildcard-tls (still used by the Ingress superset of release superset)');
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

/** The uninstall confirmation lists the objects KDPS created for the release outside its chart, which go with it. */
describe('uninstallKdpsObjectRemovals', () => {
  it('names each object, and says what is kept', () => {
    expect(uninstallKdpsObjectRemovals(['Secret/trino-keytab', 'ConfigMap/trino-krb5-conf'])).toBe(
      'KDPS also deletes what it created for this release outside its chart: Secret/trino-keytab,'
      + ' ConfigMap/trino-krb5-conf. Objects an administrator provided, or shared with other releases of the project,'
      + ' are kept.');
  });

  it('says nothing when KDPS created nothing outside the chart', () => {
    expect(uninstallKdpsObjectRemovals([])).toBeNull();
    expect(uninstallKdpsObjectRemovals(undefined)).toBeNull();
  });
});
