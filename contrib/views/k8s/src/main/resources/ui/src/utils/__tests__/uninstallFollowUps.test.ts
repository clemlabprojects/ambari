import { uninstallFollowUps } from '../uninstallFollowUps';

/** The Releases page shows what an uninstall leaves to an administrator, and nothing when it leaves nothing. */
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

  it('is empty when nothing is left, or when the note only says what was done', () => {
    expect(uninstallFollowUps(undefined)).toEqual([]);
    expect(uninstallFollowUps({})).toEqual([]);
    expect(uninstallFollowUps({ polarisCleanup: "Removed the Polaris principal 'trino'." })).toEqual([]);
  });
});
